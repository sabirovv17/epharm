package kz.epharm.shared

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kz.epharm.shared.error.ApiErrorResponse
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.MediaType
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets

/**
 * 401 для неаутентифицированного запроса (нет/истёк/невалиден токен) с тем же JSON-телом
 * `ApiErrorResponse` {code,message}, что отдаёт [kz.epharm.shared.error.GlobalExceptionHandler].
 *
 * Зачем: дефолтный Spring `HttpStatusEntryPoint` пишет 401 с ПУСТЫМ телом. Весь остальной API
 * (бизнес-ошибки, валидация, 404/409/500) отдаёт JSON {code,message}. Мобильный `_decodeList`
 * на пустом теле деградировал в дефолтную «Ошибка сервера» (экран «Мои чеки»). Контракт ошибок
 * должен быть единым — security обязан тоже отдавать JSON.
 *
 * Статус 401 (а не 403) критичен для фронта: axios-interceptor рефрешит токен только на 401,
 * а мобилка по `code` отличает «нужно перелогиниться» от реальной ошибки сервера.
 */
@Component
class ApiAuthenticationEntryPoint(
    private val objectMapper: ObjectMapper,
) : AuthenticationEntryPoint {

    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException,
    ) {
        response.status = HttpServletResponse.SC_UNAUTHORIZED
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = StandardCharsets.UTF_8.name()
        objectMapper.writeValue(
            response.outputStream,
            ApiErrorResponse(code = ErrorCode.UNAUTHORIZED, message = "Не авторизованы"),
        )
    }
}
