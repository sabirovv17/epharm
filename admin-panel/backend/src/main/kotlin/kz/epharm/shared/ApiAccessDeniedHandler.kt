package kz.epharm.shared

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kz.epharm.shared.error.ApiErrorResponse
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets

/**
 * 403 для аутентифицированного, но не обладающего нужной ролью запроса (напр. pharmacist-токен
 * на admin-эндпоинт или admin-токен на mobile-эндпоинт). Отдаёт тот же JSON `ApiErrorResponse`
 * {code,message}, что и весь остальной API — единый контракт ошибок (см. [ApiAuthenticationEntryPoint]).
 *
 * Без этого хендлера Spring пишет 403 с пустым телом, и клиент (мобилка/админка) не может
 * отличить «нет доступа» от «ошибка сервера» — деградирует в дефолтную «Ошибка сервера».
 */
@Component
class ApiAccessDeniedHandler(
    private val objectMapper: ObjectMapper,
) : AccessDeniedHandler {

    override fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        accessDeniedException: AccessDeniedException,
    ) {
        response.status = HttpServletResponse.SC_FORBIDDEN
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = StandardCharsets.UTF_8.name()
        objectMapper.writeValue(
            response.outputStream,
            ApiErrorResponse(code = ErrorCode.FORBIDDEN, message = "Доступ запрещён"),
        )
    }
}
