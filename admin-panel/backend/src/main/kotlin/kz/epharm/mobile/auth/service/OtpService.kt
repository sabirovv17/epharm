package kz.epharm.mobile.auth.service

import kz.epharm.auth.service.RefreshTokenService
import kz.epharm.mobile.auth.entity.MobileOtpEntity
import kz.epharm.mobile.auth.repository.MobileOtpRepository
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant

/**
 * Жизненный цикл OTP подтверждения телефона.
 *
 * request → upsert код (hash) с TTL; verify → сверка + отметка verified_at;
 * requireVerified → окно на /register после успешной верификации; consume → удалить строку.
 *
 * dev-режим (app.otp.dev-mode=true): код всегда фиксированный (5445), совпадает с моком
 * Flutter. В production провайдер может проверять локальный код (p1sms) или полностью владеть
 * генерацией/проверкой (Daribar gateway); выбранный провайдер фиксируется в строке запроса.
 */
@Service
class OtpService(
    private val otpRepository: MobileOtpRepository,
    private val otpProvider: OtpProvider,
    @Value("\${app.otp.dev-mode:true}") private val devMode: Boolean,
    @Value("\${app.otp.dev-fixed:5445}") private val devFixed: String,
    @Value("\${app.otp.ttl-seconds:300}") private val ttlSeconds: Long,
    @Value("\${app.otp.max-attempts:5}") private val maxAttempts: Int,
    @Value("\${app.otp.register-window-seconds:900}") private val registerWindowSeconds: Long,
    @Value("\${app.otp.resend-cooldown-seconds:60}") private val resendCooldownSeconds: Long,
) {
    private val random = SecureRandom()

    /**
     * Генерит/перезаписывает код для номера. Возвращает devCode (только в dev-режиме — для
     * curl/E2E; в prod null, код уходит SMS-ой) + ttl. Сбрасывает attempts и verified_at.
     *
     * Анти-спам: повторный запрос для того же номера раньше resendCooldownSeconds → 429
     * OTP_RESEND_TOO_SOON (реальные SMS платные; без кулдауна кнопку можно закликать).
     * Уже подтверждённые строки (verified_at) кулдауном не блокируем — это новый цикл входа.
     */
    @Transactional
    fun request(phone: String, now: Instant = Instant.now()): RequestedOtp {
        val existing = otpRepository.findById(phone).orElse(null)
        if (existing != null && existing.verifiedAt == null && resendCooldownSeconds > 0) {
            val nextAllowedAt = existing.createdAt.plusSeconds(resendCooldownSeconds)
            if (now.isBefore(nextAllowedAt)) {
                val waitSec = Duration.between(now, nextAllowedAt).seconds.coerceAtLeast(1)
                throw AppException(
                    ErrorCode.OTP_RESEND_TOO_SOON,
                    "Код уже отправлен — повторная отправка через $waitSec с",
                    HttpStatus.TOO_MANY_REQUESTS,
                )
            }
        }

        val localCode = when (otpProvider.verificationMode) {
            OtpVerificationMode.LOCAL -> if (devMode) devFixed else generateCode()
            OtpVerificationMode.EXTERNAL -> null
        }
        val entity = existing ?: MobileOtpEntity(phone = phone)
        // EXTERNAL-код ePharm не знает. Случайный nonce сохраняет NOT NULL-инвариант колонки,
        // но никогда не участвует в проверке.
        entity.codeHash = RefreshTokenService.hash(localCode ?: generateProviderNonce())
        entity.verificationProvider = otpProvider.id
        entity.expiresAt = now.plus(Duration.ofSeconds(ttlSeconds))
        entity.attempts = 0
        entity.verifiedAt = null
        entity.createdAt = now
        otpRepository.save(entity)

        otpProvider.requestOtp(phone, localCode)
        return RequestedOtp(ttlSeconds = ttlSeconds, devCode = if (devMode) localCode else null)
    }

    /**
     * Сверяет код. На успехе ставит verified_at=now. Бросает машинно-читаемые ошибки на:
     * не запрашивали / истёк / слишком много попыток / неверный код.
     *
     * noRollbackFor=AppException — КРИТИЧНО: на неверном коде мы инкрементируем attempts и
     * бросаем OTP_INVALID. Без этого throw откатил бы инкремент → анти-брутфорс не работал бы
     * (attempts всегда оставался бы 0). С noRollbackFor инкремент коммитится, ошибка летит дальше.
     */
    @Transactional(noRollbackFor = [AppException::class])
    fun verify(phone: String, code: String, now: Instant = Instant.now()) {
        val entity = otpRepository.findById(phone).orElseThrow {
            AppException(ErrorCode.OTP_NOT_REQUESTED, "Код не запрашивался для этого номера", HttpStatus.BAD_REQUEST)
        }
        if (entity.isExpired(now)) {
            throw AppException(ErrorCode.OTP_EXPIRED, "Срок действия кода истёк", HttpStatus.BAD_REQUEST)
        }
        if (entity.attempts >= maxAttempts) {
            throw AppException(
                ErrorCode.OTP_TOO_MANY_ATTEMPTS,
                "Слишком много попыток — запросите код заново",
                HttpStatus.TOO_MANY_REQUESTS,
            )
        }

        if (entity.verificationProvider != otpProvider.id) {
            throw AppException(
                ErrorCode.OTP_NOT_REQUESTED,
                "Способ отправки кода изменился — запросите новый код",
                HttpStatus.BAD_REQUEST,
            )
        }

        val verification = when (otpProvider.verificationMode) {
            OtpVerificationMode.LOCAL -> {
                if (entity.codeHash == RefreshTokenService.hash(code)) {
                    OtpVerificationResult.VERIFIED
                } else {
                    OtpVerificationResult.INVALID
                }
            }

            OtpVerificationMode.EXTERNAL -> otpProvider.verifyOtp(phone, code)
        }

        if (verification == OtpVerificationResult.EXPIRED) {
            throw AppException(ErrorCode.OTP_EXPIRED, "Срок действия кода истёк", HttpStatus.BAD_REQUEST)
        }
        if (verification == OtpVerificationResult.INVALID) {
            entity.attempts += 1
            otpRepository.save(entity)
            throw AppException(ErrorCode.OTP_INVALID, "Неверный код", HttpStatus.BAD_REQUEST)
        }
        entity.verifiedAt = now
        otpRepository.save(entity)
    }

    /**
     * Гарантирует, что номер был подтверждён недавно (окно registerWindowSeconds) — иначе
     * /register отклоняется. Защищает от регистрации без верификации номера.
     */
    @Transactional(readOnly = true)
    fun requireVerified(phone: String, now: Instant = Instant.now()) {
        val entity = otpRepository.findById(phone).orElseThrow {
            AppException(ErrorCode.OTP_NOT_VERIFIED, "Сначала подтвердите номер кодом", HttpStatus.BAD_REQUEST)
        }
        val verifiedAt = entity.verifiedAt
            ?: throw AppException(ErrorCode.OTP_NOT_VERIFIED, "Сначала подтвердите номер кодом", HttpStatus.BAD_REQUEST)
        if (verifiedAt.plus(Duration.ofSeconds(registerWindowSeconds)).isBefore(now)) {
            throw AppException(
                ErrorCode.OTP_NOT_VERIFIED,
                "Подтверждение устарело — запросите код заново",
                HttpStatus.BAD_REQUEST,
            )
        }
    }

    /** Удаляет OTP-строку после успешной регистрации (одноразовость). */
    @Transactional
    fun consume(phone: String) {
        otpRepository.deleteById(phone)
    }

    private fun generateCode(): String = (random.nextInt(10_000)).toString().padStart(4, '0')

    private fun generateProviderNonce(): String =
        List(32) { random.nextInt(256).toString(16).padStart(2, '0') }.joinToString("")

    data class RequestedOtp(val ttlSeconds: Long, val devCode: String?)
}
