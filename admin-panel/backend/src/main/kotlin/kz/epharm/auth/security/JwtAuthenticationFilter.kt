package kz.epharm.auth.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kz.epharm.auth.service.JwtService
import kz.epharm.mobile.auth.security.PharmacistPrincipal
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Парсит Authorization: Bearer <jwt> на каждом запросе. Если токен валиден — кладёт
 * AdminPrincipal в SecurityContext с ролью ROLE_<AdminRole>.
 *
 * Невалидный/отсутствующий токен — просто пропускаем дальше; решение о доступе
 * принимает SecurityFilterChain (authorizeHttpRequests).
 */
@Component
class JwtAuthenticationFilter(
    private val jwtService: JwtService,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val header = request.getHeader("Authorization")
        if (header != null && header.startsWith("Bearer ", ignoreCase = true)) {
            val token = header.substring(7).trim()
            val claims = jwtService.parseAccessToken(token)
            if (claims != null && SecurityContextHolder.getContext().authentication == null) {
                val auth = if (jwtService.isPharmacistToken(claims)) {
                    // Токен фармацевта (мобильное приложение) — PharmacistPrincipal + ROLE_PHARMACIST.
                    val principal = PharmacistPrincipal(
                        pharmacistId = jwtService.pharmacistIdOf(claims),
                        name = claims["name"] as String,
                        phone = claims["phone"] as String,
                    )
                    UsernamePasswordAuthenticationToken(
                        principal,
                        null,
                        listOf(SimpleGrantedAuthority("ROLE_PHARMACIST")),
                    )
                } else {
                    // Токен админа HQ-консоли — AdminPrincipal + ROLE_<AdminRole>.
                    val role = jwtService.roleOf(claims)
                    val principal = AdminPrincipal(
                        userId = jwtService.userIdOf(claims),
                        email = claims["email"] as String,
                        name = claims["name"] as String,
                        role = role,
                        company = claims["company"] as String,
                    )
                    UsernamePasswordAuthenticationToken(
                        principal,
                        null,
                        listOf(SimpleGrantedAuthority("ROLE_${role.name}")),
                    )
                }
                auth.details = WebAuthenticationDetailsSource().buildDetails(request)
                SecurityContextHolder.getContext().authentication = auth
            }
        }
        filterChain.doFilter(request, response)
    }
}
