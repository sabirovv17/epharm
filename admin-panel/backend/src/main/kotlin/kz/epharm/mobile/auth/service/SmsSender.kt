package kz.epharm.mobile.auth.service

import kz.epharm.shared.PhoneUtil
import org.slf4j.LoggerFactory

/**
 * Доставка локально сгенерированного OTP. Используется только через [LocalOtpProvider]:
 * [P1smsSender] отправляет реальную SMS, [LoggingSmsSender] обслуживает dev/test режим.
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
        log.info("[SMS-stub] OTP для {} сгенерирован (код не логируется)", PhoneUtil.mask(phone))
    }
}
