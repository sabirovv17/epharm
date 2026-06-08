package kz.epharm.finance

import kz.epharm.finance.repository.PayoutBatchRepository
import kz.epharm.finance.repository.PayoutItemRepository
import kz.epharm.finance.service.PayoutService
import kz.epharm.pharmacies.entity.ChainEntity
import kz.epharm.pharmacies.entity.PharmacyEntity
import kz.epharm.pharmacies.entity.PharmacyGroup
import kz.epharm.pharmacies.repository.ChainRepository
import kz.epharm.pharmacies.repository.PharmacyRepository
import kz.epharm.pharmacists.entity.PharmacistEntity
import kz.epharm.pharmacists.repository.PharmacistRepository
import kz.epharm.receipts.entity.PendingBonusEntity
import kz.epharm.receipts.entity.PendingBonusStatus
import kz.epharm.receipts.repository.PendingBonusRepository
import kz.epharm.receipts.service.PendingBonusService
import kz.epharm.shared.error.AppException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Duration
import java.time.Instant

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Transactional
class PayoutGenerateIntegrationTest {

    companion object {
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

    @Autowired private lateinit var payoutService: PayoutService
    @Autowired private lateinit var pendingBonusService: PendingBonusService
    @Autowired private lateinit var pharmacistRepository: PharmacistRepository
    @Autowired private lateinit var pharmacyRepository: PharmacyRepository
    @Autowired private lateinit var chainRepository: ChainRepository
    @Autowired private lateinit var batchRepository: PayoutBatchRepository
    @Autowired private lateinit var itemRepository: PayoutItemRepository
    @Autowired private lateinit var pendingBonusRepository: PendingBonusRepository

    @BeforeEach
    fun seed() {
        itemRepository.deleteAll()
        batchRepository.deleteAll()
        pendingBonusRepository.deleteAll()
        pharmacistRepository.deleteAll()
        pharmacyRepository.deleteAll()
        chainRepository.deleteAll()

        chainRepository.save(
            ChainEntity(id = "ch_t", name = "Сеть Т", color = "#16C97A", points = 10)
                .also { it.group = PharmacyGroup.pilot },
        )
        pharmacyRepository.save(
            PharmacyEntity(id = "ph_t", name = "Аптека Т", chainId = "ch_t", chainName = "Сеть Т",
                city = "Алматы", district = "", addr = "").also { it.group = PharmacyGroup.pilot },
        )
    }

    private fun pharmacist(id: String, balance: Long) = PharmacistEntity(
        id = id, name = "Фарм $id", iin = "990101300${id.takeLast(3).padStart(3, '0')}",
        phone = "+7700${id.hashCode().toUInt() % 10000000u}", pharmacyId = "ph_t",
        pharmacyName = "Аптека Т", city = "Алматы", balance = balance, earned30d = balance,
    )

    @Test
    fun `generateBatch собирает балансы в батч и обнуляет их`() {
        pharmacistRepository.save(pharmacist("u_a", 300))
        pharmacistRepository.save(pharmacist("u_b", 200))
        pharmacistRepository.save(pharmacist("u_zero", 0)) // без баланса — не попадёт

        val batch = payoutService.generateBatch("Тест 2026")

        assertEquals(500L, batch.amount)
        assertEquals(2, batch.pharmacists)
        assertEquals(2, batch.items)
        // балансы сняты в батч
        assertEquals(0L, pharmacistRepository.findById("u_a").get().balance)
        assertEquals(0L, pharmacistRepository.findById("u_b").get().balance)
        // позиции батча созданы
        assertEquals(2, itemRepository.findAllByBatchIdOrderByAmountDesc(batch.id).size)
    }

    @Test
    fun `generateBatch без начисленных бонусов → ошибка`() {
        pharmacistRepository.save(pharmacist("u_zero", 0))
        assertThrows(AppException::class.java) { payoutService.generateBatch("Тест 2026") }
    }

    @Test
    fun `expireStale протухает зависшие pending-бонусы, свежие не трогает`() {
        // старый (30 дней назад) и свежий (сейчас)
        pendingBonusRepository.save(
            PendingBonusEntity(id = "pb_old", pharmacistId = "u_a", pharmacistName = "A",
                pharmacyId = "ph_t", pharmacyName = "Аптека Т", sku = "p_x", productName = "X",
                expectedAmount = 1000, bonus = 300,
                createdAt = Instant.now().minus(Duration.ofDays(30))),
        )
        pendingBonusRepository.save(
            PendingBonusEntity(id = "pb_new", pharmacistId = "u_a", pharmacistName = "A",
                pharmacyId = "ph_t", pharmacyName = "Аптека Т", sku = "p_y", productName = "Y",
                expectedAmount = 1000, bonus = 300, createdAt = Instant.now()),
        )

        val expired = pendingBonusService.expireStale(Duration.ofDays(14))

        assertEquals(1, expired)
        assertEquals(PendingBonusStatus.expired, pendingBonusRepository.findById("pb_old").get().status)
        assertEquals(PendingBonusStatus.awaiting_receipt, pendingBonusRepository.findById("pb_new").get().status)
    }
}
