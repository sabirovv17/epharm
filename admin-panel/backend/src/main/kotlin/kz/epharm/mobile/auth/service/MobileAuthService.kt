package kz.epharm.mobile.auth.service

import kz.epharm.auth.dto.AuthTokens
import kz.epharm.auth.dto.RefreshResponse
import kz.epharm.auth.service.JwtService
import kz.epharm.mobile.auth.dto.MeDto
import kz.epharm.mobile.auth.dto.ActivationStatusResponse
import kz.epharm.mobile.auth.dto.ActivationVerifyResponse
import kz.epharm.mobile.auth.dto.MobileAuthResponse
import kz.epharm.mobile.auth.dto.SmsRequestResponse
import kz.epharm.mobile.auth.dto.VerifySmsResponse
import kz.epharm.pharmacists.entity.PharmacistEntity
import kz.epharm.pharmacists.entity.PharmacistStatus
import kz.epharm.pharmacists.entity.PharmacistTier
import kz.epharm.pharmacists.repository.PharmacistRepository
import kz.epharm.pharmacists.repository.StandardNPharmacistRepository
import kz.epharm.shared.PhoneUtil
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.password.PasswordEncoder
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
    private val standardNPharmacistRepository: StandardNPharmacistRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtService: JwtService,
    private val mobileRefreshTokenService: MobileRefreshTokenService,
) {

    @Transactional
    fun login(rawIin: String, password: String): MobileAuthResponse {
        val pharmacist = pharmacistRepository.findByIin(rawIin.trim())
            ?: throw AppException(
                ErrorCode.INVALID_CREDENTIALS,
                "Неверный ИИН или пароль",
                HttpStatus.UNAUTHORIZED,
            )
        ensureNotBlocked(pharmacist)
        val passwordHash = pharmacist.passwordHash
        if (passwordHash.isNullOrBlank() || !passwordEncoder.matches(password, passwordHash)) {
            throw AppException(
                ErrorCode.INVALID_CREDENTIALS,
                "Неверный ИИН или пароль",
                HttpStatus.UNAUTHORIZED,
            )
        }
        return MobileAuthResponse(tokens = issueTokens(pharmacist), pharmacist = MeDto.of(pharmacist))
    }

    @Transactional(readOnly = true)
    fun activationStatus(rawIin: String): ActivationStatusResponse {
        val identity = findActivationIdentity(rawIin)
        val pharmacist = identity.pharmacist
        return ActivationStatusResponse(
            passwordSet = !pharmacist?.passwordHash.isNullOrBlank(),
            phoneMasked = pharmacist?.phone?.takeIf(String::isNotBlank)?.let(PhoneUtil::mask) ?: "",
        )
    }

    @Transactional
    fun requestActivationSms(rawIin: String, rawPhone: String): SmsRequestResponse {
        val identity = findActivationIdentity(rawIin)
        val phone = resolveActivationPhone(identity, rawPhone)
        identity.pharmacist?.let(::ensurePasswordNotSet)
        val requested = otpService.request(phone)
        return SmsRequestResponse(
            sent = true,
            phoneMasked = PhoneUtil.mask(phone),
            ttlSeconds = requested.ttlSeconds,
            devCode = requested.devCode,
        )
    }

    @Transactional
    fun verifyActivationSms(rawIin: String, rawPhone: String, code: String): ActivationVerifyResponse {
        val identity = findActivationIdentity(rawIin)
        val phone = resolveActivationPhone(identity, rawPhone)
        identity.pharmacist?.let(::ensurePasswordNotSet)
        otpService.verify(phone, code)
        return ActivationVerifyResponse(verified = true, phoneMasked = PhoneUtil.mask(phone))
    }

    @Transactional
    fun setInitialPassword(rawIin: String, rawPhone: String, password: String): MobileAuthResponse {
        val identity = findActivationIdentity(rawIin)
        val phone = resolveActivationPhone(identity, rawPhone)
        identity.pharmacist?.let(::ensurePasswordNotSet)
        otpService.requireVerified(phone)
        val pharmacist = identity.pharmacist ?: PharmacistEntity(
            id = generateId(),
            name = identity.standardNName!!,
            iin = identity.iin,
            phone = phone,
            pharmacyId = null,
            pharmacyName = null,
            city = identity.standardNCity.orEmpty(),
            joinedAt = LocalDate.now(),
        ).also {
            it.tier = PharmacistTier.Silver
            it.status = PharmacistStatus.pending
        }
        pharmacist.passwordHash = passwordEncoder.encode(password)
        val saved = pharmacistRepository.save(pharmacist)
        otpService.consume(phone)
        return MobileAuthResponse(tokens = issueTokens(saved), pharmacist = MeDto.of(saved))
    }

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

    private fun findActivationIdentity(rawIin: String): ActivationIdentity {
        val iin = rawIin.trim()
        pharmacistRepository.findByIin(iin)?.let {
            ensureNotBlocked(it)
            return ActivationIdentity(iin = iin, pharmacist = it)
        }
        val source = standardNPharmacistRepository
            .findFirstByIinAndSourceStatusOrderBySourceProfileIdAsc(iin, 0)
            ?: throw AppException(
                ErrorCode.INVALID_CREDENTIALS,
                "Фармацевт с таким ИИН не найден",
                HttpStatus.UNAUTHORIZED,
            )
        return ActivationIdentity(
            iin = iin,
            standardNName = source.fullName,
            standardNCity = source.profileCity,
        )
    }

    private fun resolveActivationPhone(identity: ActivationIdentity, rawPhone: String): String {
        identity.pharmacist?.let { return requireBoundPhone(it, rawPhone) }
        val phone = PhoneUtil.normalize(rawPhone)
        pharmacistRepository.findByPhone(phone)?.let {
            throw AppException(
                ErrorCode.CONFLICT,
                "Этот номер уже закреплён за другим ИИН",
                HttpStatus.CONFLICT,
            )
        }
        return phone
    }

    private fun requireBoundPhone(pharmacist: PharmacistEntity, rawPhone: String): String {
        val phone = PhoneUtil.normalize(rawPhone)
        val boundPhone = PhoneUtil.normalize(pharmacist.phone)
        if (phone != boundPhone) {
            throw AppException(
                ErrorCode.INVALID_CREDENTIALS,
                "ИИН и номер телефона не совпадают",
                HttpStatus.UNAUTHORIZED,
            )
        }
        return phone
    }

    private fun ensurePasswordNotSet(pharmacist: PharmacistEntity) {
        if (!pharmacist.passwordHash.isNullOrBlank()) {
            throw AppException(
                ErrorCode.CONFLICT,
                "Пароль для этого ИИН уже создан. Войдите по ИИН и паролю",
                HttpStatus.CONFLICT,
            )
        }
    }

    private data class ActivationIdentity(
        val iin: String,
        val pharmacist: PharmacistEntity? = null,
        val standardNName: String? = null,
        val standardNCity: String? = null,
    )

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
