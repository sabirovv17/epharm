package kz.epharm.posm.service

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
) {

    private val log = LoggerFactory.getLogger(PosSaleService::class.java)

    @Transactional
    fun record(req: PosSaleRequest): Boolean {
        if (posSaleRepository.existsById(req.saleId)) {
            log.debug("pos_sale {} уже обработан (идемпотентность)", req.saleId)
            return false
        }

        posSaleRepository.save(
            PosSaleEntity(
                id = req.saleId,
                sessionId = req.sessionId,
                pharmacistId = req.pharmacistId,
                pharmacyId = req.pharmacyId,
                fiscalId = req.fiscalId,
                cashier = req.cashier,
                shift = req.shift,
                totalAmount = req.totalAmount,
                items = req.items.map { PosSaleItem(it.sku, it.name, it.qty, it.price, it.total) },
                printedAt = req.printedAt,
            ),
        )

        reconcileService.ingestLogSale(
            LogSaleInput(
                pharmacistId = req.pharmacistId,
                pharmacyId = req.pharmacyId,
                fiscalId = req.fiscalId,
                cashier = req.cashier,
                soldAt = req.printedAt,
                items = req.items.map { LogSaleItem(sku = it.sku, qty = it.qty, price = it.price, total = it.total) },
            ),
        )
        return true
    }
}
