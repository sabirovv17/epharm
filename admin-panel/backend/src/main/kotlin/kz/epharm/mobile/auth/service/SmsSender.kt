package kz.epharm.mobile.auth.service

import org.slf4j.LoggerFactory

/**
 * Отправка SMS с OTP. Реализации: [P1smsSender] (боевой p1sms.kz) и [LoggingSmsSender]
 * (заглушка-лог только для dev/тестов). Какая активна — решает [SmsSenderConfig]:
 * production без P1SMS_API_KEY не запускается, чтобы запрос OTP не выглядел успешным,
 * когда SMS фактически не отправляется. Интерфейс позволяет подменять бин без правок OtpService.
 */
interface SmsSender {
    fun sendOtp(phone: String, code: String)
}

class LoggingSmsSender : SmsSender {
    private val log = LoggerFactory.getLogger(LoggingSmsSender::class.java)

    @Suppress("UNUSED_PARAMETER")
    override fun sendOtp(phone: String, code: String) {
        // Код входа НЕ логируем: даже в dev он возвращается в ответе /sms/request
        // (devCode), а логирование 6-значного OTP в prod = утечка фактора входа.
        log.info("[SMS-stub] OTP для {} сгенерирован (код не логируется)", phone)
    }
}
