package kz.epharm.auth

import com.fasterxml.jackson.databind.ObjectMapper
import kz.epharm.auth.access.*
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.entity.AdminUserEntity
import kz.epharm.auth.repository.AdminUserRepository
import kz.epharm.auth.security.AdminPrincipal
import kz.epharm.shared.error.AppException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class UnifiedAccessRevocationConcurrencyTest {
    companion object {
        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("access_race_test").withUsername("epharm").withPassword("local-test-only").apply { start() }
        @JvmStatic @DynamicPropertySource fun props(reg: DynamicPropertyRegistry) {
            reg.add("spring.datasource.url") { postgres.jdbcUrl }
            reg.add("spring.datasource.username") { postgres.username }
            reg.add("spring.datasource.password") { postgres.password }
        }
    }
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var users: AdminUserRepository
    @Autowired lateinit var encoder: PasswordEncoder
    @Autowired lateinit var store: UnifiedAccessStore
    @Autowired lateinit var service: UnifiedAccountAccessService
    @Autowired lateinit var transactions: PlatformTransactionManager

    @Test fun `committing revocation serializes before queued delegation and cannot issue stale authority`() {
        val password = "Synthetic-concurrency-password"
        val accounts = (1..2).map { i -> users.saveAndFlush(AdminUserEntity(email = "race-$i-${UUID.randomUUID()}@example.invalid",
            name = "Synthetic", roleRaw = AdminRole.HQ_HEAD.name, passwordHash = encoder.encode(password))) }
        val people = accounts.map { UUID.randomUUID() }
        val permission = StoredGrant("pharmacies_products", "pharmacies", "view", allPharmacies = true)
        val actorGrants = listOf(permission, StoredGrant("management", "accounts", "manage", allPharmacies = true))
        accounts.forEachIndexed { i, user ->
            jdbc.update("insert into unified_persons values(?,?)", people[i], "synthetic-race-proof")
            jdbc.update("insert into unified_admin_profiles(account_id,person_id,grants_json) values(?,?,?)",
                user.id, people[i], mapper.writeValueAsString(if (i == 0) actorGrants else emptyList<StoredGrant>()))
            jdbc.update("update admin_users set access_mode='MANAGED' where id=?", user.id)
        }
        val pool = Executors.newFixedThreadPool(2)
        val revocationLocked = CountDownLatch(1)
        val commitRevocation = CountDownLatch(1)
        try {
            val revoke = pool.submit {
                TransactionTemplate(transactions).executeWithoutResult {
                    store.lockAccounts(setOf(accounts[0].id))
                    jdbc.update("update unified_admin_profiles set grants_json='[]',version=version+1 where account_id=?", accounts[0].id)
                    revocationLocked.countDown()
                    check(commitRevocation.await(10, TimeUnit.SECONDS))
                }
            }
            check(revocationLocked.await(10, TimeUnit.SECONDS))
            val delegate = pool.submit<HttpStatus> {
                val actor = accounts[0]
                try {
                    service.replace(AdminPrincipal(actor.id, actor.email, actor.name, actor.role, actor.company), accounts[1].id,
                        ReplaceGrantsRequest(0, listOf(permission), password))
                    HttpStatus.OK
                } catch (failure: AppException) { failure.status }
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var waiting = false
            while (System.nanoTime() < deadline && !waiting && !delegate.isDone) {
                waiting = jdbc.queryForObject("select count(*) from pg_locks where not granted", Long::class.java)!! > 0
                if (!waiting) Thread.sleep(10)
            }
            assertThat(waiting).describedAs("delegation must wait on the current actor row, not use pre-revocation grants").isTrue()
            commitRevocation.countDown()
            revoke.get(10, TimeUnit.SECONDS)
            assertThat(delegate.get(10, TimeUnit.SECONDS)).isEqualTo(HttpStatus.FORBIDDEN)
            assertThat(store.profile(accounts[1].id)!!.grants).isEmpty()
            assertThat(store.profile(accounts[1].id)!!.version).isZero()
        } finally {
            commitRevocation.countDown()
            pool.shutdownNow()
            pool.awaitTermination(10, TimeUnit.SECONDS)
            accounts.forEach { jdbc.update("delete from unified_admin_access_audit where actor_id=? or target_id=?", it.id, it.id) }
            accounts.forEach { users.deleteById(it.id) }
            people.forEach { jdbc.update("delete from unified_persons where id=?", it) }
        }
    }
}
