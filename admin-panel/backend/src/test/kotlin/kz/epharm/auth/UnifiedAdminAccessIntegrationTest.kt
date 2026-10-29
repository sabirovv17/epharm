package kz.epharm.auth

import com.fasterxml.jackson.databind.ObjectMapper
import kz.epharm.auth.access.*
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.entity.AdminUserEntity
import kz.epharm.auth.repository.AdminUserRepository
import kz.epharm.auth.service.JwtService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Transactional
class UnifiedAdminAccessIntegrationTest {
    companion object {
        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("unified_access_test").withUsername("epharm").withPassword("local-test-only").apply { start() }
        @JvmStatic @DynamicPropertySource fun props(reg: DynamicPropertyRegistry) {
            reg.add("spring.datasource.url") { postgres.jdbcUrl }
            reg.add("spring.datasource.username") { postgres.username }
            reg.add("spring.datasource.password") { postgres.password }
        }
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var users: AdminUserRepository
    @Autowired lateinit var jwt: JwtService
    @Autowired lateinit var encoder: PasswordEncoder
    @Autowired lateinit var catalog: UnifiedAdminCatalog
    private lateinit var admin: AdminUserEntity
    private lateinit var token: String
    private lateinit var person: UUID
    private val password = "Synthetic-admin-test-2026"

    @BeforeEach fun seed() {
        admin = users.saveAndFlush(AdminUserEntity(email = "access-${UUID.randomUUID()}@example.invalid", name = "Access Test",
            roleRaw = AdminRole.HQ_HEAD.name, passwordHash = encoder.encode(password)))
        token = jwt.issueAccessToken(admin)
        person = UUID.randomUUID()
        jdbc.update("insert into unified_persons values(?,?)", person, "synthetic-test-proof")
        jdbc.update("insert into unified_admin_profiles(account_id,person_id) values(?,?)", admin.id, person)
        jdbc.update("update admin_users set access_mode='MANAGED' where id=?", admin.id)
        jdbc.update("insert into chains(id,name,color,\"group\",points) values('access-chain','Scope test','#000000','pilot',0) on conflict do nothing")
        for ((id, group) in listOf("scope-a" to "pilot", "scope-b" to "pilot", "scope-c" to "control")) {
            jdbc.update("""insert into pharmacies(id,name,chain_id,chain_name,city,district,addr,"group")
                values(?,?,'access-chain','Scope test','Test','','',?) on conflict do nothing""", id, id, group)
        }
        for ((id, pharmacy, status) in listOf(Triple("staff-a", "scope-a", "active"), Triple("staff-b", "scope-b", "active"))) {
            jdbc.update("""insert into pharmacists(id,name,iin,phone,pharmacy_id,pharmacy_name,city,tier,status,joined_at)
                values(?,?,?, ?,?,?,'Test','Silver',?,current_date)""", id, id,
                if (id == "staff-a") "000000000001" else "000000000002",
                if (id == "staff-a") "+77000000001" else "+77000000002", pharmacy, pharmacy, status)
        }
    }

    private fun grant(module: String, subsection: String, action: String, ids: Set<String> = setOf("scope-a"), all: Boolean = false) =
        StoredGrant(module, subsection, action, all, if (all) emptySet() else ids)
    private fun grants(vararg grants: StoredGrant) {
        jdbc.update("update unified_admin_profiles set grants_json=?,version=version+1 where account_id=?", mapper.writeValueAsString(grants.toList()), admin.id)
    }
    private fun auth() = "Bearer $token"

    @Test fun `managed empty permissions deny even signed HQ role and self capabilities survive`() {
        mvc.perform(get("/api/admin/pharmacies").header("Authorization", auth())).andExpect(status().isForbidden)
        mvc.perform(get("/api/admin/access/me").header("Authorization", auth())).andExpect(status().isOk)
            .andExpect(jsonPath("$.managed").value(true)).andExpect(jsonPath("$.grants").isEmpty)
    }

    @Test fun `pharmacy SQL scope intersects both optional filters and direct id`() {
        grants(grant("pharmacies_products", "pharmacies", "view"))
        mvc.perform(get("/api/admin/pharmacies?group=pilot&chainId=access-chain").header("Authorization", auth()))
            .andExpect(status().isOk).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value("scope-a"))
        mvc.perform(get("/api/admin/pharmacies/scope-b").header("Authorization", auth())).andExpect(status().isNotFound)
        mvc.perform(get("/api/admin/pharmacies?group=control&chainId=access-chain").header("Authorization", auth()))
            .andExpect(status().isOk).andExpect(jsonPath("$.length()").value(0))
    }

    @Test fun `status filter never cancels assigned pharmacist scope and personal financial fields are null`() {
        grants(grant("pharmacists", "registry", "view"))
        mvc.perform(get("/api/admin/pharmacists?status=active").header("Authorization", auth()))
            .andExpect(status().isOk).andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value("staff-a"))
            .andExpect(jsonPath("$[0].iin").doesNotExist()).andExpect(jsonPath("$[0].phone").doesNotExist())
            .andExpect(jsonPath("$[0].balance").doesNotExist())
        mvc.perform(get("/api/admin/pharmacists?status=active&pharmacyId=scope-b").header("Authorization", auth()))
            .andExpect(status().isOk).andExpect(jsonPath("$.length()").value(0))
        mvc.perform(get("/api/admin/pharmacists/staff-b").header("Authorization", auth())).andExpect(status().isNotFound)
    }

    @Test fun `personal grant is separate and cannot expose financial fields`() {
        grants(grant("pharmacists", "registry", "view"), grant("pharmacists", "registry", "personal_data"))
        mvc.perform(get("/api/admin/pharmacists/staff-a").header("Authorization", auth())).andExpect(status().isOk)
            .andExpect(jsonPath("$.iin").value("000000000001")).andExpect(jsonPath("$.balance").doesNotExist())
    }

    @Test fun `local financial permission cannot reveal global wallet history and pharmacy money has separate permission`() {
        grants(grant("pharmacists", "registry", "view"), grant("pharmacists", "registry", "financial_data"),
            grant("pharmacies_products", "pharmacies", "view"))
        mvc.perform(get("/api/admin/pharmacists/staff-a").header("Authorization", auth())).andExpect(status().isOk)
            .andExpect(jsonPath("$.balance").doesNotExist()).andExpect(jsonPath("$.receipts30d").doesNotExist())
        mvc.perform(get("/api/admin/pharmacies/scope-a").header("Authorization", auth())).andExpect(status().isOk)
            .andExpect(jsonPath("$.gmv30d").doesNotExist())
        grants(grant("pharmacists", "registry", "view"), grant("pharmacists", "registry", "financial_data", all = true))
        mvc.perform(get("/api/admin/pharmacists/staff-a").header("Authorization", auth())).andExpect(status().isOk)
            .andExpect(jsonPath("$.balance").value(0))
    }

    @Test fun `training role can load own capabilities and scoped roster without sales statistics`() {
        admin.role = AdminRole.TRAINING_MANAGER
        users.saveAndFlush(admin)
        grants(grant("training", "roster", "view"))
        mvc.perform(get("/api/admin/access/me").header("Authorization", auth())).andExpect(status().isOk)
            .andExpect(jsonPath("$.roleCeiling").value("TRAINING_MANAGER"))
        mvc.perform(get("/api/admin/training/pharmacists").header("Authorization", auth())).andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].receipts30d").doesNotExist())
        mvc.perform(get("/api/admin/pharmacies").header("Authorization", auth())).andExpect(status().isForbidden)
    }

    @Test fun `pharmacy delete cannot make targeted playlist global for a managed account`() {
        // The delete route is not grantable yet: old FK uses SET NULL, which means global playlist.
        grants(grant("pharmacies_products", "pharmacies", "delete", all = true))
        mvc.perform(delete("/api/admin/pharmacies/scope-a").header("Authorization", auth())).andExpect(status().isForbidden)
        assertThat(jdbc.queryForObject("select count(*) from pharmacies where id='scope-a'", Long::class.java)).isEqualTo(1)
    }

    @Test fun `grant revocation takes effect with same JWT and removed profile never falls back to legacy`() {
        grants(grant("pharmacies_products", "pharmacies", "view"))
        mvc.perform(get("/api/admin/pharmacies").header("Authorization", auth())).andExpect(status().isOk)
        grants()
        mvc.perform(get("/api/admin/pharmacies").header("Authorization", auth())).andExpect(status().isForbidden)
        jdbc.update("delete from unified_admin_profiles where account_id=?", admin.id)
        mvc.perform(get("/api/admin/pharmacies").header("Authorization", auth())).andExpect(status().isForbidden)
    }

    @Test fun `editing name cannot modify balance password results or counters`() {
        grants(grant("pharmacists", "registry", "edit"), grant("pharmacies_products", "pharmacies", "edit"))
        for (body in listOf("{\"balance\":900}", "{\"password\":\"Synthetic-password\"}", "{\"coursesDone\":4}")) {
            mvc.perform(patch("/api/admin/pharmacists/staff-a").header("Authorization", auth())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden)
        }
        mvc.perform(patch("/api/admin/pharmacies/scope-a").header("Authorization", auth())
            .contentType(MediaType.APPLICATION_JSON).content("{\"gmv30d\":900}" )).andExpect(status().isForbidden)
        mvc.perform(patch("/api/admin/pharmacists/staff-b").header("Authorization", auth())
            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Changed\"}" )).andExpect(status().isNotFound)
    }

    @Test fun `unmigrated export bulk and technical routes deny even with crafted stored grant`() {
        for (path in listOf("/api/admin/promo", "/api/admin/training/pharmacists/staff-b/profile", "/api/admin/dashboard")) {
            mvc.perform(get(path).header("Authorization", auth())).andExpect(status().isForbidden)
        }
        mvc.perform(post("/api/admin/pharmacists/standardn-directory/sync").header("Authorization", auth())).andExpect(status().isForbidden)
    }

    @Test fun `learner token cannot enter access management`() {
        val learner = jwt.issuePharmacistToken("staff-a", "Synthetic", "+77000000001")
        mvc.perform(get("/api/admin/access/me").header("Authorization", "Bearer $learner")).andExpect(status().isForbidden)
    }

    @Test fun `legacy role cannot mint management capability without explicit grants`() {
        jdbc.update("update admin_users set access_mode='LEGACY' where id=?", admin.id)
        mvc.perform(get("/api/admin/pharmacies").header("Authorization", auth())).andExpect(status().isOk)
        mvc.perform(get("/api/admin/access/catalog").header("Authorization", auth())).andExpect(status().isForbidden)
    }

    @Test fun `create and replace enforce password self-change optimistic version and audit`() {
        grants(grant("management", "accounts", "manage", all = true), grant("management", "accounts", "view", all = true),
            grant("pharmacies_products", "pharmacies", "view"), grant("training", "roster", "view", all = true))
        val recipientPerson = UUID.randomUUID()
        jdbc.update("insert into unified_persons values(?,?)", recipientPerson, "synthetic-reviewed-source")
        val create = mapOf("email" to "new-managed@example.invalid", "name" to "Managed Test", "password" to "Synthetic-new-password",
            "role" to "BRAND_MANAGER", "verifiedPersonId" to recipientPerson, "currentPassword" to password)
        val created = mvc.perform(post("/api/admin/access/accounts").header("Authorization", auth())
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(create))).andExpect(status().isCreated)
            .andExpect(jsonPath("$.grants").isEmpty).andReturn()
        val id = mapper.readTree(created.response.contentAsString)["accountId"].asText()
        fun replace(target: String, version: Long, gs: List<StoredGrant>, pwd: String = password) = mvc.perform(
            put("/api/admin/access/accounts/$target/grants").header("Authorization", auth()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("expectedVersion" to version, "grants" to gs, "currentPassword" to pwd))))
        val desired = listOf(grant("pharmacies_products", "pharmacies", "view"))
        replace(id, 0, desired, "wrong-password").andExpect(status().isForbidden)
        replace(admin.id.toString(), 1, emptyList()).andExpect(status().isForbidden)
        replace(id, 0, listOf(grant("pharmacies_products", "pharmacies", "view", ids = setOf("scope-b")))).andExpect(status().isForbidden)
        replace(id, 0, listOf(grant("training", "roster", "view", all = true))).andExpect(status().isBadRequest)
        replace(id, 0, desired).andExpect(status().isOk).andExpect(jsonPath("$.version").value(1))
        replace(id, 0, desired).andExpect(status().isConflict)
        assertThat(jdbc.queryForObject("select count(*) from unified_admin_access_audit where target_id=?", Long::class.java, UUID.fromString(id))).isEqualTo(2)
        assertThat(users.findById(UUID.fromString(id)).get().passwordHash).doesNotContain("Synthetic-new-password")
    }

    @Test fun `catalog never advertises unmigrated routes as grantable and scope empty is invalid`() {
        assertThat(catalog.routes).hasSize(155)
        assertThat(catalog.routes.filter { it.mode == ScopeIntegration.SCOPED }.all {
            it.api.contains(" /api/admin/pharmacists") || it.api.contains(" /api/admin/pharmacies") ||
                it.api == "GET /api/admin/training/pharmacists"
        }).isTrue()
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> { grant("pharmacies_products", "pharmacies", "view", emptySet()).toGrant() }
    }

    @Test fun `HQ management selector returns current account metadata without credentials or private proof`() {
        grants(grant("management", "accounts", "view", all = true), grant("management", "accounts", "manage", all = true),
            grant("pharmacies_products", "pharmacies", "view"))
        mvc.perform(get("/api/admin/access/me").header("Authorization", auth())).andExpect(status().isOk)
            .andExpect(jsonPath("$.canViewAccounts").value(true)).andExpect(jsonPath("$.canManageAccounts").value(true))
        val list = mvc.perform(get("/api/admin/access/accounts").header("Authorization", auth())).andExpect(status().isOk).andReturn()
        val self = mapper.readTree(list.response.contentAsString).first { it["accountId"].asText() == admin.id.toString() }
        assertThat(self["editable"].asBoolean()).isFalse()
        assertThat(self["email"].asText()).isEqualTo(admin.email)
        assertThat(list.response.contentAsString).doesNotContain("passwordHash", password, "grants_json", "verification_reference")
        val other = UUID.randomUUID()
        jdbc.update("insert into unified_persons values(?,?)", other, "private-proof-path-must-not-leak")
        mvc.perform(get("/api/admin/access/people").header("Authorization", auth())).andExpect(status().isOk)
            .andExpect(jsonPath("$[?(@.personId == '$other')].referenceLabel").value("Связь подтверждена"))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-proof-path"))))
    }

    @Test fun `management catalog uses actor scope without inferring permissions from HQ title`() {
        grants(grant("management", "accounts", "view", all = true), grant("management", "accounts", "manage", all = true),
            grant("pharmacies_products", "pharmacies", "view"))
        val result = mvc.perform(get("/api/admin/access/catalog").header("Authorization", auth())).andExpect(status().isOk).andReturn()
        val body = mapper.readTree(result.response.contentAsString)
        assertThat(body["pharmacies"].map { it["id"].asText() }).containsExactly("scope-a")
        val options = body["permissions"].toList()
        val view = options.single { it["module"].asText() == "pharmacies_products" && it["subsection"].asText() == "pharmacies" && it["action"].asText() == "view" }
        assertThat(view["delegatable"].asBoolean()).isTrue()
        assertThat(view["delegationScope"]["pharmacyIds"].map { it.asText() }).containsExactly("scope-a")
        val finance = options.single { it["module"].asText() == "pharmacists" && it["action"].asText() == "financial_data" }
        assertThat(finance["delegatable"].asBoolean()).isFalse()
        assertThat(options.filter { it["mode"].asText() == "NOT_READY" }.all { !it["delegatable"].asBoolean() }).isTrue()
    }

    @Test fun `management view only account gets no grant authority and no pharmacy selector data`() {
        grants(grant("management", "accounts", "view", all = true))
        mvc.perform(get("/api/admin/access/me").header("Authorization", auth())).andExpect(status().isOk)
            .andExpect(jsonPath("$.canViewAccounts").value(true)).andExpect(jsonPath("$.canManageAccounts").value(false))
        mvc.perform(get("/api/admin/access/catalog").header("Authorization", auth())).andExpect(status().isOk)
            .andExpect(jsonPath("$.pharmacies").isEmpty).andExpect(jsonPath("$.assignableRoles").isEmpty)
    }
}
