package kz.epharm.mobile.auth.service

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import kz.epharm.shared.PhoneUtil
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException

/**
 * OTP через Daribar gateway из `swag.json`:
 *
 * - POST `/api/v2/sms` с `{phone, sms_type: "auth"}` генерирует и отправляет код;
 * - POST `/api/v2/auth` с `{phone, validation_code}` проверяет код.
 *
 * Gateway-токены намеренно не сохраняются: после подтверждения телефона ePharm выпускает свою
 * JWT-пару и остаётся единственным backend мобильного приложения.
 */
class DaribarOtpProvider(
    private val rest: RestClient,
    private val objectMapper: ObjectMapper,
) : OtpProvider {
    override val id: String = "daribar"
    override val verificationMode: OtpVerificationMode = OtpVerificationMode.EXTERNAL

    private val log = LoggerFactory.getLogger(DaribarOtpProvider::class.java)

    override fun requestOtp(phone: String, localCode: String?) {
        check(localCode == null) { "Daribar generates OTP itself; local code must not be supplied" }
        val digits = gatewayPhone(phone)
        val response = try {
            rest.post()
                .uri("/api/v2/sms")
                .contentType(MediaType.APPLICATION_JSON)
                .body(SmsRequest(phone = digits))
                .retrieve()
                .body(GatewayResponse::class.java)
        } catch (e: Exception) {
            log.warn("Daribar OTP: отправка на {} не удалась: {}", PhoneUtil.mask(phone), safeFailure(e))
            throw smsFailure(e)
        }

        if (response?.status != "success") {
            log.warn(
                "Daribar OTP: отправка на {} отклонена (providerCode={}, traceId={})",
                PhoneUtil.mask(phone),
                response?.code,
                response?.errorTraceID,
            )
            throw smsFailure()
        }
        log.info("Daribar OTP: SMS запрошена для {} (traceId={})", PhoneUtil.mask(phone), response.errorTraceID)
    }

    override fun verifyOtp(phone: String, code: String): OtpVerificationResult {
        val digits = gatewayPhone(phone)
        val response = try {
            rest.post()
                .uri("/api/v2/auth")
                .contentType(MediaType.APPLICATION_JSON)
                .body(AuthRequest(phone = digits, validationCode = code))
                .retrieve()
                .body(GatewayResponse::class.java)
        } catch (e: RestClientResponseException) {
            val providerResponse = parseErrorResponse(e)
            if (e.statusCode == HttpStatus.BAD_REQUEST || e.statusCode == HttpStatus.UNAUTHORIZED) {
                return classifyRejection(providerResponse?.error).also {
                    log.info(
                        "Daribar OTP: код для {} отклонён (result={}, providerCode={}, traceId={})",
                        PhoneUtil.mask(phone),
                        it,
                        providerResponse?.code,
                        providerResponse?.errorTraceID,
                    )
                }
            }
            throw verificationUnavailable(phone, e, providerResponse?.errorTraceID)
        } catch (e: Exception) {
            throw verificationUnavailable(phone, e, null)
        }

        if (response?.status == "success" && !response.result?.accessToken.isNullOrBlank()) {
            log.info("Daribar OTP: номер {} подтверждён (traceId={})", PhoneUtil.mask(phone), response.errorTraceID)
            return OtpVerificationResult.VERIFIED
        }
        if (response?.status == "error") {
            return classifyRejection(response.error)
        }

        throw verificationUnavailable(phone, IllegalStateException("Malformed gateway response"), response?.errorTraceID)
    }

    private fun gatewayPhone(phone: String): String {
        val digits = phone.filter(Char::isDigit)
        if (digits.length != 11 || !digits.startsWith('7')) {
            throw AppException(ErrorCode.VALIDATION_FAILED, "Некорректный номер телефона", HttpStatus.BAD_REQUEST)
        }
        return digits
    }

    private fun classifyRejection(error: String?): OtpVerificationResult =
        if (error.orEmpty().contains("expired", ignoreCase = true)) {
            OtpVerificationResult.EXPIRED
        } else {
            OtpVerificationResult.INVALID
        }

    private fun parseErrorResponse(e: RestClientResponseException): GatewayResponse? =
        runCatching { objectMapper.readValue(e.responseBodyAsByteArray, GatewayResponse::class.java) }.getOrNull()

    private fun smsFailure(cause: Throwable? = null): AppException = AppException(
        ErrorCode.SMS_SEND_FAILED,
        "Не удалось отправить SMS — попробуйте ещё раз через минуту",
        HttpStatus.BAD_GATEWAY,
        cause,
    )

    private fun verificationUnavailable(phone: String, cause: Throwable, traceId: String?): AppException {
        log.warn(
            "Daribar OTP: проверка для {} недоступна (traceId={}): {}",
            PhoneUtil.mask(phone),
            traceId,
            safeFailure(cause),
        )
        return AppException(
            ErrorCode.OTP_PROVIDER_UNAVAILABLE,
            "Сервис подтверждения временно недоступен — попробуйте ещё раз",
            HttpStatus.BAD_GATEWAY,
            cause,
        )
    }

    private fun safeFailure(e: Throwable): String = when (e) {
        // RestClient may include a provider response body in the exception message. Do not copy
        // arbitrary gateway data into our logs: HTTP status and errorTraceID are sufficient.
        is RestClientResponseException -> "${e::class.simpleName} (HTTP ${e.statusCode.value()})"
        else -> e::class.simpleName ?: "request failure"
    }

    data class SmsRequest(
        val phone: String,
        @com.fasterxml.jackson.annotation.JsonProperty("sms_type")
        val smsType: String = "auth",
    )

    data class AuthRequest(
        val phone: String,
        @com.fasterxml.jackson.annotation.JsonProperty("validation_code")
        val validationCode: String,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class GatewayResponse(
        val status: String? = null,
        val code: String? = null,
        val error: String? = null,
        val errorTraceID: String? = null,
        val result: AuthTokens? = null,
    ) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        data class AuthTokens(
            @com.fasterxml.jackson.annotation.JsonProperty("access_token")
            val accessToken: String? = null,
            @com.fasterxml.jackson.annotation.JsonProperty("refresh_token")
            val refreshToken: String? = null,
        )
    }
}
