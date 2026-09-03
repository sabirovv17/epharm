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
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException

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
    @Value("\${app.posm.fiscal-artifact-trusted-sources:standardn-kkm-sdk,ofd-api}")
    trustedFiscalSourcesRaw: String,
) {

    private val log = LoggerFactory.getLogger(PosSaleService::class.java)
    private val trustedFiscalSources = trustedFiscalSourcesRaw
        .split(',', ';')
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .toSet()

    @Transactional
    fun record(req: PosSaleRequest, identity: PosmPharmacistIdentity): Boolean {
        val fiscalArtifact = validateFiscalArtifact(req)
        val existing = posSaleRepository.findById(req.saleId).orElse(null)
        if (existing != null) {
            // The structured sale can arrive before the KKM/OFD adapter publishes the exact file.
            // A retry with the same id may enrich only fiscal evidence; reconciliation and sale
            // attribution must not run twice.
            validateExistingSaleIdentity(existing, req)
            if (fiscalArtifact != null && existing.artifactSha256 != null &&
                !existing.matches(fiscalArtifact)
            ) {
                log.error(
                    "pos_sale {} fiscal artifact conflict: stored={}, incoming={}",
                    req.saleId,
                    existing.artifactSha256,
                    fiscalArtifact.sha256,
                )
                throw ResponseStatusException(HttpStatus.CONFLICT, "Fiscal artifact conflicts with the stored sale")
            }
            if (fiscalArtifact != null && existing.artifactSha256 == null) {
                existing.applyFiscalArtifact(fiscalArtifact)
                if (!req.cashier.isNullOrBlank()) existing.cashier = req.cashier.trim()
                if (!req.shift.isNullOrBlank()) existing.shift = req.shift.trim()
                posSaleRepository.save(existing)
                log.info(
                    "pos_sale {} enriched with exact fiscal metadata, sha256={}",
                    req.saleId,
                    fiscalArtifact.sha256,
                )
            } else {
                log.debug("pos_sale {} уже обработан (идемпотентность)", req.saleId)
            }
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
                artifactFormat = fiscalArtifact?.format,
                artifactSha256 = fiscalArtifact?.sha256,
                artifactSource = fiscalArtifact?.source,
                fiscalId = fiscalArtifact?.fiscalDocumentNumber ?: req.fiscalId?.trim()?.takeIf { it.isNotEmpty() },
                fiscalSign = fiscalArtifact?.fiscalSign,
                cashRegisterRegistrationNumber = fiscalArtifact?.cashRegisterRegistrationNumber,
                ofdName = fiscalArtifact?.ofdName,
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

    /**
     * Old POSM builds sent artifactFormat=png for a locally reconstructed image. Such requests must
     * remain accepted for sale delivery, but cannot create fiscal provenance. A hash opts into the
     * exact-only contract and therefore requires the complete immutable evidence set.
     */
    private fun validateFiscalArtifact(req: PosSaleRequest): ValidatedFiscalArtifact? {
        val hash = req.artifactSha256?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        if (!hash.matches(Regex("[0-9a-f]{64}"))) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "artifactSha256 must contain 64 hex characters")
        }
        val format = req.artifactFormat.requiredFiscalField("artifactFormat").lowercase()
        if (format != "pdf" && format != "png") {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "artifactFormat must be pdf or png")
        }
        val source = req.artifactSource.requiredFiscalField("artifactSource").lowercase()
        if (source !in trustedFiscalSources) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "artifactSource is not trusted")
        }
        return ValidatedFiscalArtifact(
            format = format,
            sha256 = hash,
            source = source,
            fiscalDocumentNumber = req.fiscalId.requiredFiscalField("fiscalId"),
            fiscalSign = req.fiscalSign.requiredFiscalField("fiscalSign"),
            cashRegisterRegistrationNumber =
                req.cashRegisterRegistrationNumber.requiredFiscalField("cashRegisterRegistrationNumber"),
            ofdName = req.ofdName.requiredFiscalField("ofdName"),
        )
    }

    private fun validateExistingSaleIdentity(existing: PosSaleEntity, req: PosSaleRequest) {
        if (existing.pharmacyId != req.pharmacyId ||
            existing.totalAmount != req.totalAmount ||
            existing.sourceDocumentId != req.sourceDocumentId
        ) {
            log.error(
                "pos_sale {} identity conflict: stored pharmacy/doc/total={}/{}/{}, incoming={}/{}/{}",
                req.saleId,
                existing.pharmacyId,
                existing.sourceDocumentId,
                existing.totalAmount,
                req.pharmacyId,
                req.sourceDocumentId,
                req.totalAmount,
            )
            throw ResponseStatusException(HttpStatus.CONFLICT, "Sale identity conflicts with the stored sale")
        }
    }

    private fun String?.requiredFiscalField(name: String): String =
        this?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "$name is required for a fiscal artifact")

    private fun PosSaleEntity.applyFiscalArtifact(artifact: ValidatedFiscalArtifact) {
        artifactFormat = artifact.format
        artifactSha256 = artifact.sha256
        artifactSource = artifact.source
        fiscalId = artifact.fiscalDocumentNumber
        fiscalSign = artifact.fiscalSign
        cashRegisterRegistrationNumber = artifact.cashRegisterRegistrationNumber
        ofdName = artifact.ofdName
    }

    private fun PosSaleEntity.matches(artifact: ValidatedFiscalArtifact): Boolean =
        artifactFormat == artifact.format &&
            artifactSha256 == artifact.sha256 &&
            artifactSource == artifact.source &&
            fiscalId == artifact.fiscalDocumentNumber &&
            fiscalSign == artifact.fiscalSign &&
            cashRegisterRegistrationNumber == artifact.cashRegisterRegistrationNumber &&
            ofdName == artifact.ofdName

    private data class ValidatedFiscalArtifact(
        val format: String,
        val sha256: String,
        val source: String,
        val fiscalDocumentNumber: String,
        val fiscalSign: String,
        val cashRegisterRegistrationNumber: String,
        val ofdName: String,
    )

}
