package kz.epharm.mobile.auth.service

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import kz.epharm.shared.PhoneUtil
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.http.HttpStatus
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.time.Duration

/**
 * Отправка OTP через p1sms.kz (POST {base-url}/apiSms/create, см. apiInstruction.pdf).
 *
 * Контракт провайдера: body = { apiKey, sms: [{ channel, phone(11 цифр, без «+»), text,
 * sender? }] }; ответ = { status: "success", data: [{ id, status: "sent", phone }] }.
 * Каналы: "digit" — цифровой (без имени отправителя), "char" — буквенный (нужен
 * зарегистрированный в ЛК p1sms sender). Канал/sender задаются env, чтобы переключаться
 * без пересборки под то, что активировано на аккаунте.
 *
 * Ошибка провайдера → AppException(SMS_SEND_FAILED, 502): OtpService.request транзакционен,
 * бросок откатывает сохранение кода — пользователь видит ошибку сразу, а не ждёт SMS,
 * которая не придёт. Код в логи НЕ пишем (фактор входа), телефон — только маской.
 */
class P1smsSender(
    private val rest: RestClient,
    private val apiKey: String,
    private val channel: String,
    private val sender: String,
    private val textTemplate: String,
) : SmsSender {
    private val log = LoggerFactory.getLogger(P1smsSender::class.java)

    override fun sendOtp(phone: String, code: String) {
        // p1sms ждёт 11 цифр без «+»: "+77011112233" → "77011112233".
        val digits = phone.filter(Char::isDigit)
        val sms = buildMap<String, Any> {
            put("channel", channel)
            put("phone", digits)
            put("text", textTemplate.replace("{code}", code))
            if (sender.isNotBlank()) put("sender", sender)
        }
        val body = mapOf("apiKey" to apiKey, "sms" to listOf(sms))

        val resp = try {
            rest.post()
                .uri("/apiSms/create")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(P1smsResponse::class.java)
        } catch (e: Exception) {
            log.warn("p1sms: отправка OTP на {} не удалась: {}", PhoneUtil.mask(phone), e.message)
            throw AppException(
                ErrorCode.SMS_SEND_FAILED,
                "Не удалось отправить SMS — попробуйте ещё раз через минуту",
                HttpStatus.BAD_GATEWAY,
                e,
            )
        }

        if (resp?.status != "success") {
            // Провайдер ответил, но отправку отклонил (неверный ключ/канал/sender/баланс).
            log.warn("p1sms: отклонил отправку на {}: status={}", PhoneUtil.mask(phone), resp?.status)
            throw AppException(
                ErrorCode.SMS_SEND_FAILED,
                "Не удалось отправить SMS — попробуйте ещё раз через минуту",
                HttpStatus.BAD_GATEWAY,
            )
        }
        val item = resp.data?.firstOrNull()
        log.info("p1sms: OTP отправлен на {} (id={}, status={})", PhoneUtil.mask(phone), item?.id, item?.status)
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class P1smsResponse(val status: String? = null, val data: List<Item>? = null) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        data class Item(val id: Long? = null, val status: String? = null, val phone: String? = null)
    }
}

/**
 * Выбор реализации SmsSender ОДНИМ детерминированным местом: задан P1SMS_API_KEY → боевой
 * p1sms; пуст → лог-заглушка (dev/тесты). Фабричный бин вместо @Component-ов, чтобы не
 * зависеть от порядка сканирования при двух кандидатах одного типа.
 */
@Configuration
class SmsSenderConfig {

    private val log = LoggerFactory.getLogger(SmsSenderConfig::class.java)

    @Bean
    fun smsSender(
        @Value("\${app.sms.p1sms.base-url:https://admin.p1sms.kz}") baseUrl: String,
        @Value("\${app.sms.p1sms.api-key:}") apiKey: String,
        @Value("\${app.otp.dev-mode:true}") devMode: Boolean,
        @Value("\${app.sms.p1sms.channel:digit}") channel: String,
        @Value("\${app.sms.p1sms.sender:}") sender: String,
        @Value("\${app.sms.p1sms.text-template:Код входа ePharm: {code}}") textTemplate: String,
        @Value("\${app.sms.p1sms.timeout-ms:10000}") timeoutMs: Long,
    ): SmsSender {
        if (apiKey.isBlank()) {
            check(devMode) {
                "P1SMS_API_KEY must be configured when OTP_DEV_MODE=false"
            }
            log.info("SMS: P1SMS_API_KEY не задан → LoggingSmsSender (заглушка, SMS не уходят)")
            return LoggingSmsSender()
        }
        if (channel == "char" && sender.isBlank()) {
            log.warn("SMS: канал 'char' без P1SMS_SENDER — p1sms отклонит отправку; задай sender или канал digit")
        }
        log.info("SMS: p1sms активен (канал={}, sender='{}')", channel, sender)
        // Таймауты — здесь (прод-путь); в тестах RestClient приходит уже с мок-фабрикой,
        // и переопределение requestFactory её бы перетёрло.
        val rest = RestClient.builder()
            .baseUrl(baseUrl)
            .requestFactory(SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(Duration.ofMillis(timeoutMs))
                setReadTimeout(Duration.ofMillis(timeoutMs))
            })
            .build()
        return P1smsSender(rest, apiKey, channel, sender, textTemplate)
    }
}
