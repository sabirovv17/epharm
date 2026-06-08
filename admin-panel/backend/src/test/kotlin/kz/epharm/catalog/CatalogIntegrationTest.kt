package kz.epharm.catalog

import com.fasterxml.jackson.databind.ObjectMapper
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.domain.AdminUserStatus
import kz.epharm.auth.dto.LoginRequest
import kz.epharm.auth.dto.LoginResponse
import kz.epharm.auth.entity.AdminUserEntity
import kz.epharm.auth.repository.AdminUserRepository
import kz.epharm.catalog.dto.CreateProductRequest
import kz.epharm.catalog.dto.UpdateProductRequest
import kz.epharm.catalog.entity.ProductEntity
import kz.epharm.catalog.repository.ProductRepository
import kz.epharm.rules.entity.RuleEntity
import kz.epharm.rules.entity.RuleStatus
import kz.epharm.rules.entity.RuleTrigger
import kz.epharm.rules.entity.RuleType
import kz.epharm.rules.repository.RuleRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Transactional
class CatalogIntegrationTest {

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
        fun dataSourceProps(reg: DynamicPropertyRegistry) {
            reg.add("spring.datasource.url") { postgres.jdbcUrl }
            reg.add("spring.datasource.username") { postgres.username }
            reg.add("spring.datasource.password") { postgres.password }
        }
    }

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var productRepository: ProductRepository
    @Autowired private lateinit var ruleRepository: RuleRepository
    @Autowired private lateinit var adminUserRepository: AdminUserRepository
    @Autowired private lateinit var passwordEncoder: PasswordEncoder

    private lateinit var bearer: String

    @BeforeEach
    fun seed() {
        ruleRepository.deleteAll()
        productRepository.deleteAll()
        adminUserRepository.deleteAll()

        productRepository.saveAll(
            listOf(
                ProductEntity(
                    id = "p_aqm_norm_s", name = "Аквамарис Норм спрей 30мл",
                    brand = "Jadran-Galenski", vendor = "Jadran",
                    mnn = "Морская вода", price = 1480,
                ),
                ProductEntity(
                    id = "p_aql_norm_s", name = "Аквалор Норм спрей 50мл",
                    brand = "Aurena Labs", vendor = "Aurena",
                    mnn = "Морская вода", price = 1620,
                ),
                ProductEntity(
                    id = "p_pinos", name = "Пиносол спрей назальный 10мл",
                    brand = "Jadran-Galenski", vendor = "Jadran",
                    mnn = "Эфирные масла", price = 1820,
                ),
            ),
        )
        adminUserRepository.save(
            AdminUserEntity(
                email = "damir@jadran.com",
                passwordHash = passwordEncoder.encode("damir2026"),
                name = "Дамир",
                company = "Jadran",
            ).also {
                it.role = AdminRole.BRAND_MANAGER
                it.status = AdminUserStatus.ACTIVE
            },
        )
        bearer = "Bearer " + login().tokens.accessToken
    }

    @Test
    fun `GET products returns sorted list — authed`() {
        // Сортировка ASC по name — "Аквалор Норм" идёт перед "Аквамарис Норм"
        // (л перед м в кириллице). Пиносол идёт последним.
        mockMvc.perform(get("/api/admin/catalog/products").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(3))
            .andExpect(jsonPath("$[0].id").value("p_aql_norm_s"))
            .andExpect(jsonPath("$[1].id").value("p_aqm_norm_s"))
            .andExpect(jsonPath("$[2].id").value("p_pinos"))
            .andExpect(jsonPath("$[0].brand").value("Aurena Labs"))
            .andExpect(jsonPath("$[0].mnn").value("Морская вода"))
    }

    @Test
    fun `GET products without Bearer returns 401`() {
        mockMvc.perform(get("/api/admin/catalog/products"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `GET single product by id`() {
        mockMvc.perform(get("/api/admin/catalog/products/p_pinos").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value("p_pinos"))
            .andExpect(jsonPath("$.price").value(1820))
    }

    @Test
    fun `GET product by missing id returns 404`() {
        mockMvc.perform(get("/api/admin/catalog/products/p_unknown").header("Authorization", bearer))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
    }

    @Test
    fun `GET brands aggregates products`() {
        mockMvc.perform(get("/api/admin/catalog/brands").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[?(@.brand=='Jadran-Galenski')].productCount").value(2))
            .andExpect(jsonPath("$[?(@.brand=='Aurena Labs')].productCount").value(1))
    }

    @Test
    fun `GET mnn-groups aggregates products`() {
        mockMvc.perform(get("/api/admin/catalog/mnn-groups").header("Authorization", bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[?(@.mnn=='Морская вода')].productCount").value(2))
            .andExpect(jsonPath("$[?(@.mnn=='Эфирные масла')].productCount").value(1))
    }

    // ── CRUD (Блок 2) ────────────────────────────────────────────────────

    @Test
    fun `POST product creates and appears in list — 201`() {
        val req = CreateProductRequest(
            id = "p_new_drug", name = "Новый препарат 10мл",
            brand = "Test Brand", vendor = "Test", mnn = "Тест-МНН", price = 999,
        )
        mockMvc.perform(
            post("/api/admin/catalog/products")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id").value("p_new_drug"))
            .andExpect(jsonPath("$.price").value(999))

        mockMvc.perform(get("/api/admin/catalog/products").header("Authorization", bearer))
            .andExpect(jsonPath("$.length()").value(4))
    }

    @Test
    fun `POST product with duplicate id returns 409 CONFLICT`() {
        val req = CreateProductRequest(
            id = "p_pinos", name = "Дубликат", brand = "X", vendor = "X", mnn = "X", price = 1,
        )
        mockMvc.perform(
            post("/api/admin/catalog/products")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("CONFLICT"))
    }

    @Test
    fun `POST product with blank name returns 400`() {
        // Bean Validation @NotBlank ловит пустое имя до сервиса.
        val body = """{"id":"p_blank","name":"","brand":"X","vendor":"X","mnn":"X","price":10}"""
        mockMvc.perform(
            post("/api/admin/catalog/products")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
    }

    @Test
    fun `POST product with bad id pattern returns 400`() {
        // id должен быть [a-z0-9_] — пробелы/заглавные отклоняются.
        val body = """{"id":"Bad Id!","name":"X","brand":"X","vendor":"X","mnn":"X","price":10}"""
        mockMvc.perform(
            post("/api/admin/catalog/products")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
    }

    @Test
    fun `POST product with negative price returns 400`() {
        val body = """{"id":"p_neg","name":"X","brand":"X","vendor":"X","mnn":"X","price":-5}"""
        mockMvc.perform(
            post("/api/admin/catalog/products")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
    }

    @Test
    fun `POST product с битым JSON → 400 VALIDATION_FAILED`() {
        // Раньше Spring отдавал дефолтный ответ без машинного кода — теперь GlobalExceptionHandler.
        mockMvc.perform(
            post("/api/admin/catalog/products")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ не json"),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
    }

    @Test
    fun `POST product without Bearer returns 401`() {
        val req = CreateProductRequest(
            id = "p_x", name = "X", brand = "X", vendor = "X", mnn = "X", price = 1,
        )
        mockMvc.perform(
            post("/api/admin/catalog/products")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)),
        )
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `PATCH product updates only provided fields`() {
        val req = UpdateProductRequest(price = 2000, name = "Пиносол обновлённый")
        mockMvc.perform(
            patch("/api/admin/catalog/products/p_pinos")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.price").value(2000))
            .andExpect(jsonPath("$.name").value("Пиносол обновлённый"))
            // brand не передан → остался прежним
            .andExpect(jsonPath("$.brand").value("Jadran-Galenski"))
    }

    @Test
    fun `PATCH missing product returns 404`() {
        val req = UpdateProductRequest(price = 100)
        mockMvc.perform(
            patch("/api/admin/catalog/products/p_unknown")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
    }

    @Test
    fun `DELETE unreferenced product returns 204 and removes it`() {
        mockMvc.perform(delete("/api/admin/catalog/products/p_pinos").header("Authorization", bearer))
            .andExpect(status().isNoContent)

        mockMvc.perform(get("/api/admin/catalog/products/p_pinos").header("Authorization", bearer))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `DELETE product referenced by a rule returns 409 CONFLICT`() {
        // Правило рекомендует p_aqm_norm_s — удалять товар нельзя, осиротит матчер.
        ruleRepository.save(
            RuleEntity(
                id = "r_s_ref01",
                trigger = RuleTrigger(kind = "product", value = "p_aql_norm_s"),
                recommend = "p_aqm_norm_s",
                createdBy = "test",
            ).also {
                it.type = RuleType.substitution
                it.status = RuleStatus.active
            },
        )
        mockMvc.perform(delete("/api/admin/catalog/products/p_aqm_norm_s").header("Authorization", bearer))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("CONFLICT"))

        // товар на месте — удаление не прошло
        mockMvc.perform(get("/api/admin/catalog/products/p_aqm_norm_s").header("Authorization", bearer))
            .andExpect(status().isOk)
    }

    private fun login(): LoginResponse {
        val req = LoginRequest(email = "damir@jadran.com", password = "damir2026")
        val result = mockMvc.perform(
            post("/api/admin/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)),
        ).andExpect(status().isOk).andReturn()
        return objectMapper.readValue(result.response.contentAsString, LoginResponse::class.java)
    }
}
