package kz.epharm.merch.staffaccess

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.entity.AdminUserEntity
import kz.epharm.auth.repository.AdminUserRepository
import kz.epharm.auth.service.JwtService
import kz.epharm.shared.error.AppException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest(properties = ["app.merch.staff-access-enabled=true"])
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class StaffAccessIntegrationTest {
    companion object {
        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("staff_access_disposable").withUsername("epharm").withPassword("synthetic-only").apply { start() }
        @JvmStatic @DynamicPropertySource fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }; registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
        }
        private val MONTH = LocalDate.of(2027, 1, 1)
    }
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var users: AdminUserRepository
    @Autowired lateinit var jwt: JwtService
    @Autowired lateinit var service: StaffAccessService
    @Autowired lateinit var tokens: StaffAccessTokens
    @Autowired lateinit var transactions: PlatformTransactionManager
    @MockkBean lateinit var clock: Clock
    private lateinit var now: Instant
    private lateinit var actor: AdminUserEntity
    private lateinit var bearer: String
    private lateinit var organization: UUID
    private lateinit var foreignOrganization: UUID
    private lateinit var branch: UUID
    private lateinit var otherBranch: UUID
    private lateinit var foreignBranch: UUID

    @BeforeEach fun seed() {
        jdbc.execute("""TRUNCATE merch_staff_access_attempts,merch_staff_access_sessions,merch_staff_access_code_branches,
            merch_staff_access_codes,merch_staff_visits,merch_branches,merch_organizations,
            unified_identity_audit,unified_admin_profiles,unified_persons,admin_users CASCADE""")
        now = Instant.now(); every { clock.instant() } answers { now }
        actor = users.saveAndFlush(AdminUserEntity(email = "staff-hq@example.test", name = "Synthetic HQ",
            passwordHash = "not-used", roleRaw = AdminRole.HQ_HEAD.name))
        bearer = "Bearer ${jwt.issueAccessToken(actor)}"
        jdbc.update("UPDATE admin_users SET access_mode='MANAGED' WHERE id=?", actor.id)
        val person = UUID.randomUUID()
        jdbc.update("INSERT INTO unified_persons(id,verification_reference) VALUES(?,'synthetic-staff-test-proof')", person)
        jdbc.update("INSERT INTO unified_admin_profiles(account_id,person_id) VALUES(?,?)", actor.id, person)
        organization = organization("Synthetic pharmacy organization")
        foreignOrganization = organization("Foreign synthetic organization")
        branch = branch(organization, "A"); otherBranch = branch(organization, "B"); foreignBranch = branch(foreignOrganization, "C")
        grants()
    }
    private fun organization(name: String): UUID {
        val id = UUID.randomUUID()
        jdbc.update("""INSERT INTO merch_organizations(id,source_system,source_origin,source_id,source_payload,name,legal_name,active,created_at,updated_at)
            VALUES(?,'MERCH_CRM','https://crm.inkar.kz',?,'{}',?,?,true,?,?)""", id, id, name, name, Timestamp.from(now), Timestamp.from(now))
        return id
    }
    private fun branch(organization: UUID, code: String): UUID {
        val id = UUID.randomUUID()
        jdbc.update("""INSERT INTO merch_branches(id,source_system,source_origin,source_id,source_payload,organization_id,code,name,address,city,active,created_at,updated_at)
            VALUES(?,'MERCH_CRM','https://crm.inkar.kz',?,'{}',?,?,?,'Synthetic address','Synthetic city',true,?,?)""",
            id, id, organization, code, "Synthetic branch $code", Timestamp.from(now), Timestamp.from(now))
        return id
    }
    private fun grants(actions: List<String> = listOf("view", "create", "revoke"), branches: Set<UUID> = setOf(branch), all: Boolean = false) {
        val records = actions.map { mapOf("module" to "merchandising", "subsection" to "staff_access", "action" to it,
            "allPharmacies" to false, "pharmacyIds" to emptyList<String>(), "allMerchBranches" to all,
            "merchBranchIds" to if (all) emptyList() else branches.map(UUID::toString)) }
        jdbc.update("UPDATE unified_admin_profiles SET grants_json=?,version=version+1 WHERE account_id=?", json.writeValueAsString(records), actor.id)
    }
    private fun create(branches: Set<UUID> = setOf(branch), deadline: Instant? = now.plusSeconds(3600)): StaffAccessCreated =
        json.readValue(mvc.perform(post("/api/admin/merch/staff-access").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(CreateStaffAccess(organization, MONTH, branches, deadline))))
            .andExpect(status().isOk).andExpect(header().string("Cache-Control", "no-store")).andReturn().response.contentAsByteArray)
    private fun code(value: String, ip: String = "192.0.2.1") = mvc.perform(post("/api/staff/auth/code")
        .with { it.remoteAddr = ip; it }.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(StaffCodeRequest(value))))
    private fun qr(value: String, ip: String = "192.0.2.2") = mvc.perform(post("/api/staff/auth/qr")
        .with { it.remoteAddr = ip; it }.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(StaffQrRequest(value))))
    private fun qrSession(created: StaffAccessCreated): StaffAccessLogin = json.readValue(qr(created.accessToken).andExpect(status().isOk)
        .andExpect(header().string("Cache-Control", "no-store")).andReturn().response.contentAsByteArray)
    private fun me(token: String) = mvc.perform(get("/api/staff/auth/me").header(StaffAccessTokens.HEADER, token))
    private fun scalar(sql: String, vararg args: Any) = jdbc.queryForObject(sql, Long::class.java, *args)!!
    private fun legacy(code: String, branches: Set<UUID> = setOf(branch), all: Boolean = false): UUID {
        val id = UUID.randomUUID()
        // The source producer uses the same BCrypt10 $2a$ wire format; no real source credential is read.
        jdbc.update("""INSERT INTO merch_staff_access_codes(id,source_system,source_origin,source_id,source_payload,organization_id,
            placement_month,code_hash,all_org_branches,deadline_at,status,created_at,updated_at)
            VALUES(?,'MERCH_CRM','https://crm.inkar.kz',?,'{"retained":"source-history"}',?,?,?,?,?,'active',?,?)""",
            id, id, organization, MONTH, BCryptPasswordEncoder(10).encode(code), all, Timestamp.from(now.plusSeconds(3600)), Timestamp.from(now), Timestamp.from(now))
        branches.forEach { jdbc.update("INSERT INTO merch_staff_access_code_branches(code_id,branch_id,created_at) VALUES(?,?,?)", id, it, Timestamp.from(now)) }
        return id
    }

    @Test fun `one time code closes new exchanges but preserves its scoped twelve hour session`() {
        val created = create()
        val session: StaffAccessLogin = json.readValue(code(created.code).andExpect(status().isOk).andReturn().response.contentAsByteArray)
        assertThat(session.scope.branchIds).containsExactly(branch)
        assertThat(session.scope.placementMonth).isEqualTo(MONTH)
        assertThat(session.expiresAt).isEqualTo(now.plusSeconds(43200))
        code(created.code).andExpect(status().isUnauthorized)
        qr(created.accessToken).andExpect(status().isUnauthorized)
        me(session.token).andExpect(status().isOk)
        assertThat(scalar("SELECT use_count FROM merch_staff_access_codes WHERE id=?", created.grant.codeId)).isEqualTo(1)
        assertThat(scalar("SELECT count(*) FROM merch_staff_access_sessions")).isEqualTo(1)
        assertThat(scalar("SELECT count(*) FROM unified_persons")).isEqualTo(1) // Only the pre-existing admin identity.
    }

    @Test fun `QR sessions do not consume numeric quota and neither token is a personal or administrator identity`() {
        val created = create(); val first = qrSession(created); qrSession(created)
        assertThat(scalar("SELECT use_count FROM merch_staff_access_codes")).isZero()
        assertThat(scalar("SELECT qr_use_count FROM merch_staff_access_codes")).isEqualTo(2)
        me(first.token).andExpect(status().isOk)
        me(created.accessToken).andExpect(status().isUnauthorized)
        mvc.perform(get("/api/admin/merch/staff-access/options").header(StaffAccessTokens.HEADER, first.token)).andExpect(status().isUnauthorized)
        mvc.perform(get("/api/mobile/auth/me").header(StaffAccessTokens.HEADER, first.token)).andExpect(status().isUnauthorized)
        assertThat(jdbc.queryForObject("SELECT token_hash FROM merch_staff_access_sessions WHERE id=?", String::class.java, first.scope.sessionId))
            .isEqualTo(StaffAccessTokens.sessionHash(first.token)).isNotEqualTo(first.token)
    }

    @Test fun `deadline makes issued session read only while new login is denied and logout remains available`() {
        val created = create(deadline = now.plusSeconds(30)); val session = qrSession(created)
        now = now.plusSeconds(31)
        me(session.token).andExpect(status().isOk).andExpect(jsonPath("$.readOnly").value(true))
        qr(created.accessToken).andExpect(status().isUnauthorized)
        assertThrows(AppException::class.java) { TransactionTemplate(transactions).execute { service.requireSession(session.token, true) } }
        mvc.perform(post("/api/staff/auth/logout").header(StaffAccessTokens.HEADER, session.token)).andExpect(status().isNoContent)
        me(session.token).andExpect(status().isUnauthorized)
    }

    @Test fun `manual revoke immediately closes every session even after code quota exhaustion`() {
        val created = create(); val first = qrSession(created)
        val second: StaffAccessLogin = json.readValue(code(created.code).andExpect(status().isOk).andReturn().response.contentAsByteArray)
        mvc.perform(post("/api/admin/merch/staff-access/${created.grant.codeId}/revoke").header("Authorization", bearer)).andExpect(status().isOk)
        val firstRevocation = jdbc.queryForMap("SELECT * FROM merch_staff_access_codes WHERE id=?", created.grant.codeId)
        val otherAdmin = users.saveAndFlush(AdminUserEntity(email = "second-staff-hq@example.test", name = "Second synthetic HQ",
            passwordHash = "not-used", roleRaw = AdminRole.HQ_HEAD.name))
        jdbc.update("UPDATE admin_users SET access_mode='MANAGED' WHERE id=?", otherAdmin.id)
        val secondPerson = UUID.randomUUID()
        jdbc.update("INSERT INTO unified_persons(id,verification_reference) VALUES(?,'second-synthetic-staff-proof')", secondPerson)
        jdbc.update("""INSERT INTO unified_admin_profiles(account_id,person_id,grants_json)
            SELECT ?,?,grants_json FROM unified_admin_profiles WHERE account_id=?""", otherAdmin.id, secondPerson, actor.id)
        now = now.plusSeconds(60)
        mvc.perform(post("/api/admin/merch/staff-access/${created.grant.codeId}/revoke")
            .header("Authorization", "Bearer ${jwt.issueAccessToken(otherAdmin)}")).andExpect(status().isOk)
        assertThat(jdbc.queryForMap("SELECT * FROM merch_staff_access_codes WHERE id=?", created.grant.codeId)).isEqualTo(firstRevocation)
        assertThat(firstRevocation["revoked_by_account_id"]).isEqualTo(actor.id)
        me(first.token).andExpect(status().isUnauthorized); me(second.token).andExpect(status().isUnauthorized)
        assertThat(scalar("SELECT count(*) FROM merch_staff_access_sessions WHERE revoked_at IS NOT NULL")).isEqualTo(2)
        assertThat(scalar("SELECT count(*) FROM unified_identity_audit WHERE operation='REVOKE'")).isEqualTo(1)
    }

    @Test fun `expiry active organization and branch are rechecked without a side effecting GET`() {
        val created = create(deadline = null); val session = qrSession(created)
        val before = jdbc.queryForMap("SELECT * FROM merch_staff_access_codes")
        jdbc.update("UPDATE merch_branches SET active=false WHERE id=?", branch)
        me(session.token).andExpect(status().isUnauthorized)
        jdbc.update("UPDATE merch_branches SET active=true WHERE id=?", branch)
        jdbc.update("UPDATE merch_organizations SET active=false WHERE id=?", organization)
        me(session.token).andExpect(status().isUnauthorized)
        jdbc.update("UPDATE merch_organizations SET active=true WHERE id=?", organization)
        now = session.expiresAt.plusSeconds(1)
        me(session.token).andExpect(status().isUnauthorized)
        assertThat(jdbc.queryForMap("SELECT * FROM merch_staff_access_codes")).isEqualTo(before)
    }

    @Test fun `legacy bcrypt format and explicit all organization scope are preserved`() {
        val id = legacy("004275", branches = emptySet(), all = true)
        jdbc.update("UPDATE merch_staff_access_codes SET note='Preserved source note' WHERE id=?", id)
        val before = jdbc.queryForMap("SELECT source_id,source_origin,source_payload,code_hash FROM merch_staff_access_codes WHERE id=?", id)
        val session: StaffAccessLogin = json.readValue(code("004-275").andExpect(status().isOk).andReturn().response.contentAsByteArray)
        assertThat(session.scope.branchIds).containsExactlyInAnyOrder(branch, otherBranch)
        grants(all = true)
        mvc.perform(get("/api/admin/merch/staff-access").param("organizationId", organization.toString()).param("month", MONTH.toString())
            .header("Authorization", bearer)).andExpect(status().isOk).andExpect(jsonPath("$.codes[0].note").value("Preserved source note"))
        assertThat(jdbc.queryForMap("SELECT source_id,source_origin,source_payload,code_hash FROM merch_staff_access_codes WHERE id=?", id)).isEqualTo(before)
        assertThat(StaffCodeRequest("004275").toString()).doesNotContain("004275")
        assertThat(session.toString()).doesNotContain(session.token)
    }

    @Test fun `ambiguous legacy code never picks a newest pharmacy and bounded fallback fails closed`() {
        legacy("004275"); legacy("004275", branches = setOf(otherBranch))
        code("004275").andExpect(status().isUnauthorized)
        assertThat(scalar("SELECT count(*) FROM merch_staff_access_sessions")).isZero()
        repeat(15) { legacy((100000 + it).toString()) }
        code("004275", "192.0.2.8").andExpect(status().isTooManyRequests)
        assertThat(scalar("SELECT coalesce(sum(use_count),0) FROM merch_staff_access_codes")).isZero()
    }

    @Test fun `three failed attempts are shared by IP and mode and denied retries do not append rows`() {
        repeat(3) { code("not-a-code").andExpect(status().isUnauthorized) }
        val before = scalar("SELECT count(*) FROM merch_staff_access_attempts")
        repeat(4) { code("123456").andExpect(status().isTooManyRequests) }
        assertThat(scalar("SELECT count(*) FROM merch_staff_access_attempts")).isEqualTo(before)
        qr("wrong").andExpect(status().isUnauthorized) // Separate QR failure counter, matching the source contract.
    }

    @Test fun `global budgets are enforced without unbounded denied records and purge never removes source history`() {
        val oldLegacy = UUID.randomUUID(); val oldNative = UUID.randomUUID(); val recent = UUID.randomUUID()
        for ((id, source, age) in listOf(Triple(oldLegacy, oldLegacy, 90000L), Triple(oldNative, null, 90000L), Triple(recent, null, 10L)))
            jdbc.update("""INSERT INTO merch_staff_access_attempts(id,access_type,lookup_hash,ip_hash,success,bcrypt_cost,created_at,source_id)
                VALUES(?,'qr',?,?,true,0,?,?)""", id, "a".repeat(64), "b".repeat(64), Timestamp.from(now.minusSeconds(age)), source)
        code("wrong").andExpect(status().isUnauthorized)
        assertThat(scalar("SELECT count(*) FROM merch_staff_access_attempts WHERE id=?", oldLegacy)).isEqualTo(1)
        assertThat(scalar("SELECT count(*) FROM merch_staff_access_attempts WHERE id=?", oldNative)).isZero()
        assertThat(scalar("SELECT count(*) FROM merch_staff_access_attempts WHERE id=?", recent)).isEqualTo(1)
        jdbc.update("UPDATE merch_staff_access_attempts SET bcrypt_cost=17 WHERE id=?", recent)
        repeat(3) { jdbc.update("""INSERT INTO merch_staff_access_attempts(id,access_type,lookup_hash,ip_hash,success,bcrypt_cost,created_at)
            VALUES(?,'code',?,?,true,16,?)""", UUID.randomUUID(), "a".repeat(64), "b".repeat(64), Timestamp.from(now)) }
        val before = scalar("SELECT count(*) FROM merch_staff_access_attempts")
        code("123456", "192.0.2.50").andExpect(status().isTooManyRequests)
        assertThat(scalar("SELECT count(*) FROM merch_staff_access_attempts")).isEqualTo(before)
    }

    @Test fun `two concurrent exchanges of the same one time code have one winner`() {
        val created = create(); val start = CountDownLatch(1); val pool = Executors.newFixedThreadPool(2)
        try {
            val results = (1..2).map { index -> pool.submit(Callable {
                start.await(5, TimeUnit.SECONDS); code(created.code, "192.0.2.$index").andReturn().response.status
            }) }
            start.countDown()
            assertThat(results.map { it.get(20, TimeUnit.SECONDS) }).containsExactlyInAnyOrder(200, 401)
        } finally { pool.shutdownNow() }
        assertThat(scalar("SELECT count(*) FROM merch_staff_access_sessions")).isEqualTo(1)
        assertThat(scalar("SELECT use_count FROM merch_staff_access_codes")).isEqualTo(1)
    }

    @Test fun `admin scopes cover every branch and revoked grants never fall back to a role`() {
        mvc.perform(get("/api/admin/merch/staff-access/options").header("Authorization", bearer)).andExpect(status().isOk)
            .andExpect(jsonPath("$.organizations.length()").value(1)).andExpect(jsonPath("$.organizations[0].branches.length()").value(1))
        mvc.perform(post("/api/admin/merch/staff-access").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(CreateStaffAccess(organization, MONTH, setOf(otherBranch))))).andExpect(status().isForbidden)
        grants(all = true); val created = create(setOf(branch, otherBranch))
        grants()
        mvc.perform(post("/api/admin/merch/staff-access/${created.grant.codeId}/revoke").header("Authorization", bearer)).andExpect(status().isForbidden)
        mvc.perform(get("/api/admin/merch/staff-access").param("organizationId", organization.toString()).param("month", MONTH.toString())
            .header("Authorization", bearer)).andExpect(status().isOk).andExpect(jsonPath("$.codes.length()").value(0))
        grants(actions = emptyList())
        mvc.perform(get("/api/admin/merch/staff-access/options").header("Authorization", bearer)).andExpect(status().isForbidden)
        assertThat(scalar("SELECT count(*) FROM merch_staff_access_codes WHERE manual_revoked_at IS NOT NULL")).isZero()
    }

    @Test fun `empty assigned scope and mismatched organization cannot issue access or disclose secrets`() {
        grants(branches = emptySet())
        mvc.perform(post("/api/admin/merch/staff-access").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(CreateStaffAccess(organization, MONTH, setOf(branch))))).andExpect(status().isForbidden)
        grants(all = true)
        mvc.perform(post("/api/admin/merch/staff-access").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(CreateStaffAccess(organization, MONTH, setOf(foreignBranch))))).andExpect(status().isForbidden)
        val created = create()
        val body = mvc.perform(get("/api/admin/merch/staff-access").param("organizationId", organization.toString()).param("month", MONTH.toString())
            .header("Authorization", bearer)).andExpect(status().isOk).andReturn().response.contentAsByteArray.toString(Charsets.UTF_8)
        assertThat(body).doesNotContain(created.code, created.accessToken, "codeHash", "qrTokenHash", "password", "phone", "personId")
        assertThat(scalar("SELECT count(*) FROM merch_staff_access_codes")).isEqualTo(1)
    }
}
