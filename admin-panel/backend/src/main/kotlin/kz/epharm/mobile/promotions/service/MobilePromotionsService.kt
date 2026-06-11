package kz.epharm.mobile.promotions.service

import kz.epharm.mobile.catalog.dto.MobileCatalogProductDto
import kz.epharm.mobile.catalog.service.MobileCatalogService
import kz.epharm.mobile.promotions.dto.MobilePromotionDto
import kz.epharm.promo.dto.PromoTierDto
import kz.epharm.promo.entity.PromoEntity
import kz.epharm.promo.entity.PromoStatus
import kz.epharm.promo.repository.PromoRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/**
 * Лента промо-товаров для мобильного приложения. Источник — активные промо-кампании
 * из админки (status=active, привязан товар Medusa, дата в окне), смерженные с живыми
 * данными витрины Medusa (имя/бренд/фото/категории через [MobileCatalogService]).
 *
 * Если Medusa недоступна или товар удалён — деградируем на снимок промо
 * (productName/productImage/brand), чтобы лента не падала.
 */
@Service
class MobilePromotionsService(
    private val promoRepository: PromoRepository,
    private val catalog: MobileCatalogService,
) {

    @Transactional(readOnly = true)
    fun activeFeed(today: LocalDate = LocalDate.now()): List<MobilePromotionDto> {
        val promos = promoRepository
            .findAllByStatusRawAndMedusaProductIdIsNotNullOrderByUpdatedAtDesc(PromoStatus.active.name)
            .filter { inWindow(it, today) }
        if (promos.isEmpty()) return emptyList()

        // Живые карточки витрины пачкой по id (одним запросом в Medusa + кеш).
        val cards = catalog.cardsByIds(promos.mapNotNull { it.medusaProductId })
        return promos.map { toDto(it, cards[it.medusaProductId]) }
    }

    /** Промо активно сегодня: сегодня не раньше dateStart и не позже dateEnd (null = без границы). */
    private fun inWindow(p: PromoEntity, today: LocalDate): Boolean {
        val startOk = p.dateStart?.let { !today.isBefore(it) } ?: true
        val endOk = p.dateEnd?.let { !today.isAfter(it) } ?: true
        return startOk && endOk
    }

    private fun toDto(p: PromoEntity, card: MobileCatalogProductDto?): MobilePromotionDto =
        MobilePromotionDto(
            id = p.id,
            title = p.title,
            productId = p.medusaProductId.orEmpty(),
            name = card?.name ?: p.productName.ifBlank { p.title },
            brand = card?.brand ?: p.brand.takeIf { it.isNotBlank() },
            mnn = card?.mnn,
            rxOtc = card?.rxOtc,
            imageUrl = card?.imageUrl ?: p.productImage,
            barcode = card?.barcode,
            category = card?.category,
            categories = card?.categories ?: emptyList(),
            dateStart = p.dateStart,
            dateEnd = p.dateEnd,
            tiers = p.tiers.map(PromoTierDto::of),
        )
}
