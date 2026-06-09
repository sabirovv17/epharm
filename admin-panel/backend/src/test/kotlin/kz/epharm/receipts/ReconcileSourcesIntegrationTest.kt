package kz.epharm.receipts

import com.fasterxml.jackson.databind.ObjectMapper
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.domain.AdminUserStatus
import kz.epharm.auth.dto.LoginRequest
import kz.epharm.auth.dto.LoginResponse
import kz.epharm.auth.entity.AdminUserEntity
import kz.epharm.auth.repository.AdminUserRepository
import kz.epharm.pharmacies.entity.ChainEntity
import kz.epharm.pharmacies.entity.PharmacyEntity
import kz.epharm.pharmacies.entity.PharmacyGroup
import kz.epharm.pharmacies.repository.ChainRepository
import kz.epharm.pharmacies.repository.PharmacyRepository
import kz.epharm.pharmacists.entity.PharmacistEntity
import kz.epharm.pharmacists.repository.PharmacistRepository
import kz.epharm.posm.dto.PosSaleItemDto
import kz.epharm.posm.dto.PosSaleRequest
import kz.epharm.posm.repository.PosSaleRepository
import kz.epharm.receipts.entity.PendingBonusEntity
import kz.epharm.receipts.repository.ExcelImportRepository
import kz.epharm.receipts.repository.PendingBonusRepository
import kz.epharm.receipts.repository.ReceiptRepository
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.io.ByteArrayOutputStream
import java.time.Instant

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Transactional
class ReconcileSourcesIntegrationTest {

    companion object {
        private const val POSM_KEY = "dev-posm-key"

        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("epharm_test").withUsername("epharm").withPassword("epharm_test")
            .apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun props(reg: DynamicPropertyRegistry) {
            reg.add("spring.datasource.url") { postgres.jdbcUrl }
            reg.add("spring.datasource.username") { postgres.username }
            reg.add("spring.datasource.password") { postgres.password }
        }
    }

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var receiptRepository: ReceiptRepository
    @Autowired private lateinit var pendingBonusRepository: PendingBonusRepository
    @Autowired private lateinit var posSaleRepository: PosSaleRepository
    @Autowired private lateinit var excelImportRepository: ExcelImportRepository
    @Autowired private lateinit var pharmacistRepository: PharmacistRepository
    @Autowired private lateinit var pharmacyRepository: PharmacyRepository
    @Autowired private lateinit var chainRepository: ChainRepository
    @Autowired private lateinit var adminUserRepository: AdminUserRepository
    @Autowired private lateinit var passwordEncoder: PasswordEncoder

    private lateinit var bearer: String

    @BeforeEach
    fun seed() {
        receiptRepository.deleteAll()
        posSaleRepository.deleteAll()
        excelImportRepository.deleteAll()
        pendingBonusRepository.deleteAll()
        pharmacistRepository.deleteAll()
        pharmacyRepository.deleteAll()
        chainRepository.deleteAll()
        adminUserRepository.deleteAll()

        chainRepository.save(
            ChainEntity(id = "ch_t", name = "Сеть Т", color = "#16C97A", points = 10)
                .also { it.group = PharmacyGroup.pilot },
        )
        pharmacyRepository.save(
            PharmacyEntity(id = "ph_t", name = "Аптека Т", chainId = "ch_t", chainName = "Сеть Т",
                city = "Алматы", district = "", addr = "").also { it.group = PharmacyGroup.pilot },
        )
        pharmacistRepository.save(
            PharmacistEntity(id = "u_t", name = "Тест Фарм", iin = "900115300013", phone = "+77001234567",
                pharmacyId = "ph_t", pharmacyName = "Аптека Т", city = "Алматы", balance = 0, earned30d = 0),
        )
        pendingBonusRepository.save(
            PendingBonusEntity(id = "pb_t", pharmacistId = "u_t", pharmacistName = "Тест Фарм",
                pharmacyId = "ph_t", pharmacyName = "Аптека Т", sku = "p_x", productName = "Товар X",
                expectedAmount = 1_000, bonus = 300, createdAt = Instant.now()),
        )
        adminUserRepository.save(
            AdminUserEntity(email = "damir@jadran.com", passwordHash = passwordEncoder.encode("damir2026"),
                name = "Дамир", company = "Jadran").also { it.role = AdminRole.BRAND_MANAGER; it.status = AdminUserStatus.ACTIVE },
        )
        bearer = "Bearer " + login().tokens.accessToken
    }

    @Test
    fun `лог + Excel → авто-одобрение и начисление бонуса`() {
        postSale("sale_1", fiscal = "F1", total = 1_000)
        // только лог → ручная модерация, бонус ещё не начислен
        assertEquals("moderation_required", receiptForPending().status.name)
        assertEquals(0L, pharmacistRepository.findById("u_t").get().balance)

        importExcel(listOf(Triple("F1", "p_x", 1_000L)))
        // подтверждён обоими → approved + начисление
        val r = receiptForPending()
        assertEquals("approved", r.status.name)
        assertEquals(true, r.confirmedByLog && r.confirmedByExcel)
        assertEquals(300L, pharmacistRepository.findById("u_t").get().balance)
    }

    @Test
    fun `только лог → moderation_required`() {
        postSale("sale_2", fiscal = "F2", total = 1_000)
        assertEquals("moderation_required", receiptForPending().status.name)
        assertEquals(0L, pharmacistRepository.findById("u_t").get().balance)
    }

    @Test
    fun `только Excel → moderation_required`() {
        importExcel(listOf(Triple("F3", "p_x", 1_000L)))
        val r = receiptForPending()
        assertEquals("moderation_required", r.status.name)
        assertEquals(true, r.confirmedByExcel)
        assertEquals(0L, pharmacistRepository.findById("u_t").get().balance)
    }

    @Test
    fun `расхождение сумм лог vs Excel → flagged, без начисления`() {
        postSale("sale_4", fiscal = "F4", total = 1_000)
        importExcel(listOf(Triple("F4", "p_x", 5_000L))) // сумма не сходится
        assertEquals("flagged", receiptForPending().status.name)
        assertEquals(0L, pharmacistRepository.findById("u_t").get().balance)
    }

    @Test
    fun `повторный pos_sale идемпотентен (один и тот же saleId)`() {
        postSale("sale_5", fiscal = "F5", total = 1_000)
        // второй раз тот же saleId → accepted=false, дубля сверки нет
        mockMvc.perform(
            post("/api/posm/sales").header("X-Posm-Key", POSM_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(saleReq("sale_5", "F5", 1_000))),
        ).andExpect(status().isOk)
        assertEquals(1, posSaleRepository.count().toInt())
    }

    @Test
    fun `import-excel без авторизации → 401`() {
        mockMvc.perform(
            multipart("/api/admin/reconcile/import-excel")
                .file(MockMultipartFile("file", "s.xlsx", "application/octet-stream", buildXlsx(listOf(Triple("F", "p_x", 1L))))),
        ).andExpect(status().isUnauthorized)
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun receiptForPending() =
        receiptRepository.findAll().first { it.pendingBonusId == "pb_t" }

    private fun postSale(saleId: String, fiscal: String, total: Long) {
        mockMvc.perform(
            post("/api/posm/sales").header("X-Posm-Key", POSM_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(saleReq(saleId, fiscal, total))),
        ).andExpect(status().isOk)
    }

    private fun saleReq(saleId: String, fiscal: String, total: Long) = PosSaleRequest(
        saleId = saleId, pharmacistId = "u_t", pharmacyId = "ph_t", fiscalId = fiscal,
        cashier = "Касса 1", totalAmount = total, printedAt = Instant.now(),
        items = listOf(PosSaleItemDto(sku = "p_x", name = "Товар X", qty = 1.0, price = total, total = total)),
    )

    private fun importExcel(rows: List<Triple<String, String, Long>>) {
        mockMvc.perform(
            multipart("/api/admin/reconcile/import-excel")
                .file(MockMultipartFile("file", "sales.xlsx", "application/octet-stream", buildXlsx(rows)))
                .header("Authorization", bearer),
        ).andExpect(status().isOk)
    }

    private fun buildXlsx(rows: List<Triple<String, String, Long>>): ByteArray {
        XSSFWorkbook().use { wb ->
            val sheet = wb.createSheet("sales")
            val header = sheet.createRow(0)
            header.createCell(0).setCellValue("Чек")
            header.createCell(1).setCellValue("Артикул")
            header.createCell(2).setCellValue("Сумма")
            rows.forEachIndexed { i, (fiscal, sku, amount) ->
                val row = sheet.createRow(i + 1)
                row.createCell(0).setCellValue(fiscal)
                row.createCell(1).setCellValue(sku)
                row.createCell(2).setCellValue(amount.toDouble())
            }
            val bos = ByteArrayOutputStream()
            wb.write(bos)
            return bos.toByteArray()
        }
    }

    private fun login(): LoginResponse {
        val res = mockMvc.perform(
            post("/api/admin/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(LoginRequest("damir@jadran.com", "damir2026"))),
        ).andExpect(status().isOk).andReturn()
        return objectMapper.readValue(res.response.contentAsString, LoginResponse::class.java)
    }
}
