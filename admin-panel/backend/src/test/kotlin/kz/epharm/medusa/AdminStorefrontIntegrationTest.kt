package kz.epharm.medusa

import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.domain.AdminUserStatus
import kz.epharm.auth.entity.AdminUserEntity
import kz.epharm.auth.repository.AdminUserRepository
import kz.epharm.auth.service.JwtService
import kz.epharm.pharmacists.entity.PharmacistEntity
import kz.epharm.pharmacists.entity.PharmacistStatus
import kz.epharm.pharmacists.repository.PharmacistRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.crypto.password.PasswordEncoder
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
 * Витрина каталога в админке (`/api/admin/storefront/products`). Только под admin-JWT.
 * В тест-профиле Medusa выключен → пустая страница (деградация без 5xx). Доступ
 * фармацевта запрещён (admin-only).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Transactional
class AdminStorefrontIntegrationTest {

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
    @Autowired private lateinit var jwtService: JwtService
    @Autowired private lateinit var adminUserRepository: AdminUserRepository
    @Autowired private lateinit var pharmacistRepository: PharmacistRepository
    @Autowired private lateinit var passwordEncoder: PasswordEncoder

    private lateinit var adminToken: String
    private lateinit var pharmacistToken: String

    @BeforeEach
    fun seed() {
        adminUserRepository.deleteAll()
        pharmacistRepository.deleteAll()

        val admin = adminUserRepository.save(
            AdminUserEntity(email = "hq@inkar.kz", passwordHash = passwordEncoder.encode("x"),
                name = "HQ", company = "Inkar")
                .also { it.role = AdminRole.HQ_HEAD; it.status = AdminUserStatus.ACTIVE },
        )
        adminToken = "Bearer " + jwtService.issueAccessToken(admin)

        val rx = pharmacistRepository.save(
            PharmacistEntity(id = "u_rx", name = "Фарм", iin = "850615400016", phone = "+77005556677")
                .also { it.status = PharmacistStatus.active },
        )
        pharmacistToken = "Bearer " + jwtService.issuePharmacistToken(rx.id, rx.name, rx.phone)
    }

    @Test
    fun `админ видит витрину — 200 и пустая страница при выключенном Medusa`() {
        mockMvc.perform(get("/api/admin/storefront/products").header("Authorization", adminToken))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.total").value(0))
            .andExpect(jsonPath("$.items.length()").value(0))
    }

    @Test
    fun `витрина без токена → 401`() {
        mockMvc.perform(get("/api/admin/storefront/products")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `витрина по токену фармацевта → 401 (admin-only)`() {
        mockMvc.perform(get("/api/admin/storefront/products").header("Authorization", pharmacistToken))
            .andExpect(status().isUnauthorized)
    }
}
