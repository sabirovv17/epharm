package kz.epharm.mobile.promotions.service

import kz.epharm.mobile.catalog.dto.MobileCatalogProductDto
import kz.epharm.mobile.catalog.service.MobileCatalogService
import kz.epharm.mobile.promotions.dto.MobilePromotionDto
import kz.epharm.promo.dto.PromoTierDto
import kz.epharm.promo.entity.PromoEntity
import kz.epharm.promo.entity.PromoStatus
import kz.epharm.promo.repository.PromoRepository
import kz.epharm.shared.error.AppException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.LocalDate

/**
 * Лента промо-товаров для мобильного приложения. Источник — активные промо-кампании
 * из админки (status=active, привязан товар витрины, дата в окне, есть ценовые пороги),
 * смерженные с опубликованными карточками текущего каталога.
 *
 * Кампании по проверенным legacy ID сливаются с canonical карточкой. После cutover
 * лента включает только опубликованные товары; до него сохраняется прежний fallback
 * на снимок промо, если Medusa недоступна.
 */
@Service
class MobilePromotionsService(
    private val promoRepository: PromoRepository,
    private val catalog: MobileCatalogService,
    @Value("\${app.eshop.catalog.read-enabled:false}") private val eshopReadEnabled: Boolean = false,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun activeFeed(today: LocalDate = LocalDate.now()): List<MobilePromotionDto> {
        // 1) Чтение промо из БД (repository открывает короткую транзакцию на один SELECT).
        //    В ленту берём только активные товарные акции в окне дат и С ценовыми порогами.
        val promos = promoRepository
            .findAllByStatusRawAndMedusaProductIdIsNotNullOrderByUpdatedAtDesc(PromoStatus.active.name)
            .filter { inWindow(it, today) && it.tiers.isNotEmpty() }
        if (promos.isEmpty()) return emptyList()

        // 2) Живые карточки витрины — ВНЕ транзакции. При сбое Medusa → пустая map → снимок.
        val cards = try {
            catalog.cardsByIds(promos.mapNotNull { it.medusaProductId })
        } catch (e: AppException) {
            if (eshopReadEnabled) throw e
            log.warn("Medusa-мёрж ленты промо упал, деградируем на снимок: {}", e.message)
            emptyMap()
        }
        // A verified alias can point several legacy campaigns to one site product.
        // Only published source cards are eligible for the mobile feed after cutover.
        return if (eshopReadEnabled) {
            promos.mapNotNull { promo ->
                cards[promo.medusaProductId]?.let { card -> toDto(promo, card) }
            }.distinctBy { it.productId }
        } else {
            promos.map { toDto(it, cards[it.medusaProductId]) }
        }
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
            productId = if (eshopReadEnabled) card?.id ?: p.medusaProductId.orEmpty()
                        else p.medusaProductId.orEmpty(),
            name = card?.name ?: p.productName.ifBlank { p.title },
            brand = card?.brand ?: p.brand.takeIf { it.isNotBlank() },
            mnn = card?.mnn,
            rxOtc = card?.rxOtc,
            // Приоритет: ручной override → живое фото Medusa → снимок при привязке.
            imageUrl = if (eshopReadEnabled) card?.imageUrl else p.overrideImage ?: card?.imageUrl ?: p.productImage,
            overrideDescription = p.overrideDescription,
            barcode = card?.barcode,
            category = card?.category,
            categories = card?.categories ?: emptyList(),
            dateStart = p.dateStart,
            dateEnd = p.dateEnd,
            tiers = p.tiers.map(PromoTierDto::of),
        )
}
