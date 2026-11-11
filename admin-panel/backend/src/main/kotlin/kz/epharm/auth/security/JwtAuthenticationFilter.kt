package kz.epharm.auth.security

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kz.epharm.auth.service.JwtService
import kz.epharm.mobile.auth.security.PharmacistSessionLoader
import kz.epharm.shared.error.ApiErrorResponse
import kz.epharm.shared.error.AppException
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Парсит Authorization: Bearer <jwt> на каждом запросе. Для администратора загружает
 * текущий активный аккаунт и роль из БД; claims не сохраняют отозванные полномочия.
 *
 * Невалидный/отсутствующий токен — просто пропускаем дальше; решение о доступе
 * принимает SecurityFilterChain (authorizeHttpRequests).
 */
@Component
class JwtAuthenticationFilter(
    private val jwtService: JwtService,
    private val adminSessionLoader: AdminSessionLoader,
    private val pharmacistSessionLoader: PharmacistSessionLoader,
    private val objectMapper: ObjectMapper,
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
                    val current = try {
                        pharmacistSessionLoader.load(jwtService.pharmacistIdOf(claims))
                    } catch (ex: AppException) {
                        // Filters run before controller advice. Preserve the account error JSON
                        // contract and stop before any learner or other protected operation.
                        response.status = ex.status.value()
                        response.contentType = MediaType.APPLICATION_JSON_VALUE
                        response.characterEncoding = "UTF-8"
                        response.setHeader("Cache-Control", "no-store")
                        objectMapper.writeValue(response.outputStream, ApiErrorResponse(ex.code, ex.message ?: ex.code.name))
                        return
                    }
                    current?.let { principal ->
                        UsernamePasswordAuthenticationToken(
                            principal,
                            null,
                            listOf(SimpleGrantedAuthority("ROLE_PHARMACIST")),
                        )
                    }
                } else {
                    adminSessionLoader.load(claims.subject, jwtService.adminSessionVersionOf(claims))?.let { principal ->
                        UsernamePasswordAuthenticationToken(
                            principal,
                            null,
                            listOf(SimpleGrantedAuthority("ROLE_${principal.role.name}")),
                        )
                    }
                }
                if (auth != null) {
                    auth.details = WebAuthenticationDetailsSource().buildDetails(request)
                    SecurityContextHolder.getContext().authentication = auth
                }
            }
        }
        filterChain.doFilter(request, response)
    }
}
