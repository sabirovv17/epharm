package kz.epharm.mobile.auth.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.net.URI
import java.time.Duration

enum class OtpVerificationMode {
    LOCAL,
    EXTERNAL,
}

enum class OtpVerificationResult {
    VERIFIED,
    INVALID,
    EXPIRED,
}

/**
 * Полный контракт OTP-провайдера.
 *
 * LOCAL-провайдер получает сгенерированный ePharm код и только доставляет его. EXTERNAL-провайдер
 * сам генерирует код, отправляет SMS и затем проверяет введённое значение на своей стороне.
 */
interface OtpProvider {
    val id: String
    val verificationMode: OtpVerificationMode

    fun requestOtp(phone: String, localCode: String?)

    fun verifyOtp(phone: String, code: String): OtpVerificationResult =
        throw UnsupportedOperationException("Provider '$id' uses local OTP verification")
}

class LocalOtpProvider(
    override val id: String,
    private val smsSender: SmsSender,
) : OtpProvider {
    override val verificationMode: OtpVerificationMode = OtpVerificationMode.LOCAL

    override fun requestOtp(phone: String, localCode: String?) {
        requireNotNull(localCode) { "Local OTP provider requires a generated code" }
        smsSender.sendOtp(phone, localCode)
    }
}

/** Единственная точка выбора OTP-провайдера для dev, production и тестов. */
@Configuration
class OtpProviderConfig {
    private val log = LoggerFactory.getLogger(OtpProviderConfig::class.java)

    @Bean
    fun otpProvider(
        objectMapper: ObjectMapper,
        @Value("\${app.otp.dev-mode:true}") devMode: Boolean,
        @Value("\${app.otp.provider:daribar}") providerName: String,
        @Value("\${app.sms.daribar.base-url:https://backoffice.daribar.com}") daribarBaseUrl: String,
        @Value("\${app.sms.daribar.timeout-ms:10000}") daribarTimeoutMs: Long,
        @Value("\${app.sms.p1sms.base-url:https://admin.p1sms.kz}") p1smsBaseUrl: String,
        @Value("\${app.sms.p1sms.api-key:}") p1smsApiKey: String,
        @Value("\${app.sms.p1sms.channel:digit}") p1smsChannel: String,
        @Value("\${app.sms.p1sms.sender:}") p1smsSender: String,
        @Value("\${app.sms.p1sms.text-template:Код входа ePharm: {code}}") p1smsTextTemplate: String,
        @Value("\${app.sms.p1sms.timeout-ms:10000}") p1smsTimeoutMs: Long,
    ): OtpProvider {
        if (devMode) {
            log.warn("OTP: включён dev-режим с фиксированным кодом; внешние SMS отключены")
            return LocalOtpProvider("dev", LoggingSmsSender())
        }

        return when (val provider = providerName.trim().lowercase()) {
            "daribar" -> {
                requireHttpsUrl("DARIBAR_OTP_BASE_URL", daribarBaseUrl)
                log.info("OTP: активен внешний Daribar gateway (код генерирует и проверяет провайдер)")
                DaribarOtpProvider(
                    rest = restClient(daribarBaseUrl, daribarTimeoutMs),
                    objectMapper = objectMapper,
                )
            }

            "p1sms" -> {
                check(p1smsApiKey.isNotBlank()) {
                    "P1SMS_API_KEY must be configured when OTP_DEV_MODE=false and OTP_PROVIDER=p1sms"
                }
                if (p1smsChannel == "char" && p1smsSender.isBlank()) {
                    log.warn("OTP: канал p1sms 'char' без P1SMS_SENDER будет отклонён провайдером")
                }
                log.info("OTP: активен p1sms (канал={}, senderConfigured={})", p1smsChannel, p1smsSender.isNotBlank())
                LocalOtpProvider(
                    id = "p1sms",
                    smsSender = P1smsSender(
                        rest = restClient(p1smsBaseUrl, p1smsTimeoutMs),
                        apiKey = p1smsApiKey,
                        channel = p1smsChannel,
                        sender = p1smsSender,
                        textTemplate = p1smsTextTemplate,
                    ),
                )
            }

            else -> error("Unsupported OTP_PROVIDER '$provider'; expected 'daribar' or 'p1sms'")
        }
    }

    private fun restClient(baseUrl: String, timeoutMs: Long): RestClient {
        require(timeoutMs in 1_000..60_000) { "OTP provider timeout must be between 1000 and 60000 ms" }
        return RestClient.builder()
            .baseUrl(baseUrl.trimEnd('/'))
            .requestFactory(SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(Duration.ofMillis(timeoutMs))
                setReadTimeout(Duration.ofMillis(timeoutMs))
            })
            .build()
    }

    private fun requireHttpsUrl(variableName: String, value: String) {
        val uri = runCatching { URI.create(value) }.getOrNull()
        require(
            uri?.scheme.equals("https", ignoreCase = true) &&
                !uri?.host.isNullOrBlank() &&
                uri?.userInfo == null &&
                uri?.query == null &&
                uri?.fragment == null
        ) {
            "$variableName must be a non-empty HTTPS URL"
        }
    }
}
