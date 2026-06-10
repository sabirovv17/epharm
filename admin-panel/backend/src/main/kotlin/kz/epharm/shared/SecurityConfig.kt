package kz.epharm.shared

import kz.epharm.auth.security.JwtAuthenticationFilter
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.http.HttpStatus
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

// SecurityFilterChain для HQ-консоли + мобильного API + POSM.
//
// Публичные эндпоинты:
//   GET /api/health + actuator (health/info) + Swagger (в prod Swagger выключается профилем)
//   POST /api/admin/auth/{login,refresh} — выдача/обновление admin-токенов
//   POST /api/mobile/auth/{sms/request,sms/verify,register,refresh} — вход фармацевта
//   /api/posm/** — касса (Module 2) аутентифицируется device-key, не JWT
//
// Разграничение по authority (КРИТИЧНО — закрывает эскалацию pharmacist→admin):
//   /api/admin/**  → только админ-роли (HQ_HEAD/CATEGORY_LEAD/BRAND_MANAGER/FINANCE_REVIEWER)
//   /api/mobile/** → только ROLE_PHARMACIST
//   /actuator/**   → только админ-роли (метрики/prometheus не наружу)
// Тонкая сегрегация (напр. финансовый approve → FINANCE_REVIEWER) — через @PreAuthorize
// (@EnableMethodSecurity включён ниже).
@Configuration
@EnableMethodSecurity
class SecurityConfig(
    private val jwtAuthenticationFilter: JwtAuthenticationFilter,
    @Value("\${app.cors.allowed-origins:http://localhost:5173}") private val allowedOriginsRaw: String,
) {

    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .cors { it.configurationSource(corsConfigurationSource()) }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers(
                        "/api/health",
                        "/actuator/health",
                        "/actuator/info",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html",
                        "/api/admin/auth/login",
                        "/api/admin/auth/refresh",
                        // Мобильное приложение фармацевта: вход по OTP до выдачи JWT.
                        // /logout и /me требуют токен → НЕ в permitAll.
                        "/api/mobile/auth/sms/request",
                        "/api/mobile/auth/sms/verify",
                        "/api/mobile/auth/register",
                        "/api/mobile/auth/refresh",
                        // dev-only reset (DevController существует только в profile=dev;
                        // в prod бина нет → путь отдаёт 404, permitAll безвреден).
                        "/api/admin/dev/**",
                        // POSM-клиент кассы (Module 2): аутентификация не JWT, а device-key
                        // (заголовок X-Posm-Key), проверяется в PosmController. JWT-фильтр пропускает.
                        "/api/posm/**",
                    ).permitAll()
                    // Метрики/prometheus — только для админ-консоли, не наружу.
                    .requestMatchers("/actuator/**").hasAnyRole(*ADMIN_ROLES)
                    // HQ-консоль — строго админ-роли. Мобильный pharmacist-токен → 403.
                    .requestMatchers("/api/admin/**").hasAnyRole(*ADMIN_ROLES)
                    // Мобильное приложение — строго роль фармацевта. Admin-токен сюда → 403.
                    .requestMatchers("/api/mobile/**").hasRole("PHARMACIST")
                    .anyRequest().authenticated()
            }
            // Неаутентифицированный запрос (нет/истёк/невалиден токен) → 401, НЕ 403.
            // Это критично для фронта: axios-interceptor рефрешит токен только на 401.
            // Дефолтный Spring entry point отдавал 403 → протухший access-токен (TTL 15м)
            // ронял каждую секцию в «нет доступа» без авто-refresh. 403 оставляем для
            // будущих ролевых запретов (@PreAuthorize → AccessDeniedHandler).
            .exceptionHandling { it.authenticationEntryPoint(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)) }
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter::class.java)

        return http.build()
    }

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    private fun corsConfigurationSource(): UrlBasedCorsConfigurationSource {
        val cfg = CorsConfiguration().apply {
            allowedOrigins = allowedOriginsRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            allowedHeaders = listOf("*")
            exposedHeaders = listOf("Authorization")
            allowCredentials = true
            maxAge = 3600L
        }
        val source = UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/**", cfg)
        return source
    }

    companion object {
        // Роли HQ-консоли (без префикса ROLE_ — Spring добавляет его сам в hasAnyRole).
        // Должны соответствовать enum AdminRole и CHECK-constraint в V002__auth.sql.
        private val ADMIN_ROLES = arrayOf("HQ_HEAD", "CATEGORY_LEAD", "BRAND_MANAGER", "FINANCE_REVIEWER")
    }
}
