package kz.epharm.shared.error

import org.springframework.http.HttpStatus

/**
 * Доменное исключение бизнес-слоя. Содержит ErrorCode для машинной идентификации
 * на фронте + HTTP status.
 */
class AppException(
    val code: ErrorCode,
    message: String,
    val status: HttpStatus = HttpStatus.BAD_REQUEST,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Машинные коды ошибок. Фронт может switch'ить по ним для локализации/UX.
 */
enum class ErrorCode {
    INVALID_CREDENTIALS,
    INVALID_REFRESH_TOKEN,
    USER_NOT_FOUND,
    USER_NOT_ACTIVE,
    VALIDATION_FAILED,
    UNAUTHORIZED,
    FORBIDDEN,
    NOT_FOUND,
    CONFLICT,
    INTERNAL,
}
