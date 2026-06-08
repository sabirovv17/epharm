package kz.epharm.pharmacies.dto

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import kz.epharm.pharmacies.entity.ChainEntity
import kz.epharm.pharmacies.entity.PharmacyEntity
import kz.epharm.pharmacies.entity.PharmacyGroup
import java.time.Instant

data class ChainDto(
    val id: String,
    val name: String,
    val color: String,
    val points: Int,
    val group: PharmacyGroup,
) {
    companion object {
        fun of(e: ChainEntity): ChainDto = ChainDto(
            id = e.id, name = e.name, color = e.color, points = e.points, group = e.group,
        )
    }
}

/**
 * Create-запрос сети. id задаёт клиент (slug `[a-z0-9_]`) — на него ссылаются
 * аптеки (pharmacy.chainId), он должен быть стабильным и читаемым (`europharma`).
 */
data class CreateChainRequest(
    @field:NotBlank
    @field:Pattern(regexp = "[a-z0-9_]{2,64}", message = "id: только [a-z0-9_], 2-64 символа")
    val id: String,
    @field:NotBlank @field:Size(max = 255)
    val name: String,
    @field:NotBlank
    @field:Pattern(regexp = "#[0-9a-fA-F]{6}", message = "color: hex вида #RRGGBB")
    val color: String,
    @field:Min(0)
    val points: Int = 0,
    @field:NotNull
    val group: PharmacyGroup,
)

data class UpdateChainRequest(
    @field:Size(max = 255)
    val name: String? = null,
    @field:Pattern(regexp = "#[0-9a-fA-F]{6}", message = "color: hex вида #RRGGBB")
    val color: String? = null,
    @field:Min(0)
    val points: Int? = null,
    val group: PharmacyGroup? = null,
)

data class PharmacyDto(
    val id: String,
    val name: String,
    val chainId: String,
    val chainName: String,
    val city: String,
    val district: String,
    val addr: String,
    val group: PharmacyGroup,
    val pharmacists: Int,
    val receipts30d: Int,
    val gmv30d: Long,
    val liftPct: Double,
    val rulesAccepted: Int,
    val active: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun of(e: PharmacyEntity): PharmacyDto = PharmacyDto(
            id = e.id,
            name = e.name,
            chainId = e.chainId,
            chainName = e.chainName,
            city = e.city,
            district = e.district,
            addr = e.addr,
            group = e.group,
            pharmacists = e.pharmacists,
            receipts30d = e.receipts30d,
            gmv30d = e.gmv30d,
            liftPct = e.liftPct.toDouble(),
            rulesAccepted = e.rulesAccepted,
            active = e.active,
            createdAt = e.createdAt,
            updatedAt = e.updatedAt,
        )
    }
}

data class CreatePharmacyRequest(
    @field:NotBlank @field:Size(max = 255)
    val name: String,
    @field:NotBlank @field:Size(max = 64)
    val chainId: String,
    @field:NotBlank @field:Size(max = 128)
    val city: String,
    @field:Size(max = 128)
    val district: String = "",
    @field:Size(max = 255)
    val addr: String = "",
    @field:NotNull
    val group: PharmacyGroup,
    val active: Boolean = true,
)

data class UpdatePharmacyRequest(
    @field:Size(max = 255)
    val name: String? = null,
    @field:Size(max = 128)
    val city: String? = null,
    @field:Size(max = 128)
    val district: String? = null,
    @field:Size(max = 255)
    val addr: String? = null,
    val group: PharmacyGroup? = null,
    val active: Boolean? = null,
    @field:Min(0) val receipts30d: Int? = null,
    @field:Min(0) val gmv30d: Long? = null,
    @field:Min(0) val rulesAccepted: Int? = null,
)
