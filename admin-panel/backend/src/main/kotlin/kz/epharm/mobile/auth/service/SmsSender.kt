package kz.epharm.mobile.auth.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Отправка SMS с OTP. На MVP — заглушка (лог). В prod заменяется на Mobizon-реализацию
 * (открытый вопрос §2 в notes — SMS-провайдер). Интерфейс, чтобы prod-бин подменялся без
 * правок OtpService.
 */
interface SmsSender {
    fun sendOtp(phone: String, code: String)
}

@Component
class LoggingSmsSender : SmsSender {
    private val log = LoggerFactory.getLogger(LoggingSmsSender::class.java)

    @Suppress("UNUSED_PARAMETER")
    override fun sendOtp(phone: String, code: String) {
        // Код входа НЕ логируем: даже в dev он возвращается в ответе /sms/request
        // (devCode), а логирование 6-значного OTP в prod = утечка фактора входа.
        log.info("[SMS-stub] OTP для {} сгенерирован (код не логируется)", phone)
    }
}
