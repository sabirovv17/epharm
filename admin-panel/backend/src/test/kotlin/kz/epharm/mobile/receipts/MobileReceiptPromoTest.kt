package kz.epharm.mobile.receipts

import com.fasterxml.jackson.databind.ObjectMapper
import kz.epharm.auth.service.JwtService
import kz.epharm.pharmacists.entity.PharmacistEntity
import kz.epharm.pharmacists.entity.PharmacistStatus
import kz.epharm.pharmacists.repository.PharmacistRepository
import kz.epharm.receipts.repository.PendingBonusRepository
import kz.epharm.receipts.repository.ReceiptRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Канал «акция→чек» (п.2): фармацевт в приложении заявляет выбранные акции при загрузке чека —
 * POST /api/mobile/receipts с параметром `promoIds` (CSV pr_*). Проверяем, что заявка
 * сохраняется в receipts.claimed_promo_ids (контекст для модератора), нормализуется
 * (trim/дедуп/выкинуть пустые) и не доверяется как источник начисления.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Transactional
class MobileReceiptPromoTest {

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
    @Autowired private lateinit var pharmacistRepository: PharmacistRepository
    @Autowired private lateinit var receiptRepository: ReceiptRepository
    @Autowired private lateinit var pendingBonusRepository: PendingBonusRepository
    @Autowired private lateinit var jwtService: JwtService

    private lateinit var token: String
    private val mapper = ObjectMapper()

    @BeforeEach
    fun seed() {
        receiptRepository.deleteAll()
        pendingBonusRepository.deleteAll()
        pharmacistRepository.deleteAll()
        val rx = pharmacistRepository.save(
            PharmacistEntity(id = "u_promo", name = "Фарм Промо", iin = "850615400016", phone = "+77005556677")
                .also { it.status = PharmacistStatus.active },
        )
        token = jwtService.issuePharmacistToken(rx.id, rx.name, rx.phone)
    }

    private fun submitWithPromoIds(promoIds: String?): String {
        val file = MockMultipartFile("file", "receipt.jpg", "image/jpeg", "fake-photo".toByteArray())
        val builder = multipart("/api/mobile/receipts").file(file).header("Authorization", "Bearer $token")
        if (promoIds != null) builder.param("promoIds", promoIds)
        val response = mockMvc.perform(builder)
            .andExpect(status().isCreated)
            .andReturn().response.contentAsString
        return mapper.readTree(response).get("id").asText()
    }

    @Test
    fun `POST чек с promoIds сохраняет claimed_promo_ids на чеке`() {
        val id = submitWithPromoIds("pr_1,pr_2")
        val saved = receiptRepository.findById(id).orElseThrow()
        assertEquals("pr_1,pr_2", saved.claimedPromoIds)
    }

    @Test
    fun `promoIds нормализуется - trim, выкидывание пустых, дедуп с сохранением порядка`() {
        val id = submitWithPromoIds(" pr_1 , , pr_2 , pr_1 ")
        val saved = receiptRepository.findById(id).orElseThrow()
        assertEquals("pr_1,pr_2", saved.claimedPromoIds)
    }

    @Test
    fun `POST чек без promoIds — claimed_promo_ids остаётся null`() {
        val id = submitWithPromoIds(null)
        val saved = receiptRepository.findById(id).orElseThrow()
        assertNull(saved.claimedPromoIds)
    }

    @Test
    fun `POST чек с пустым promoIds (только запятые) — claimed_promo_ids null`() {
        val id = submitWithPromoIds(" , , ")
        val saved = receiptRepository.findById(id).orElseThrow()
        assertNull(saved.claimedPromoIds)
    }
}
