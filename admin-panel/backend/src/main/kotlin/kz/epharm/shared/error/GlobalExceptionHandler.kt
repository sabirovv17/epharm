package kz.epharm.shared.error

import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.ErrorResponseException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

/**
 * Единая точка превращения исключений в JSON `ApiErrorResponse` с машинным ErrorCode.
 * Контракт кодов зеркалится фронтом (`api-types.ts: ApiErrorCode`, `describeError.ts`).
 *
 * Принципы:
 *  - Клиент НИКОГДА не получает stack-trace (утечка внутренностей / безопасность).
 *  - Каждая ветка → ApiErrorResponse с кодом, который фронт умеет переводить.
 *  - 4xx (ожидаемые ошибки клиента) логируются на DEBUG, 5xx (наши баги) — на ERROR.
 *
 * Порядок резолва Spring: ExceptionHandlerExceptionResolver (этот advice) идёт ПЕРВЫМ,
 * поэтому общий `Exception`-handler мог бы перехватить фреймворк-исключения 4xx.
 * В Spring 6 все они — наследники `ErrorResponseException` (обработан отдельно ниже),
 * так что catch-all безопасно отдаёт 500 только для действительно непредвиденного.
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    /** Доменные ошибки бизнес-слоя — статус и код задаёт сам AppException. */
    @ExceptionHandler(AppException::class)
    fun handleApp(ex: AppException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(ex.status).body(
            ApiErrorResponse(code = ex.code, message = ex.message ?: ex.code.name),
        )

    /** @Valid на DTO — собираем пофайловые ошибки полей. */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException): ResponseEntity<ApiErrorResponse> {
        val fields = ex.bindingResult.fieldErrors.associate {
            it.field to (it.defaultMessage ?: "invalid")
        }
        return badRequest("Проверьте корректность данных", fields)
    }

    /** Тело запроса нечитаемо / пустое / битый JSON. Раньше отдавался Spring-дефолт без кода. */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleNotReadable(ex: HttpMessageNotReadableException): ResponseEntity<ApiErrorResponse> =
        badRequest("Тело запроса отсутствует или содержит некорректный JSON")

    /** Несовпадение типа path/query-параметра (напр. ?status=bogus для enum). */
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(ex: MethodArgumentTypeMismatchException): ResponseEntity<ApiErrorResponse> =
        badRequest("Параметр '${ex.name}' имеет недопустимое значение '${ex.value}'")

    /** Отсутствует обязательный query-параметр. */
    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun handleMissingParam(ex: MissingServletRequestParameterException): ResponseEntity<ApiErrorResponse> =
        badRequest("Отсутствует обязательный параметр '${ex.parameterName}'")

    /** Нарушение целостности БД (unique / FK) — если бизнес-проверка не успела. */
    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleDataIntegrity(ex: DataIntegrityViolationException): ResponseEntity<ApiErrorResponse> {
        log.warn("DataIntegrityViolation: {}", ex.mostSpecificCause.message)
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
            ApiErrorResponse(
                code = ErrorCode.CONFLICT,
                message = "Конфликт целостности данных — запись связана или уже существует",
            ),
        )
    }

    /** Method-level @PreAuthorize denial occurs after the security filter chain. */
    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDenied(ex: AccessDeniedException): ResponseEntity<ApiErrorResponse> {
        log.debug("Method authorization denied: {}", ex.message)
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
            ApiErrorResponse(code = ErrorCode.FORBIDDEN, message = "Недостаточно прав"),
        )
    }

    /**
     * Все стандартные Spring-MVC исключения (404 NoResourceFound, 405 MethodNotSupported,
     * 415 MediaTypeNotSupported, ResponseStatusException, …) — единый супертип в Spring 6.
     * Сохраняем их HTTP-статус, маппим в наш код по семейству.
     */
    @ExceptionHandler(ErrorResponseException::class)
    fun handleErrorResponse(ex: ErrorResponseException): ResponseEntity<ApiErrorResponse> {
        val status = ex.statusCode
        val code = if (status == HttpStatus.NOT_FOUND) ErrorCode.NOT_FOUND else ErrorCode.VALIDATION_FAILED
        log.debug("ErrorResponseException {}: {}", status, ex.message)
        return ResponseEntity.status(status).body(
            ApiErrorResponse(code = code, message = "Запрос не может быть обработан (${status.value()})"),
        )
    }

    /** Catch-all: непредвиденная ошибка → 500. Логируем со стеком, клиенту — только код. */
    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception): ResponseEntity<ApiErrorResponse> {
        log.error("Необработанное исключение", ex)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
            ApiErrorResponse(code = ErrorCode.INTERNAL, message = "Внутренняя ошибка сервера"),
        )
    }

    private fun badRequest(
        message: String,
        fields: Map<String, String>? = null,
    ): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ApiErrorResponse(code = ErrorCode.VALIDATION_FAILED, message = message, fields = fields),
        )
}
