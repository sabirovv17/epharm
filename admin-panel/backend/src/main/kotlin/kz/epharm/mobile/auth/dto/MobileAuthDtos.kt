package kz.epharm.mobile.auth.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import kz.epharm.auth.dto.AuthTokens
import kz.epharm.pharmacists.entity.PharmacistEntity
import kz.epharm.pharmacists.entity.PharmacistStatus
import kz.epharm.pharmacists.entity.PharmacistTier
import java.time.LocalDate

// ── Запросы ────────────────────────────────────────────────────────────────

data class SmsRequestRequest(
    @field:NotBlank
    @field:Size(max = 32)
    // Допускаем маску `+7 (XXX) XXX-XX-XX` и любой человеческий ввод — нормализуем в сервисе.
    @field:Pattern(regexp = "[+0-9 ()\\-]{6,32}", message = "Некорректный номер телефона")
    val phone: String,
)

data class SmsVerifyRequest(
    @field:NotBlank
    @field:Size(max = 32)
    @field:Pattern(regexp = "[+0-9 ()\\-]{6,32}", message = "Некорректный номер телефона")
    val phone: String,
    @field:NotBlank
    @field:Pattern(regexp = "\\d{4,8}", message = "Код состоит из 4-8 цифр")
    val code: String,
)

data class RegisterRequest(
    @field:NotBlank
    @field:Size(max = 32)
    @field:Pattern(regexp = "[+0-9 ()\\-]{6,32}", message = "Некорректный номер телефона")
    val phone: String,
    @field:NotBlank
    @field:Size(min = 3, max = 255)
    val fio: String,
    @field:NotBlank
    @field:Pattern(regexp = "\\d{12}", message = "ИИН состоит из 12 цифр")
    val iin: String,
)

// ── Ответы ─────────────────────────────────────────────────────────────────

/** Ответ на запрос кода. devCode заполнен ТОЛЬКО в dev-режиме (для curl/E2E). */
data class SmsRequestResponse(
    val sent: Boolean,
    val phoneMasked: String,
    val ttlSeconds: Long,
    val devCode: String?,
)

/**
 * Ответ на verify. Если номер уже привязан к фармацевту — registered=true + токены + профиль.
 * Если номер новый — registered=false (клиент ведёт на экран ФИО+ИИН), токенов нет.
 */
data class VerifySmsResponse(
    val registered: Boolean,
    val tokens: AuthTokens?,
    val pharmacist: MeDto?,
)

/** Ответ на register / повторный вход — всегда с токенами и профилем. */
data class MobileAuthResponse(
    val tokens: AuthTokens,
    val pharmacist: MeDto,
)

/**
 * Профиль фармацевта для мобильного приложения. Маппится во Flutter `User` (name→fio,
 * balance→balanceKzt) + поля для BalanceCard (tier/earned/status).
 */
data class MeDto(
    val id: String,
    val name: String,
    val phone: String,
    val iin: String,
    val balance: Long,
    val tier: PharmacistTier,
    val status: PharmacistStatus,
    val pharmacyId: String?,
    val pharmacyName: String?,
    val city: String,
    val earned30d: Long,
    val receipts30d: Int,
    val rulesAccepted30d: Int,
    val coursesDone: Int,
    val coursesTotal: Int,
    val joinedAt: LocalDate,
) {
    companion object {
        fun of(e: PharmacistEntity): MeDto = MeDto(
            id = e.id,
            name = e.name,
            phone = e.phone,
            iin = e.iin,
            balance = e.balance,
            tier = e.tier,
            status = e.status,
            pharmacyId = e.pharmacyId,
            pharmacyName = e.pharmacyName,
            city = e.city,
            earned30d = e.earned30d,
            receipts30d = e.receipts30d,
            rulesAccepted30d = e.rulesAccepted30d,
            coursesDone = e.coursesDone,
            coursesTotal = e.coursesTotal,
            joinedAt = e.joinedAt,
        )
    }
}
