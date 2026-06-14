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
import org.springframework.stereotype.Service
import java.time.LocalDate

/**
 * Лента промо-товаров для мобильного приложения. Источник — активные промо-кампании
 * из админки (status=active, привязан товар Medusa, дата в окне, есть ценовые пороги),
 * смерженные с живыми данными витрины Medusa (имя/бренд/фото/категории).
 *
 * Устойчивость:
 *  - HTTP к Medusa выполняется ВНЕ БД-транзакции (не держим соединение пула на внешний
 *    round-trip; tiers — jsonb-колонка, грузится с сущностью, lazy-ассоциаций нет);
 *  - если витрина недоступна/упала (timeout/5xx) или товар удалён — деградируем на снимок
 *    промо (productName/productImage/brand), чтобы публичная лента на главной не падала 502.
 */
@Service
class MobilePromotionsService(
    private val promoRepository: PromoRepository,
    private val catalog: MobileCatalogService,
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
            log.warn("Medusa-мёрж ленты промо упал, деградируем на снимок: {}", e.message)
            emptyMap()
        }
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
            // Приоритет: ручной override → живое фото Medusa → снимок при привязке.
            imageUrl = p.overrideImage ?: card?.imageUrl ?: p.productImage,
            overrideDescription = p.overrideDescription,
            barcode = card?.barcode,
            category = card?.category,
            categories = card?.categories ?: emptyList(),
            dateStart = p.dateStart,
            dateEnd = p.dateEnd,
            tiers = p.tiers.map(PromoTierDto::of),
        )
}
