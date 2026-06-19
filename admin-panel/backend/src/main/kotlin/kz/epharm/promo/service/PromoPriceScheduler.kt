package kz.epharm.promo.service

import kz.epharm.catalog.repository.ProductRepository
import kz.epharm.medusa.service.MedusaPriceService
import kz.epharm.promo.entity.PromoTier
import kz.epharm.promo.repository.PromoRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Ежедневное обновление цен из Medusa (T1).
 *
 * Требование: «цены на все товары обновлять раз в день из Medusa, чтобы в блоке
 * рекомендаций всегда была актуальная инфа». Обновляем два источника:
 *  - promos.tiers[0].price — цена в мобильной ленте акций (бонус не трогаем);
 *  - products.price — цена в POSM-карточке рекомендации (замена/кросс-селл).
 *
 * Устойчивость: цена тянется без кэша (MedusaPriceService). Если Medusa недоступна
 * или у товара нет цены — оставляем прошлое значение (не обнуляем). Один инстанс бэка
 * в проде → без распределённой блокировки.
 *
 * Cron: `app.promo.price-refresh-cron` (по умолчанию 06:00 Asia/Almaty — перед
 * открытием аптек, чтобы к началу рабочего дня цены и блок рекомендаций были свежими).
 */
@Service
class PromoPriceScheduler(
    private val promoRepository: PromoRepository,
    private val productRepository: ProductRepository,
    private val medusaPriceService: MedusaPriceService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // Таймзона зафиксирована Asia/Almaty: иначе @Scheduled берёт TZ JVM/контейнера
    // (в проде UTC) и рефреш «уезжает» на 5 ч. По умолчанию 06:00 — перед открытием аптек.
    @Scheduled(cron = "\${app.promo.price-refresh-cron:0 0 6 * * *}", zone = "Asia/Almaty")
    fun scheduledRefresh() {
        val summary = refreshNow()
        log.info(
            "Ежедневный рефреш цен Medusa: промо обновлено {}/{}, товаров {}/{}",
            summary.promosUpdated, summary.promosTotal, summary.productsUpdated, summary.productsTotal,
        )
    }

    /** Точка для ручного запуска (тест / админ-кнопка). Возвращает сводку. */
    @Transactional
    fun refreshNow(): RefreshSummary {
        if (!medusaPriceService.active) {
            log.warn("Рефреш цен пропущен: Medusa выключена")
            return RefreshSummary(0, 0, 0, 0)
        }
        return RefreshSummary(
            promosTotal = 0, promosUpdated = 0,
            productsTotal = 0, productsUpdated = 0,
        ).let { refreshPromos(it) }.let { refreshProducts(it) }
    }

    private fun refreshPromos(acc: RefreshSummary): RefreshSummary {
        val promos = promoRepository.findAllByMedusaProductIdIsNotNull()
        var updated = 0
        for (p in promos) {
            // Один запрос к Medusa → актуальные цена И обложка (фото в витрине тоже
            // меняют). Снимок недоступен (Medusa легла / товара нет) → пропускаем,
            // прошлые значения остаются.
            val snap = medusaPriceService.snapshotOf(p.medusaProductId) ?: continue
            var changed = false

            // Цена первого порога (бонус фармацевту сохраняем как есть).
            val tier = p.tiers.firstOrNull()
            if (snap.price != null && tier != null && tier.price != snap.price) {
                p.tiers = listOf(PromoTier(minQty = tier.minQty, price = snap.price, bonus = tier.bonus)) +
                    p.tiers.drop(1)
                changed = true
            }

            // Снимок обложки витрины (productImage) — фоллбэк-фото и первый кадр
            // галереи. overrideImage (ручная обложка) — отдельное поле, не трогаем.
            if (!snap.cover.isNullOrBlank() && p.productImage != snap.cover) {
                p.productImage = snap.cover
                changed = true
            }

            // Штрих-код EAN-13 (ключ матчинга POSM-кассы) — тоже обновляем из Medusa.
            if (!snap.barcode.isNullOrBlank() && p.barcode != snap.barcode) {
                p.barcode = snap.barcode
                changed = true
            }

            if (changed) {
                promoRepository.save(p)
                updated++
            }
        }
        return acc.copy(promosTotal = promos.size, promosUpdated = updated)
    }

    private fun refreshProducts(acc: RefreshSummary): RefreshSummary {
        val products = productRepository.findAllByMedusaProductIdIsNotNull()
        var updated = 0
        for (prod in products) {
            // Один снимок Medusa → актуальные цена И штрих-код (ключ матчинга кассы).
            // Снимок недоступен → пропускаем, прошлые значения остаются.
            val snap = medusaPriceService.snapshotOf(prod.medusaProductId) ?: continue
            var changed = false

            val intPrice = snap.price?.toInt()
            if (intPrice != null && prod.price != intPrice) {
                prod.price = intPrice
                changed = true
            }
            if (!snap.barcode.isNullOrBlank() && prod.barcode != snap.barcode) {
                prod.barcode = snap.barcode
                changed = true
            }

            if (changed) {
                productRepository.save(prod)
                updated++
            }
        }
        return acc.copy(productsTotal = products.size, productsUpdated = updated)
    }

    data class RefreshSummary(
        val promosTotal: Int,
        val promosUpdated: Int,
        val productsTotal: Int,
        val productsUpdated: Int,
    )
}
