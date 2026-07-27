package kz.epharm.posm.service

import java.time.Duration
import kz.epharm.posm.entity.PosSaleEntity
import kz.epharm.posm.repository.RecommendationEventRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Атрибуция продажи к показанной рекомендации (Задача 1 + 1.2).
 *
 * При приёме завершённого чека ищем показанные, ещё не атрибутированные рекомендации ЭТОЙ ЖЕ
 * сессии и проверяем, оказался ли рекомендованный товар в чеке. Если да — фиксируем:
 *   - sold_at         = printed_at чека (когда продали),
 *   - sale_id         = id чека (трассировка),
 *   - seconds_to_sale = время от показа до продажи (Задача 1.2).
 *
 * Корреляция по `session_id` (одна сессия = один чек) надёжнее окна по времени: внутри одного
 * чека показ и продажа заведомо связаны. Матчинг «продан именно рекомендованный товар» — по нашему
 * productId: recommendSku хранит productId (как в /recommend), позиции чека резолвятся в productId
 * тем же коллизионно-устойчивым матчером (штрих-код→iPartID→имя). Без угадывания.
 *
 * Это ЧИСТАЯ аналитика — pending_bonuses / outcome не трогаем. Метод вызывается в транзакции
 * приёма чека (PosSaleService.record), после сохранения pos_sale.
 */
@Service
class RecommendationAttributionService(
    private val eventRepository: RecommendationEventRepository,
) {
    private val log = LoggerFactory.getLogger(RecommendationAttributionService::class.java)

    /**
     * @param sale            сохранённый чек (источник session_id / printed_at / sale_id).
     * @param soldProductIds  productId позиций чека, уже разрезолвленные (из PosSaleService).
     */
    fun attributeSale(sale: PosSaleEntity, soldProductIds: Set<String>) {
        if (soldProductIds.isEmpty()) return
        val sessionId = sale.sessionId
        if (sessionId.isNullOrBlank()) return // без сессии корреляции нет — не угадываем по аптеке

        val candidates = eventRepository.findBySessionIdAndSoldAtIsNull(sessionId)
        if (candidates.isEmpty()) return

        var attributed = 0
        for (ev in candidates) {
            if (ev.recommendSku.isBlank() || ev.recommendSku !in soldProductIds) continue
            val origin = ev.displayedAt ?: ev.shownAt
            ev.soldAt = sale.printedAt
            ev.saleId = sale.id
            // clamp >= 0: при расхождении часов кассы и сервера показ мог оказаться «позже» продажи.
            ev.secondsToSale = Duration.between(origin, sale.printedAt).seconds.coerceAtLeast(0).toInt()
            eventRepository.save(ev)
            attributed++
        }
        if (attributed > 0) {
            log.info(
                "Атрибуция чека {}: рекомендаций закрыто продажей = {} (сессия {})",
                sale.id, attributed, sessionId,
            )
        }
    }
}
