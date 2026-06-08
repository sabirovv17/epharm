package kz.epharm.shared.error

import java.time.Instant

/**
 * Унифицированный JSON для ошибочных ответов. Не отдаём stack-trace на клиента.
 */
data class ApiErrorResponse(
    val code: ErrorCode,
    val message: String,
    val timestamp: Instant = Instant.now(),
    val fields: Map<String, String>? = null,
)
