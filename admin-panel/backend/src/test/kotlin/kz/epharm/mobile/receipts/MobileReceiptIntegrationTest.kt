package kz.epharm.mobile.receipts

import com.fasterxml.jackson.databind.ObjectMapper
import kz.epharm.auth.service.JwtService
import kz.epharm.pharmacists.entity.PharmacistEntity
import kz.epharm.pharmacists.entity.PharmacistStatus
import kz.epharm.pharmacists.repository.PharmacistRepository
import kz.epharm.receipts.entity.ReceiptEntity
import kz.epharm.receipts.entity.ReceiptStatus
import kz.epharm.receipts.repository.ReceiptRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Мобильная отправка/история чеков. Валидация идёт через ReconcileService (тот же пайплайн, что
 * POSM/Excel/админка). pharmacistId берётся из JWT — нельзя загрузить чек за другого.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Transactional
class MobileReceiptIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("epharm_test")
            .withUsername("epharm")
            .withPassword("epharm_test")
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
    @Autowired private lateinit var pharmacistRepository: PharmacistRepository
    @Autowired private lateinit var receiptRepository: ReceiptRepository
    @Autowired private lateinit var jwtService: JwtService

    private lateinit var token: String

    @BeforeEach
    fun seed() {
        receiptRepository.deleteAll()
        pharmacistRepository.deleteAll()
        val rx = pharmacistRepository.save(
            PharmacistEntity(id = "u_rx", name = "Фарм Тестов", iin = "990101000333", phone = "+77005556677")
                .also { it.status = PharmacistStatus.active },
        )
        token = jwtService.issuePharmacistToken(rx.id, rx.name, rx.phone)
    }

    @Test
    fun `POST чек с фото → 201, статус на проверке, чек попадает в историю`() {
        val file = MockMultipartFile("file", "receipt.jpg", "image/jpeg", "fake-photo-bytes".toByteArray())
        mockMvc.perform(
            multipart("/api/mobile/receipts").file(file).header("Authorization", "Bearer $token"),
        )
            .andExpect(status().isCreated)
            // Без матча с pending-бонусом авто-апрува нет → на ручной проверке.
            .andExpect(jsonPath("$.status").value("inReview"))
            .andExpect(jsonPath("$.id").isString)
            .andExpect(jsonPath("$.productName").isString)

        mockMvc.perform(get("/api/mobile/receipts").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
    }

    @Test
    fun `GET история отдаёт только свои чеки`() {
        // Чужой чек — не должен попасть в выдачу.
        receiptRepository.save(
            ReceiptEntity(
                id = "rcp_other",
                pharmacistId = "u_other",
                pharmacistName = "Чужой",
                parsedSku = "p_x",
                parsedAmount = 1000,
            ).also { it.status = ReceiptStatus.pending },
        )
        mockMvc.perform(get("/api/mobile/receipts").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(0))
    }

    @Test
    fun `POST чек с выбранной аптекой → аптека сохранена и видна в DTO`() {
        // Баг-фикс: выбранная в приложении аптека должна попасть в чек, а не теряться
        // (профиль u_rx без аптеки — раньше pharmacyName приходил пустым).
        val file = MockMultipartFile("file", "receipt.jpg", "image/jpeg", "bytes".toByteArray())
        mockMvc.perform(
            multipart("/api/mobile/receipts").file(file)
                .param("pharmacyId", "ph_abc")
                .param("pharmacyName", "Аптека на Сатпаева 5")
                .header("Authorization", "Bearer $token"),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.pharmacyName").value("Аптека на Сатпаева 5"))
    }

    @Test
    fun `POST без фото → 400`() {
        mockMvc.perform(
            multipart("/api/mobile/receipts").header("Authorization", "Bearer $token"),
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `POST без токена → 401`() {
        val file = MockMultipartFile("file", "r.jpg", "image/jpeg", "x".toByteArray())
        mockMvc.perform(multipart("/api/mobile/receipts").file(file)).andExpect(status().isUnauthorized)
    }

    @Test
    fun `GET без токена → 401`() {
        mockMvc.perform(get("/api/mobile/receipts")).andExpect(status().isUnauthorized)
    }
}
