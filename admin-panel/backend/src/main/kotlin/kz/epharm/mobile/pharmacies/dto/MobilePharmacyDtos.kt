package kz.epharm.mobile.pharmacies.dto

import kz.epharm.pharmacies.entity.PharmacyEntity

/**
 * Аптека для мобильного приложения (выбор места покупки при загрузке чека).
 * Источник — НАШ реестр аптек (golden rule: что в админке, то и в приложении),
 * а не хардкод в Flutter. `chainColor` — для бейджа сети в списке.
 */
data class MobilePharmacyDto(
    val id: String,
    val name: String,
    val chain: String,
    val chainColor: String,
    val city: String,
    val district: String,
    val addr: String,
) {
    companion object {
        fun of(e: PharmacyEntity, chainColor: String): MobilePharmacyDto = MobilePharmacyDto(
            id = e.id,
            name = e.name,
            chain = e.chainName,
            chainColor = chainColor,
            city = e.city,
            district = e.district,
            addr = e.addr,
        )
    }
}
