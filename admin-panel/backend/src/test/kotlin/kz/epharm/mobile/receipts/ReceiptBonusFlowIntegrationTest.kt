package kz.epharm.mobile.receipts

import com.fasterxml.jackson.databind.ObjectMapper
import kz.epharm.auth.service.JwtService
import kz.epharm.pharmacists.entity.PharmacistEntity
import kz.epharm.pharmacists.entity.PharmacistStatus
import kz.epharm.pharmacists.repository.PharmacistRepository
import kz.epharm.receipts.entity.PendingBonusEntity
import kz.epharm.receipts.entity.PendingBonusStatus
import kz.epharm.receipts.repository.PendingBonusRepository
import kz.epharm.receipts.repository.ReceiptRepository
import kz.epharm.receipts.service.ReconcileService
import org.assertj.core.api.Assertions.assertThat
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
 * E2E «чек → бонус» (КТ-4, todo #137): фармацевт грузит чек из мобилки → менеджер одобряет →
 * баланс фармацевта растёт на бонус → мобильные /me и история отражают результат.
 *
 * Доказывает бизнес-обещание «приложение реально даёт бонусы» на уровне API. Цикл уже работает
 * в установленной мобильной сборке (баланс обновляется при входе на Home, история — pull-to-refresh),
 * поэтому пересборка приложения для этого НЕ нужна — это backend-проверка.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Transactional
class ReceiptBonusFlowIntegrationTest {

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
    @Autowired private lateinit var pendingBonusRepository: PendingBonusRepository
    @Autowired private lateinit var receiptRepository: ReceiptRepository
    @Autowired private lateinit var reconcileService: ReconcileService
    @Autowired private lateinit var jwtService: JwtService

    private lateinit var token: String
    private val pharmacistId = "u_e2e"
    private val bonus = 520L

    @BeforeEach
    fun seed() {
        receiptRepository.deleteAll()
        pendingBonusRepository.deleteAll()
        pharmacistRepository.deleteAll()

        // Активный фармацевт без аптеки (pharmacyId=null → в чеке "" — совпадёт с pending "").
        val rx = pharmacistRepository.save(
            PharmacistEntity(id = pharmacistId, name = "Бонус Тестов", iin = "920707300015", phone = "+77006667788")
                .also { it.status = PharmacistStatus.active },
        )
        // Бронь бонуса от POSM-замены (источник №1) — её закроет загруженный чек.
        pendingBonusRepository.save(
            PendingBonusEntity(
                id = "pb_e2e",
                pharmacistId = rx.id,
                pharmacistName = rx.name,
                pharmacyId = "",
                pharmacyName = "",
                sku = "p_e2e",
                productName = "Аквамарис Норм",
                expectedAmount = 1500,
                bonus = bonus,
                ruleId = "r_e2e",
            ).also { it.status = PendingBonusStatus.awaiting_receipt },
        )
        token = jwtService.issuePharmacistToken(rx.id, rx.name, rx.phone)
    }

    @Test
    fun `чек из мобилки → одобрение менеджером → баланс вырос → история confirmed`() {
        // 0) старт: баланс 0.
        assertThat(pharmacistRepository.findById(pharmacistId).get().balance).isEqualTo(0)

        // 1) фармацевт грузит фото чека из приложения.
        val file = MockMultipartFile("file", "receipt.jpg", "image/jpeg", "fake-photo".toByteArray())
        val submit = mockMvc.perform(
            multipart("/api/mobile/receipts").file(file).header("Authorization", "Bearer $token"),
        ).andExpect(status().isCreated).andReturn()
        val receiptId = objectMapper.readTree(submit.response.contentAsByteArray).get("id").asText()

        // 2) менеджер одобряет в админке (ReconcileService.approve — идемпотентно начисляет бонус).
        reconcileService.approve(receiptId, reviewer = "moderator")

        // 3) баланс фармацевта вырос ровно на бонус (и earned_30d).
        val after = pharmacistRepository.findById(pharmacistId).get()
        assertThat(after.balance).isEqualTo(bonus)
        assertThat(after.earned30d).isEqualTo(bonus)

        // 4) мобильное /me отдаёт новый баланс (это видит приложение при refresh).
        mockMvc.perform(get("/api/mobile/me").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.balance").value(bonus.toInt()))

        // 5) история чеков: статус confirmed + начисленный бонус.
        mockMvc.perform(get("/api/mobile/receipts").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].status").value("confirmed"))
            .andExpect(jsonPath("$[0].bonusCredited").value(bonus.toInt()))
    }

    @Test
    fun `повторное одобрение не начисляет бонус дважды (идемпотентность)`() {
        val file = MockMultipartFile("file", "r.jpg", "image/jpeg", "x".toByteArray())
        val submit = mockMvc.perform(
            multipart("/api/mobile/receipts").file(file).header("Authorization", "Bearer $token"),
        ).andExpect(status().isCreated).andReturn()
        val receiptId = objectMapper.readTree(submit.response.contentAsByteArray).get("id").asText()

        reconcileService.approve(receiptId, reviewer = "moderator")
        reconcileService.approve(receiptId, reviewer = "moderator") // повтор

        assertThat(pharmacistRepository.findById(pharmacistId).get().balance).isEqualTo(bonus)
    }
}
