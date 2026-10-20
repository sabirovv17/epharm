package kz.epharm.receipts

import java.io.ByteArrayOutputStream
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.entity.AdminUserEntity
import kz.epharm.auth.repository.AdminUserRepository
import kz.epharm.auth.service.JwtService
import kz.epharm.pharmacies.entity.ChainEntity
import kz.epharm.pharmacies.entity.PharmacyEntity
import kz.epharm.pharmacies.repository.ChainRepository
import kz.epharm.pharmacies.repository.PharmacyRepository
import kz.epharm.pharmacists.entity.PharmacistEntity
import kz.epharm.pharmacists.repository.PharmacistRepository
import kz.epharm.receipts.entity.PendingBonusEntity
import kz.epharm.receipts.entity.ReceiptEntity
import kz.epharm.receipts.repository.PendingBonusRepository
import kz.epharm.receipts.repository.ReceiptRepository
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/** Real JWT/filter/method-security/DB boundary: an Excel import can itself approve and credit. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Transactional
class ReconcileMoneyAuthorizationIntegrationTest {
    companion object {
        @Container @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("reconcile_roles").withUsername("epharm").withPassword("test-only")
            .apply { start() }

        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
        }
    }

    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var jwt: JwtService
    @Autowired private lateinit var admins: AdminUserRepository
    @Autowired private lateinit var pharmacists: PharmacistRepository
    @Autowired private lateinit var pharmacies: PharmacyRepository
    @Autowired private lateinit var chains: ChainRepository
    @Autowired private lateinit var pending: PendingBonusRepository
    @Autowired private lateinit var receipts: ReceiptRepository
    @Autowired private lateinit var jdbc: JdbcTemplate
    private val allowed = listOf(AdminRole.HQ_HEAD, AdminRole.FINANCE_REVIEWER, AdminRole.SYSTEM_ADMIN)
    private val commercial = listOf(AdminRole.BRAND_MANAGER, AdminRole.CATEGORY_LEAD)

    @BeforeEach
    fun seed() {
        chains.save(ChainEntity(id = "ch_roles", name = "Test"))
        pharmacies.save(PharmacyEntity(id = "ph_roles", name = "Test", chainId = "ch_roles", chainName = "Test"))
        pharmacists.save(PharmacistEntity(id = "u_roles", name = "Test", iin = "900115300011", phone = "+77001112233",
            pharmacyId = "ph_roles", pharmacyName = "Test"))
    }

    @Test
    fun `brand and category cannot approve reject or import financial evidence`() {
        val receipt = receipt("blocked")
        commercial.forEach { role ->
            val bearer = bearer(role)
            mvc.perform(post("/api/admin/reconcile/${receipt.id}/approve").header("Authorization", bearer))
                .andExpect(status().isForbidden)
            mvc.perform(post("/api/admin/reconcile/${receipt.id}/reject").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content("""{"reason":"Test rejection"}"""))
                .andExpect(status().isForbidden)
            mvc.perform(multipart("/api/admin/reconcile/import-excel").file(excel()).header("Authorization", bearer))
                .andExpect(status().isForbidden)
        }
        assertThat(pharmacists.findById("u_roles").orElseThrow().balance).isZero()
        assertThat(receipts.findById(receipt.id).orElseThrow().statusRaw).isEqualTo("pending")
    }

    @Test
    fun `HQ finance and system admin can approve a receipt and credit its pending once`() {
        allowed.forEachIndexed { index, role ->
            val receipt = receipt("approve_$index")
            mvc.perform(post("/api/admin/reconcile/${receipt.id}/approve").header("Authorization", bearer(role)))
                .andExpect(status().isOk).andExpect(jsonPath("$.status").value("approved"))
                .andExpect(jsonPath("$.bonusCredited").value(250))
        }
        assertThat(pharmacists.findById("u_roles").orElseThrow().balance).isEqualTo(750)
    }

    @Test
    fun `HQ finance and system admin can reject an unpaid receipt`() {
        allowed.forEachIndexed { index, role ->
            val receipt = receipt("reject_$index")
            mvc.perform(post("/api/admin/reconcile/${receipt.id}/reject").header("Authorization", bearer(role))
                .contentType(MediaType.APPLICATION_JSON).content("""{"reason":"Test rejection"}"""))
                .andExpect(status().isOk).andExpect(jsonPath("$.status").value("rejected"))
        }
        assertThat(pharmacists.findById("u_roles").orElseThrow().balance).isZero()
    }

    @Test
    fun `HQ finance and system admin can import reconciliation Excel`() {
        allowed.forEach { role ->
            mvc.perform(multipart("/api/admin/reconcile/import-excel").file(excel()).header("Authorization", bearer(role)))
                .andExpect(status().isOk).andExpect(jsonPath("$.rowsTotal").value(0))
        }
    }

    @Test
    fun `commercial roles retain receipt reads and normal photo submission`() {
        val receipt = receipt("read")
        commercial.forEach { role ->
            val bearer = bearer(role)
            listOf("/api/admin/reconcile", "/api/admin/reconcile/summary", "/api/admin/reconcile/${receipt.id}").forEach { path ->
                mvc.perform(get(path).header("Authorization", bearer)).andExpect(status().isOk)
            }
            mvc.perform(multipart("/api/admin/reconcile/submit")
                .file(MockMultipartFile("file", "receipt.jpg", "image/jpeg", byteArrayOf(1, 2, 3)))
                .param("pharmacistId", "u_roles").header("Authorization", bearer))
                .andExpect(status().isCreated)
        }
        assertThat(pharmacists.findById("u_roles").orElseThrow().balance).isZero()
    }

    @Test
    fun `unauthenticated requests cannot reach financial mutations`() {
        mvc.perform(post("/api/admin/reconcile/no-receipt/approve")).andExpect(status().isUnauthorized)
        mvc.perform(post("/api/admin/reconcile/no-receipt/reject").contentType(MediaType.APPLICATION_JSON)
            .content("""{"reason":"Test rejection"}""")).andExpect(status().isUnauthorized)
        mvc.perform(multipart("/api/admin/reconcile/import-excel").file(excel())).andExpect(status().isUnauthorized)
    }

    @Test
    fun `commercial cannot set wallet balance even to its current value or with other profile fields`() {
        commercial.forEach { role ->
            val bearer = bearer(role)
            listOf("""{"balance":1000000,"name":"Must not change"}""", """{"balance":0}""").forEach { body ->
                mvc.perform(patch("/api/admin/pharmacists/u_roles").header("Authorization", bearer)
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden)
            }
        }
        val person = pharmacists.findById("u_roles").orElseThrow()
        assertThat(person.balance).isZero()
        assertThat(person.name).isEqualTo("Test")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pharmacist_wallet_entries", Long::class.java)).isZero()
    }

    @Test
    fun `financial roles may adjust balance through profile with an audited ledger entry`() {
        allowed.forEachIndexed { index, role ->
            val amount = (index + 1) * 1000
            mvc.perform(patch("/api/admin/pharmacists/u_roles").header("Authorization", bearer(role))
                .contentType(MediaType.APPLICATION_JSON).content("""{"balance":$amount}"""))
                .andExpect(status().isOk).andExpect(jsonPath("$.balance").value(amount))
        }
        assertThat(pharmacists.findById("u_roles").orElseThrow().balance).isEqualTo(3000)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pharmacist_wallet_entries WHERE source='admin_adjustment'", Long::class.java)).isEqualTo(3)
    }

    @Test
    fun `commercial profile changes without a balance remain permitted`() {
        commercial.forEach { role ->
            mvc.perform(patch("/api/admin/pharmacists/u_roles").header("Authorization", bearer(role))
                .contentType(MediaType.APPLICATION_JSON).content("""{"name":"Updated ${role.name}"}"""))
                .andExpect(status().isOk).andExpect(jsonPath("$.name").value("Updated ${role.name}"))
        }
        assertThat(pharmacists.findById("u_roles").orElseThrow().balance).isZero()
    }

    private fun bearer(role: AdminRole): String {
        val admin = admins.save(AdminUserEntity(email = "${role.name.lowercase()}@roles.test", name = "Test reviewer",
            passwordHash = "test-only-not-used", company = "Test").also { it.role = role })
        return "Bearer ${jwt.issueAccessToken(admin)}"
    }

    private fun receipt(suffix: String): ReceiptEntity {
        val bonus = pending.save(PendingBonusEntity(id = "pb_roles_$suffix", pharmacistId = "u_roles", pharmacistName = "Test",
            pharmacyId = "ph_roles", pharmacyName = "Test", sku = "p_roles_$suffix", productName = "Test", expectedAmount = 1000, bonus = 250))
        return receipts.save(ReceiptEntity(id = "receipt_roles_$suffix", pharmacistId = "u_roles", pharmacistName = "Test",
            pharmacyId = "ph_roles", pharmacyName = "Test", pendingBonusId = bonus.id))
    }

    private fun excel(): MockMultipartFile {
        val bytes = XSSFWorkbook().use { workbook ->
            val header = workbook.createSheet("Sales").createRow(0)
            header.createCell(0).setCellValue("Артикул")
            header.createCell(1).setCellValue("Сумма")
            ByteArrayOutputStream().use { output -> workbook.write(output); output.toByteArray() }
        }
        return MockMultipartFile("file", "test-sales.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes)
    }
}
