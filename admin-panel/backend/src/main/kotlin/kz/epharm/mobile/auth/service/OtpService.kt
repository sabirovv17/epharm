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
 * dev-режим (app.otp.dev-mode=true): код всегда фиксированный (544544), совпадает с моком
 * Flutter (lib/features/auth/data/auth_repository.dart) — чтобы dev-вход был детерминирован.
 * prod-режим: случайный 6-значный код + реальная отправка через SmsSender.
 */
@Service
class OtpService(
    private val otpRepository: MobileOtpRepository,
    private val smsSender: SmsSender,
    @Value("\${app.otp.dev-mode:true}") private val devMode: Boolean,
    @Value("\${app.otp.dev-fixed:544544}") private val devFixed: String,
    @Value("\${app.otp.ttl-seconds:300}") private val ttlSeconds: Long,
    @Value("\${app.otp.max-attempts:5}") private val maxAttempts: Int,
    @Value("\${app.otp.register-window-seconds:900}") private val registerWindowSeconds: Long,
) {
    private val random = SecureRandom()

    /**
     * Генерит/перезаписывает код для номера. Возвращает devCode (только в dev-режиме — для
     * curl/E2E; в prod null, код уходит SMS-ой) + ttl. Сбрасывает attempts и verified_at.
     */
    @Transactional
    fun request(phone: String, now: Instant = Instant.now()): RequestedOtp {
        val code = if (devMode) devFixed else generateCode()
        val entity = otpRepository.findById(phone).orElseGet { MobileOtpEntity(phone = phone) }
        entity.codeHash = RefreshTokenService.hash(code)
        entity.expiresAt = now.plus(Duration.ofSeconds(ttlSeconds))
        entity.attempts = 0
        entity.verifiedAt = null
        entity.createdAt = now
        otpRepository.save(entity)

        if (!devMode) smsSender.sendOtp(phone, code)
        return RequestedOtp(ttlSeconds = ttlSeconds, devCode = if (devMode) code else null)
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
        if (entity.codeHash != RefreshTokenService.hash(code)) {
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

    private fun generateCode(): String = (random.nextInt(1_000_000)).toString().padStart(6, '0')

    data class RequestedOtp(val ttlSeconds: Long, val devCode: String?)
}
