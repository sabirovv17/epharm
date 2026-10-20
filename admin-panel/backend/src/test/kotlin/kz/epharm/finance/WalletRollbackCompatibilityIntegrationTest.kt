package kz.epharm.finance

import com.fasterxml.jackson.databind.ObjectMapper
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.entity.AdminUserEntity
import kz.epharm.auth.service.JwtService
import kz.epharm.finance.service.PharmacistWalletService
import kz.epharm.finance.service.WalletCreditSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/** Explicit release gate: supply an independently built exact previous-release jar.
 * The old binary is launched against this class's disposable, forward-migrated PostgreSQL only.
 */
@EnabledIfEnvironmentVariable(named="WALLET_ROLLBACK_JAR",matches=".+")
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class WalletRollbackCompatibilityIntegrationTest {
    companion object {
        @Container @JvmStatic val postgres=PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("wallet_rollback_disposable").withUsername("epharm").withPassword("test-only").apply { start() }
        @JvmStatic @DynamicPropertySource fun properties(reg: DynamicPropertyRegistry) {
            reg.add("spring.datasource.url") { postgres.jdbcUrl }
            reg.add("spring.datasource.username") { postgres.username }
            reg.add("spring.datasource.password") { postgres.password }
        }
    }
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var wallet: PharmacistWalletService
    @Autowired lateinit var jwt: JwtService
    @Autowired lateinit var json: ObjectMapper

    @Test fun `old v38 boots and reads forward schema but its financial writer bypasses the new journal`() {
        val jar=Path.of(System.getenv("WALLET_ROLLBACK_JAR")).toAbsolutePath()
        check(Files.isRegularFile(jar)) { "Supply an independently built exact v38 jar" }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version IN ('062','063','62','63') AND success",Long::class.java)).isEqualTo(2)
        val person="rollback_${UUID.randomUUID()}"
        jdbc.update("""INSERT INTO pharmacists(id,name,iin,phone,tier,status,balance)
            VALUES (?,'ROLLBACK DISPOSABLE TEST','900101300011','+77001112233','Silver','active',250)""",person)
        wallet.credit(WalletCreditSource.TRAINING_BONUS,UUID.randomUUID().toString(),person,100,"rollback-disposable-test")
        val admin=AdminUserEntity(id=UUID.randomUUID(),name="ROLLBACK TEST",email="rollback@test.invalid",company="TEST")
            .also { it.role=AdminRole.HQ_HEAD }
        jdbc.update("""INSERT INTO admin_users(id,email,password_hash,name,role,company,status)
            VALUES (?,?,'unused','ROLLBACK TEST','HQ_HEAD','TEST','active')""",admin.id,admin.email)
        val port=ServerSocket(0).use { it.localPort }
        val log=Files.createTempFile("epharm-v38-rollback-", ".log")
        val process=ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-jar",jar.toString(),
            "--server.address=127.0.0.1","--server.port=$port","--spring.profiles.active=rollback-test",
            "--spring.datasource.url=${postgres.jdbcUrl}","--spring.datasource.username=${postgres.username}","--spring.datasource.password=${postgres.password}",
            "--spring.jpa.hibernate.ddl-auto=validate","--spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
            "--app.jwt.secret=test-secret-key-of-at-least-32-bytes-for-hs256-tests-only-please",
            "--app.otp.dev-mode=true","--app.medusa.enabled=false","--app.storefront.enabled=false","--app.merch-tasks.enabled=false",
            "--app.version=v0.1.38-rollback-compat","--app.commit=757039e1021170351c6215c85a8b888ac4df6c8e")
            .redirectErrorStream(true).redirectOutput(log.toFile()).start()
        val http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
        fun call(path: String,token: String?=null,post: Boolean=false): HttpResponse<String> {
            val request=HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).timeout(Duration.ofSeconds(3))
            token?.let { request.header("Authorization","Bearer $it") }
            if(post) request.POST(HttpRequest.BodyPublishers.noBody())
            return http.send(request.build(),HttpResponse.BodyHandlers.ofString())
        }
        try {
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(45)
            var health: HttpResponse<String>?=null
            while(System.nanoTime()<deadline && process.isAlive) {
                health=runCatching { call("/api/health") }.getOrNull()
                if(health?.statusCode()==200) break
                TimeUnit.MILLISECONDS.sleep(100)
            }
            assertThat(health?.statusCode()).describedAs("Exact old binary startup; diagnostic log: $log").isEqualTo(200)
            assertThat(json.readTree(health!!.body())["commit"].asText()).isEqualTo("757039e1021170351c6215c85a8b888ac4df6c8e")
            val profile=call("/api/mobile/me",jwt.issuePharmacistToken(person,"TEST","+77001112233"))
            assertThat(profile.statusCode()).isEqualTo(200)
            assertThat(json.readTree(profile.body())["balance"].asLong()).isEqualTo(350)
            val payout=call("/api/admin/payouts/generate?period=ROLLBACK-DISPOSABLE-TEST",jwt.issueAccessToken(admin),true)
            assertThat(payout.statusCode()).isEqualTo(200)
            assertThat(jdbc.queryForObject("SELECT balance FROM pharmacists WHERE id=?",Long::class.java,person)).isZero()
            assertThat(jdbc.queryForObject("SELECT coalesce(sum(amount),0) FROM payout_items WHERE pharmacist_id=?",Long::class.java,person)).isEqualTo(350)
            // This is the rollback limitation, not a desired new behavior: old code cannot maintain the new journal.
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pharmacist_wallet_entries WHERE pharmacist_id=?",Long::class.java,person)).isEqualTo(1)
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pharmacist_wallet_entries WHERE pharmacist_id=? AND source='payout_reservation'",Long::class.java,person)).isZero()
            assertThat(call("/api/integrations/merch/reward-events",post=true).statusCode()).isNotIn(200,201)
        } finally {
            process.destroy()
            if(!process.waitFor(10,TimeUnit.SECONDS)) process.destroyForcibly().waitFor(10,TimeUnit.SECONDS)
        }
    }
}
