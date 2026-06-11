package kz.epharm.mobile

import kz.epharm.auth.service.JwtService
import kz.epharm.pharmacies.entity.ChainEntity
import kz.epharm.pharmacies.entity.PharmacyEntity
import kz.epharm.pharmacies.entity.PharmacyGroup
import kz.epharm.pharmacies.repository.ChainRepository
import kz.epharm.pharmacies.repository.PharmacyRepository
import kz.epharm.pharmacists.entity.PharmacistEntity
import kz.epharm.pharmacists.entity.PharmacistStatus
import kz.epharm.pharmacists.repository.PharmacistRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Мобильные эндпоинты каталога и аптек.
 *  - /api/mobile/pharmacies — реальные адреса из нашего реестра (только активные, фильтры q/city);
 *  - /api/mobile/catalog/products — прокси Medusa; в тест-профиле Medusa выключен →
 *    пустая страница (деградация без 5xx). Маппинг проверяет MobileCatalogServiceTest.
 *
 * Всё под JWT фармацевта (pharmacistId из токена). Без токена → 401.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Transactional
class MobilePharmacyCatalogIntegrationTest {

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

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var chainRepository: ChainRepository
    @Autowired private lateinit var pharmacyRepository: PharmacyRepository
    @Autowired private lateinit var pharmacistRepository: PharmacistRepository
    @Autowired private lateinit var jwtService: JwtService

    private lateinit var token: String

    @BeforeEach
    fun seed() {
        pharmacyRepository.deleteAll()
        chainRepository.deleteAll()
        pharmacistRepository.deleteAll()

        chainRepository.save(
            ChainEntity(id = "ch_eu", name = "Europharma", color = "#16C97A", points = 10)
                .also { it.group = PharmacyGroup.pilot },
        )
        // Две активные (разные города) + одна закрытая — проверяем фильтр active.
        pharmacyRepository.save(
            PharmacyEntity(id = "ph_alm", name = "Аптека Алматы Достык", chainId = "ch_eu", chainName = "Europharma",
                city = "Алматы", district = "Медеуский", addr = "пр. Достык, 132")
                .also { it.group = PharmacyGroup.pilot; it.active = true },
        )
        pharmacyRepository.save(
            PharmacyEntity(id = "ph_ast", name = "Аптека Астана Кенесары", chainId = "ch_eu", chainName = "Europharma",
                city = "Астана", district = "Есильский", addr = "пр. Кенесары, 10")
                .also { it.group = PharmacyGroup.pilot; it.active = true },
        )
        pharmacyRepository.save(
            PharmacyEntity(id = "ph_closed", name = "Аптека Алматы Закрытая", chainId = "ch_eu", chainName = "Europharma",
                city = "Алматы", district = "", addr = "ул. Закрытая, 1")
                .also { it.group = PharmacyGroup.pilot; it.active = false },
        )

        val rx = pharmacistRepository.save(
            PharmacistEntity(id = "u_rx", name = "Фарм Тестов", iin = "850615400016", phone = "+77005556677")
                .also { it.status = PharmacistStatus.active },
        )
        token = jwtService.issuePharmacistToken(rx.id, rx.name, rx.phone)
    }

    private fun bearer() = "Bearer $token"

    @Test
    fun `аптеки — только активные, с адресом и цветом сети, по алфавиту`() {
        mockMvc.perform(get("/api/mobile/pharmacies").header("Authorization", bearer()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2)) // закрытая исключена
            .andExpect(jsonPath("$[0].id").value("ph_alm"))
            .andExpect(jsonPath("$[0].addr").value("пр. Достык, 132"))
            .andExpect(jsonPath("$[0].chain").value("Europharma"))
            .andExpect(jsonPath("$[0].chainColor").value("#16C97A"))
            .andExpect(jsonPath("$[1].city").value("Астана"))
    }

    @Test
    fun `фильтр по городу`() {
        mockMvc.perform(get("/api/mobile/pharmacies").param("city", "Алматы").header("Authorization", bearer()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value("ph_alm"))
    }

    @Test
    fun `поиск по подстроке адреса`() {
        mockMvc.perform(get("/api/mobile/pharmacies").param("q", "кенесары").header("Authorization", bearer()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value("ph_ast"))
    }

    @Test
    fun `аптеки без токена → 401`() {
        mockMvc.perform(get("/api/mobile/pharmacies")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `каталог при выключенном Medusa → 200 и пустая страница`() {
        mockMvc.perform(get("/api/mobile/catalog/products").header("Authorization", bearer()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.total").value(0))
            .andExpect(jsonPath("$.items.length()").value(0))
            .andExpect(jsonPath("$.limit").value(24))
            .andExpect(jsonPath("$.offset").value(0))
    }

    @Test
    fun `каталог ПУБЛИЧНЫЙ — без токена тоже 200 (лента на главной до логина)`() {
        // /api/mobile/catalog/** → permitAll: товары видны без входа (фармацевт смотрит
        // каталог до логина). При выключенном Medusa — пустая страница, но именно 200.
        mockMvc.perform(get("/api/mobile/catalog/products"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.total").value(0))
    }
}
