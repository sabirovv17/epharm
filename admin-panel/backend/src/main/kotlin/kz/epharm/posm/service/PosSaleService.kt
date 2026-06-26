package kz.epharm.posm.service

import kz.epharm.posm.dto.CartItemDto
import kz.epharm.posm.dto.PosSaleRequest
import kz.epharm.posm.entity.PosSaleEntity
import kz.epharm.posm.entity.PosSaleItem
import kz.epharm.posm.repository.PosSaleRepository
import kz.epharm.receipts.dto.LogSaleInput
import kz.epharm.receipts.dto.LogSaleItem
import kz.epharm.receipts.service.ReconcileService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Приём завершённого чека из лога кассы (источник №1 сверки). Сохраняет сырой pos_sale
 * и передаёт состав в ReconcileService для подтверждения pending-бонусов.
 * Идемпотентно по saleId — повторная отправка из offline-outbox клиента не двоит сверку.
 */
@Service
class PosSaleService(
    private val posSaleRepository: PosSaleRepository,
    private val reconcileService: ReconcileService,
    private val rulesEngineService: RulesEngineService,
    private val attributionService: RecommendationAttributionService,
) {

    private val log = LoggerFactory.getLogger(PosSaleService::class.java)

    @Transactional
    fun record(req: PosSaleRequest): Boolean {
        if (posSaleRepository.existsById(req.saleId)) {
            log.debug("pos_sale {} уже обработан (идемпотентность)", req.saleId)
            return false
        }

        val sale = posSaleRepository.save(
            PosSaleEntity(
                id = req.saleId,
                sessionId = req.sessionId,
                pharmacistId = req.pharmacistId,
                pharmacyId = req.pharmacyId,
                fiscalId = req.fiscalId,
                cashier = req.cashier,
                shift = req.shift,
                totalAmount = req.totalAmount,
                items = req.items.map { PosSaleItem(it.sku ?: "", it.barcode, it.name, it.qty, it.price, it.total) },
                printedAt = req.printedAt,
            ),
        )

        // Резолвим позиции (штрих-код → имя) в наши productId — тем же матчером, что и /recommend.
        // pending-бонус хранит recommendSku = productId, поэтому в сверку отдаём именно productId
        // (а не iPartID кассы). Не разрезолвилось — отдаём исходный sku (в проде не сматчится → ок).
        val cartItems = req.items.map { CartItemDto(sku = it.sku, barcode = it.barcode, name = it.name, qty = it.qty) }
        val productIds = rulesEngineService.resolveToProductIds(cartItems)

        // Атрибуция показ→продажа (V032): закрываем рекомендации этой сессии, чей товар попал в чек.
        attributionService.attributeSale(sale, productIds.filterNotNull().toSet())

        reconcileService.ingestLogSale(
            LogSaleInput(
                pharmacistId = req.pharmacistId,
                pharmacyId = req.pharmacyId,
                fiscalId = req.fiscalId,
                cashier = req.cashier,
                soldAt = req.printedAt,
                items = req.items.mapIndexed { i, it ->
                    LogSaleItem(sku = productIds[i] ?: it.sku ?: "", qty = it.qty, price = it.price, total = it.total)
                },
            ),
        )
        return true
    }
}
