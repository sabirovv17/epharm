package kz.epharm.cdp.dto

import jakarta.validation.constraints.NotBlank
import java.time.Instant

/** Поиск профиля клиента по телефону (тело, не URL — это перс.данные). */
data class CdpLookupRequest(
    @field:NotBlank
    val phone: String,
)

data class CdpLookupResponse(
    val found: Boolean,
    val profileId: String?,
    val phone: String,
    val name: String?,
    val tier: String?,
)

/** Регистрация нового клиента на кассе. Идемпотентно по телефону. */
data class CdpRegisterRequest(
    @field:NotBlank
    val phone: String,
    val name: String? = null,
    val pharmacistId: String? = null,
    val pharmacyId: String? = null,
)

data class CdpProfileDto(
    val id: String,
    val phone: String,
    val name: String?,
    val tier: String,
    val createdAt: Instant,
    /** true — профиль создан этим вызовом; false — уже существовал. */
    val isNew: Boolean,
)
