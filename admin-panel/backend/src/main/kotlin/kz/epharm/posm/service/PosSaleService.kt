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
    fun record(req: PosSaleRequest, identity: PosmPharmacistIdentity): Boolean {
        if (posSaleRepository.existsById(req.saleId)) {
            log.debug("pos_sale {} уже обработан (идемпотентность)", req.saleId)
            return false
        }

        // Резолвим до сохранения, чтобы pos_sales был полноценной базой соответствий:
        // pharmacyId + локальный iPartID/EAN + внутренний catalog productId.
        val cartItems = req.items.map { CartItemDto(sku = it.sku, barcode = it.barcode, name = it.name, qty = it.qty) }
        val productIds = rulesEngineService.resolveToProductIds(cartItems)

        val sale = posSaleRepository.save(
            PosSaleEntity(
                id = req.saleId,
                sessionId = req.sessionId,
                pharmacistId = req.pharmacistId,
                pharmacistName = req.pharmacistName?.trim()?.takeIf { it.isNotEmpty() },
                reportedPharmacistId = identity.reportedPharmacistId,
                reportedPharmacistName = identity.reportedPharmacistName,
                pharmacistSource = identity.source.wireValue,
                pharmacyId = req.pharmacyId,
                sourceDocumentId = req.sourceDocumentId,
                captureSource = req.captureSource?.trim()?.takeIf { it.isNotEmpty() },
                artifactFormat = req.artifactFormat?.trim()?.lowercase()?.takeIf { it.isNotEmpty() },
                fiscalId = req.fiscalId,
                cashier = req.cashier,
                shift = req.shift,
                totalAmount = req.totalAmount,
                items = req.items.mapIndexed { index, item ->
                    PosSaleItem(
                        sku = item.sku ?: "",
                        barcode = item.barcode,
                        name = item.name,
                        qty = item.qty,
                        price = item.price,
                        total = item.total,
                        productId = productIds[index],
                    )
                },
                printedAt = req.printedAt,
            ),
        )
        if (identity.pharmacistId.isBlank()) {
            log.warn(
                "POS sale {}: Standard-N продавец не сопоставлен (pharmacy={}, reportedId={}, reportedName={})",
                req.saleId,
                req.pharmacyId,
                identity.reportedPharmacistId ?: "—",
                identity.reportedPharmacistName ?: "—",
            )
        }

        // Атрибуция показ→продажа (V032): закрываем рекомендации этой сессии, чей товар попал в чек.
        attributionService.attributeSale(sale, productIds.filterNotNull().toSet())

        // Сверка/начисление допустимы только для доверенного внутреннего pharmacistId.
        // Сырой Standard-N USER_ID всё равно сохранён выше и отображается в аналитике.
        if (req.pharmacistId.isNotBlank()) {
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
        }
        return true
    }
}
