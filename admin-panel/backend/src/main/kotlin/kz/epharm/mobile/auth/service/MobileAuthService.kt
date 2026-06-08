package kz.epharm.mobile.auth.service

import kz.epharm.auth.dto.AuthTokens
import kz.epharm.auth.dto.RefreshResponse
import kz.epharm.auth.service.JwtService
import kz.epharm.mobile.auth.dto.MeDto
import kz.epharm.mobile.auth.dto.MobileAuthResponse
import kz.epharm.mobile.auth.dto.SmsRequestResponse
import kz.epharm.mobile.auth.dto.VerifySmsResponse
import kz.epharm.pharmacists.entity.PharmacistEntity
import kz.epharm.pharmacists.entity.PharmacistStatus
import kz.epharm.pharmacists.entity.PharmacistTier
import kz.epharm.pharmacists.repository.PharmacistRepository
import kz.epharm.shared.PhoneUtil
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Бизнес-логика аутентификации мобильного приложения фармацевта.
 *
 * Флоу (решение пользователя — саморегистрация → pending → активация админом):
 *   1. requestSms  — выдать OTP на номер.
 *   2. verifySms   — сверить код. Номер уже фармацевта → сразу токены. Новый → registered=false.
 *   3. register    — для нового номера: ФИО+ИИН → pharmacist(status=pending) → токены.
 *   4. refresh / logout / me — стандартный JWT-цикл под роль PHARMACIST.
 */
@Service
class MobileAuthService(
    private val otpService: OtpService,
    private val pharmacistRepository: PharmacistRepository,
    private val jwtService: JwtService,
    private val mobileRefreshTokenService: MobileRefreshTokenService,
) {

    @Transactional
    fun requestSms(rawPhone: String): SmsRequestResponse {
        val phone = PhoneUtil.normalize(rawPhone)
        val requested = otpService.request(phone)
        return SmsRequestResponse(
            sent = true,
            phoneMasked = PhoneUtil.mask(phone),
            ttlSeconds = requested.ttlSeconds,
            devCode = requested.devCode,
        )
    }

    @Transactional
    fun verifySms(rawPhone: String, code: String): VerifySmsResponse {
        val phone = PhoneUtil.normalize(rawPhone)
        otpService.verify(phone, code)

        val pharmacist = pharmacistRepository.findByPhone(phone)
            ?: return VerifySmsResponse(registered = false, tokens = null, pharmacist = null)

        ensureNotBlocked(pharmacist)
        // Существующий фармацевт — OTP больше не нужен.
        otpService.consume(phone)
        return VerifySmsResponse(
            registered = true,
            tokens = issueTokens(pharmacist),
            pharmacist = MeDto.of(pharmacist),
        )
    }

    @Transactional
    fun register(rawPhone: String, fio: String, iin: String): MobileAuthResponse {
        val phone = PhoneUtil.normalize(rawPhone)
        // Номер должен быть подтверждён недавно (окно из OtpService).
        otpService.requireVerified(phone)

        pharmacistRepository.findByPhone(phone)?.let {
            throw AppException(ErrorCode.CONFLICT, "Этот номер уже зарегистрирован", HttpStatus.CONFLICT)
        }
        pharmacistRepository.findByIin(iin.trim())?.let {
            throw AppException(ErrorCode.CONFLICT, "Этот ИИН уже зарегистрирован", HttpStatus.CONFLICT)
        }

        val entity = PharmacistEntity(
            id = generateId(),
            name = fio.trim(),
            iin = iin.trim(),
            phone = phone,
            // Аптека ещё не назначена — её привяжет админ при активации.
            pharmacyId = null,
            pharmacyName = null,
            city = "",
            joinedAt = LocalDate.now(),
        ).also {
            it.tier = PharmacistTier.Silver
            it.status = PharmacistStatus.pending
        }
        val saved = pharmacistRepository.save(entity)
        otpService.consume(phone)

        return MobileAuthResponse(tokens = issueTokens(saved), pharmacist = MeDto.of(saved))
    }

    @Transactional
    fun refresh(rawRefreshToken: String): RefreshResponse {
        val now = Instant.now()
        val pharmacistId = mobileRefreshTokenService.rotate(rawRefreshToken, now)
            ?: throw AppException(
                ErrorCode.INVALID_REFRESH_TOKEN,
                "Refresh-токен недействителен или истёк",
                HttpStatus.UNAUTHORIZED,
            )
        val pharmacist = pharmacistRepository.findById(pharmacistId).orElseThrow {
            AppException(ErrorCode.USER_NOT_FOUND, "Фармацевт не найден", HttpStatus.UNAUTHORIZED)
        }
        if (pharmacist.status == PharmacistStatus.blocked) {
            mobileRefreshTokenService.revokeAllForPharmacist(pharmacist.id)
            throw AppException(ErrorCode.PHARMACIST_BLOCKED, "Аккаунт заблокирован", HttpStatus.FORBIDDEN)
        }
        return RefreshResponse(tokens = issueTokens(pharmacist, now))
    }

    @Transactional
    fun logout(pharmacistId: String) {
        mobileRefreshTokenService.revokeAllForPharmacist(pharmacistId)
    }

    @Transactional(readOnly = true)
    fun me(pharmacistId: String): MeDto {
        val pharmacist = pharmacistRepository.findById(pharmacistId).orElseThrow {
            AppException(ErrorCode.USER_NOT_FOUND, "Фармацевт не найден", HttpStatus.UNAUTHORIZED)
        }
        return MeDto.of(pharmacist)
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun ensureNotBlocked(pharmacist: PharmacistEntity) {
        if (pharmacist.status == PharmacistStatus.blocked) {
            throw AppException(ErrorCode.PHARMACIST_BLOCKED, "Аккаунт заблокирован", HttpStatus.FORBIDDEN)
        }
    }

    private fun issueTokens(pharmacist: PharmacistEntity, now: Instant = Instant.now()): AuthTokens {
        val access = jwtService.issuePharmacistToken(pharmacist.id, pharmacist.name, pharmacist.phone, now)
        val refresh = mobileRefreshTokenService.issue(pharmacist.id, now)
        return AuthTokens(
            accessToken = access,
            accessTokenExpiresAt = now.plus(jwtService.accessTtl),
            refreshToken = refresh.raw,
            refreshTokenExpiresAt = refresh.expiresAt,
        )
    }

    private fun generateId(): String = "u_${UUID.randomUUID().toString().substring(0, 8)}"
}
