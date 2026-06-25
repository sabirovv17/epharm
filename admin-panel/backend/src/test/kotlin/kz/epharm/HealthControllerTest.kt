package kz.epharm

import kz.epharm.auth.security.JwtAuthenticationFilter
import kz.epharm.auth.service.JwtService
import kz.epharm.shared.ApiAccessDeniedHandler
import kz.epharm.shared.ApiAuthenticationEntryPoint
import kz.epharm.shared.HealthController
import kz.epharm.shared.SecurityConfig
import kz.epharm.shared.error.GlobalExceptionHandler
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Slice-test для HealthController. Подгружаем только web-слой + security + JWT-фильтр,
 * без БД и JPA — health должен работать всегда (актуатор-стиль endpoint).
 */
@WebMvcTest(controllers = [HealthController::class])
@Import(
    SecurityConfig::class,
    JwtAuthenticationFilter::class,
    JwtService::class,
    ApiAuthenticationEntryPoint::class,
    ApiAccessDeniedHandler::class,
    GlobalExceptionHandler::class,
)
@TestPropertySource(properties = [
    "app.jwt.secret=test-secret-key-of-at-least-32-bytes-for-hs256-tests-only-please",
    "app.jwt.access-ttl-minutes=15",
    "app.cors.allowed-origins=http://localhost:5173",
])
class HealthControllerTest(@Autowired val mvc: MockMvc) {

    @Test
    fun `health endpoint returns ok without auth`() {
        mvc.perform(get("/api/health"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("ok"))
            .andExpect(jsonPath("$.service").value("epharm-backend"))
    }
}
