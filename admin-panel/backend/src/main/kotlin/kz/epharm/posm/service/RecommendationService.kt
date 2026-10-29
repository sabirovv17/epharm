package kz.epharm.posm.service

import jakarta.persistence.EntityManager
import kz.epharm.catalog.entity.ProductEntity
import kz.epharm.catalog.repository.ProductRepository
import kz.epharm.merchtasks.dto.MerchProductBalance
import kz.epharm.merchtasks.dto.MerchRecommendationCandidate
import kz.epharm.merchtasks.dto.MerchRecommendationCartItem
import kz.epharm.merchtasks.dto.MerchRecommendationContextRequest
import kz.epharm.merchtasks.dto.MerchRecommendationContextEnvelope
import kz.epharm.merchtasks.dto.MerchRecommendationProductRef
import kz.epharm.merchtasks.service.MerchTaskClient
import kz.epharm.posm.dto.ComparisonRowDto
import kz.epharm.posm.dto.CartItemDto
import kz.epharm.posm.dto.ConflictDto
import kz.epharm.posm.dto.OutcomeRequest
import kz.epharm.posm.dto.OutcomeResponse
import kz.epharm.posm.dto.ProductBalanceDto
import kz.epharm.posm.dto.RecommendRequest
import kz.epharm.posm.dto.RecommendResponse
import kz.epharm.posm.dto.RecommendationDto
import kz.epharm.rules.entity.RuleCard
import kz.epharm.posm.entity.RecommendationEventEntity
import kz.epharm.posm.entity.RecommendationKind
import kz.epharm.posm.entity.RecommendationOutcome
import kz.epharm.posm.repository.RecommendationEventRepository
import kz.epharm.receipts.service.PendingBonusService
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Оркестрация POSM-рекомендаций (ТЗ §4):
 *  - подбор и ранжирование делегируется RulesEngineService;
 *  - здесь: фильтр «не показывать отклонённое в этом чеке», лимит до 5 на тип,
 *    идемпотентная фиксация показа, фиксация результата, создание pending_bonus при accepted;
 *  - фактический ACK может завершить проверку поздно доставленной продажи без фиктивного accepted.
 */
@Service
class RecommendationService(
    private val rulesEngine: RulesEngineService,
    private val eventRepository: RecommendationEventRepository,
    private val pendingBonusService: PendingBonusService,
    private val jdbc: JdbcTemplate,
    private val entityManager: EntityManager,
    private val merchTaskClient: MerchTaskClient,
    private val productRepository: ProductRepository,
    private val saleBonuses: RecommendationSaleBonusService,
) {

    private val log = LoggerFactory.getLogger(RecommendationService::class.java)

    companion object {
        /** UX-контракт popup: максимум пять замен и пять cross-sell одновременно. */
        private const val MAX_RECOMMENDATIONS_PER_KIND = 5
        // Matches the CRM integration's documented PHARMAPAY_BALANCE_MAX_AGE_SECONDS contract.
        private const val MAX_CONFIRMED_STOCK_AGE_SECONDS = 1_800L
    }

    @Transactional
    fun recommend(req: RecommendRequest): RecommendResponse {
        // A decision already made for this product in the current receipt must not prompt again.
        val decidedSkus = eventRepository.findAllBySessionId(req.sessionId)
            .filter { it.outcome == RecommendationOutcome.rejected || it.outcome == RecommendationOutcome.accepted }
            .map { it.recommendSku }
            .toSet()

        val matchResult = rulesEngine.match(req.cart, req.scannedBarcode)
        val eligible = matchResult.matches.filter { it.recommend.id !in decidedSkus }
        val ranked = eligible.filter { it.rule.type == kz.epharm.rules.entity.RuleType.substitution }
            .take(MAX_RECOMMENDATIONS_PER_KIND) +
            eligible.filter { it.rule.type == kz.epharm.rules.entity.RuleType.crosssell }
                .take(MAX_RECOMMENDATIONS_PER_KIND)

        // The optional call carries only pharmacy/product context. Pharmacist identity and the
        // receipt session remain inside ePharm. A timeout or malformed response yields an empty
        // context and the existing local rules continue unchanged.
        val merchContext = merchTaskClient.recommendationContext(
            MerchRecommendationContextRequest(
                pharmacyId = req.pharmacyId,
                cart = req.cart.map {
                    MerchRecommendationCartItem(it.sku, it.barcode, it.name, it.qty)
                },
                requestedProducts = ranked.map {
                    MerchRecommendationProductRef(
                        productId = it.recommend.id,
                        barcode = it.recommend.barcode,
                        ipartId = it.recommend.ipartId,
                    )
                }.distinct(),
            ),
        ).takeIf { it.available && it.pharmacyMapped } ?: MerchRecommendationContextEnvelope(available = false)
        val balanceBarcodes = productsByBarcode(merchContext.balances.mapNotNull { it.barcode })

        // Начало текущего месяца — период для счётчика цели «N/target».
        val periodStart = java.time.YearMonth.now()
            .atDay(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant()

        val localOutOfStock = ranked.filter { isConfirmedOutOfStock(it.recommend, merchContext.balances, balanceBarcodes) }
        val selectedLocal = ranked.filterNot { it in localOutOfStock }
        val localDtos = selectedLocal.map { m ->
            val event = upsertShownEvent(req, m)
            val card = m.rule.card
            val (goalText, goalBonus) = buildGoal(card, req.pharmacistId, m.rule.id, periodStart)
            RecommendationDto(
                eventId = event.id,
                ruleId = m.rule.id,
                kind = m.rule.type.name,
                triggerSku = m.triggerSku,
                triggerIpartId = m.triggerIpartId,
                triggerName = m.triggerName,
                triggerVolume = m.triggerProduct?.volume?.ifBlank { null },
                triggerPrice = m.triggerProduct?.price,
                triggerBarcode = m.triggerBarcode,
                recommendSku = event.recommendSku,
                recommendName = event.recommendName,
                recommendVendor = m.recommend.vendor.ifBlank { null },
                recommendVolume = m.recommend.volume.ifBlank { null },
                recommendPrice = snapshotMoney(event.expectedAmount),
                recommendBarcode = m.recommend.barcode?.ifBlank { null },
                partnerLabel = card?.partnerLabel?.ifBlank { null },
                bonus = snapshotMoney(event.bonus),
                script = m.rule.script,
                advantages = m.rule.advantages,
                comparison = card?.comparison.orEmpty().map {
                    ComparisonRowDto(it.label, it.triggerValue, it.recommendValue, it.recommendHighlight)
                },
                goalText = goalText,
                goalBonus = goalBonus,
                source = "epharm",
                balance = balanceFor(m.recommend, merchContext.balances, balanceBarcodes),
            )
        }
        val localKeys = selectedLocal.map { it.rule.type.name to it.recommend.id }.toSet()
        val resolvedExternal = resolveExternalRecommendations(req, merchContext.recommendations)
            .filter { it.recommend.id !in decidedSkus }
            .filter { (it.candidate.kind to it.recommend.id) !in localKeys }
        val externalOutOfStock = resolvedExternal.filter {
            isConfirmedOutOfStock(it.recommend, merchContext.balances, balanceBarcodes)
        }
        val externalInStock = resolvedExternal.filterNot { it in externalOutOfStock }
        val localByPair = selectedLocal.groupBy {
            (it.triggerBarcode ?: it.triggerSku ?: it.triggerIpartId?.let { id -> "ipart:$id" }) to it.recommend.id
        }
        val externalContradictions = externalInStock.groupBy { it.pairKey() }.filter { (pair, offers) ->
            offers.map { it.candidate.kind }.distinct().size > 1 || offers.any { offer ->
                localByPair[pair].orEmpty().any { it.rule.type.name != offer.candidate.kind }
            }
        }
        // CRM cannot remove authoritative local rules. Conflicting external pairs are suppressed.
        val external = externalInStock.filterNot { it.pairKey() in externalContradictions }

        val externalDtos = listOf("substitution", "crosssell").flatMap { kind ->
            val remaining = MAX_RECOMMENDATIONS_PER_KIND - localDtos.count { it.kind == kind }
            external.asSequence()
                .filter { it.candidate.kind == kind }
                .sortedWith(compareByDescending<ExternalRecommendation> { it.candidate.priority }
                    .thenByDescending { it.candidate.bonus }
                    .thenBy { it.candidate.id })
                .distinctBy { it.recommend.id }
                .take(remaining.coerceAtLeast(0))
                .map { externalRecommendationDto(req, it, merchContext.balances, balanceBarcodes) }
                .toList()
        }
        val conflicts = matchResult.conflicts.map {
            ConflictDto(kind = it.kind, triggerName = it.triggerName, reason = it.reason, ruleIds = it.ruleIds)
        } + localOutOfStock.map {
            ConflictDto(
                kind = "unavailable",
                triggerName = it.triggerName,
                reason = "Рекомендованный товар отсутствует в подтверждённом остатке аптеки",
                ruleIds = listOf(it.rule.id),
            )
        } + externalOutOfStock.map {
            ConflictDto(
                kind = "unavailable",
                triggerName = it.triggerItem.name ?: it.triggerProduct?.name,
                reason = "Рекомендованный товар отсутствует в подтверждённом остатке аптеки",
                ruleIds = listOf("merch:${it.candidate.id}"),
            )
        } + externalContradictions.map { (pair, offers) ->
            ConflictDto(
                kind = "contradiction",
                triggerName = offers.first().triggerItem.name ?: offers.first().triggerProduct?.name,
                reason = "Одна и та же пара товаров одновременно задана как замена и допродажа; " +
                    "противоречащие рекомендации CRM не показаны",
                ruleIds = localByPair[pair].orEmpty().map { it.rule.id } +
                    offers.map { "merch:${it.candidate.id}" },
            )
        }
        val ordered = listOf("substitution", "crosssell").flatMap { kind ->
            (localDtos + externalDtos).filter { it.kind == kind }
        }
        return RecommendResponse(
            sessionId = req.sessionId,
            recommendations = ordered,
            conflicts = conflicts,
        )
    }

    private data class ExternalRecommendation(
        val candidate: MerchRecommendationCandidate,
        val triggerItem: CartItemDto,
        val triggerProduct: ProductEntity?,
        val recommend: ProductEntity,
    ) {
        fun pairKey(): Pair<String?, String> = (
            triggerItem.barcode?.trim()?.takeIf(String::isNotEmpty)
                ?: triggerProduct?.barcode?.trim()?.takeIf(String::isNotEmpty)
                ?: triggerProduct?.id
                ?: triggerItem.sku?.trim()?.takeIf(String::isNotEmpty)?.let { "ipart:$it" }
            ) to recommend.id
    }

    /** Resolve both sides by exact identifiers only; ambiguous catalog keys fail closed. */
    private fun resolveExternalRecommendations(
        req: RecommendRequest,
        candidates: List<MerchRecommendationCandidate>,
    ): List<ExternalRecommendation> {
        if (candidates.isEmpty() || req.cart.isEmpty()) return emptyList()
        val ids = candidates.mapNotNull { it.recommendProductId?.trim()?.takeIf(String::isNotEmpty) }.toSet()
        val barcodes = candidates.flatMap {
            listOfNotNull(it.triggerBarcode?.trim(), it.recommendBarcode?.trim())
        }.plus(req.cart.mapNotNull { it.barcode?.trim() }).filter(String::isNotEmpty).toSet()
        val ipartIds = candidates.flatMap {
            listOfNotNull(it.triggerIpartId?.trim(), it.recommendIpartId?.trim())
        }.filter(String::isNotEmpty).toSet()
        val byId = productRepository.findAllById(ids).associateBy(ProductEntity::id)
        val byBarcode = productsByBarcode(barcodes)
        val byIpart = uniqueProducts(
            if (ipartIds.isEmpty()) emptyList() else productRepository.findAllByIpartIdIn(ipartIds),
        ) { it.ipartId?.trim() }

        return candidates.mapNotNull { candidate ->
            val triggerBarcode = candidate.triggerBarcode?.trim()?.takeIf(String::isNotEmpty)
            val triggerIpart = candidate.triggerIpartId?.trim()?.takeIf(String::isNotEmpty)
            if (triggerBarcode != null && triggerBarcode in byBarcode && byBarcode[triggerBarcode] == null) return@mapNotNull null
            val triggerByBarcode = triggerBarcode?.let(byBarcode::get)
            val triggerByIpart = triggerIpart?.let(byIpart::get)
            if (triggerByBarcode != null && triggerByIpart != null && triggerByBarcode.id != triggerByIpart.id) {
                return@mapNotNull null
            }
            val triggerItem = req.cart.firstOrNull { item ->
                val itemBarcode = item.barcode?.trim()?.takeIf(String::isNotEmpty)
                if (itemBarcode != null && itemBarcode in byBarcode && byBarcode[itemBarcode] == null) return@firstOrNull false
                val itemByBarcode = itemBarcode?.let(byBarcode::get)
                if (itemByBarcode != null && triggerByIpart != null && itemByBarcode.id != triggerByIpart.id) {
                    return@firstOrNull false
                }
                val barcodeMatches = triggerBarcode == null || itemBarcode == null || itemBarcode == triggerBarcode ||
                    (triggerByBarcode != null && itemByBarcode?.id == triggerByBarcode.id)
                val ipartMatches = triggerIpart == null || item.sku.isNullOrBlank() ||
                    item.sku?.trim() == triggerIpart
                val hasExactMatch = (triggerBarcode != null && itemBarcode != null && barcodeMatches) ||
                    (triggerIpart != null && item.sku?.trim() == triggerIpart)
                barcodeMatches && ipartMatches && hasExactMatch
            } ?: return@mapNotNull null
            val suppliedProducts = listOfNotNull(
                candidate.recommendProductId?.trim()?.takeIf(String::isNotEmpty)?.let(byId::get),
                candidate.recommendBarcode?.trim()?.takeIf(String::isNotEmpty)?.let(byBarcode::get),
                candidate.recommendIpartId?.trim()?.takeIf(String::isNotEmpty)?.let(byIpart::get),
            )
            val suppliedKeyCount = listOf(candidate.recommendProductId, candidate.recommendBarcode,
                candidate.recommendIpartId).count { !it.isNullOrBlank() }
            if (suppliedProducts.size != suppliedKeyCount || suppliedProducts.map { it.id }.distinct().size != 1) {
                return@mapNotNull null
            }
            val recommend = suppliedProducts.first()
            val alreadyInCart = req.cart.any { item ->
                (item.barcode?.trim() in recommend.scanBarcodes()) ||
                    (!recommend.ipartId.isNullOrBlank() && item.sku?.trim() == recommend.ipartId?.trim()) ||
                    (item.barcode.isNullOrBlank() && item.sku.isNullOrBlank() &&
                        item.name?.trim()?.equals(recommend.name.trim(), ignoreCase = true) == true)
            }
            if (alreadyInCart) return@mapNotNull null
            ExternalRecommendation(
                candidate = candidate,
                triggerItem = triggerItem,
                triggerProduct = triggerItem.barcode?.trim()?.let(byBarcode::get) ?: triggerByBarcode ?: triggerByIpart,
                recommend = recommend,
            )
        }
    }

    private fun uniqueProducts(
        products: List<ProductEntity>,
        key: (ProductEntity) -> String?,
    ): Map<String, ProductEntity> = products
        .mapNotNull { product -> key(product)?.takeIf(String::isNotEmpty)?.let { it to product } }
        .groupBy({ it.first }, { it.second })
        .filterValues { it.size == 1 }
        .mapValues { it.value.single() }

    /** Query all owners, not only the active rule products; null keeps conflicting aliases closed. */
    private fun productsByBarcode(raw: Collection<String>): Map<String, ProductEntity?> {
        val barcodes = raw.map(String::trim).filter(String::isNotEmpty).toSet()
        if (barcodes.isEmpty()) return emptyMap()
        return productRepository.findAllByBarcodeIn(barcodes)
            .flatMap { product -> product.scanBarcodes().filter { it in barcodes }.map { it to product } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, products) -> products.distinctBy(ProductEntity::id).singleOrNull() }
    }

    private fun externalRecommendationDto(
        req: RecommendRequest,
        external: ExternalRecommendation,
        balances: List<MerchProductBalance>,
        balanceBarcodes: Map<String, ProductEntity?>,
    ): RecommendationDto {
        val candidate = external.candidate
        val ruleId = "merch:${candidate.id}"
        val triggerIdentity = external.triggerProduct?.id
            ?: candidate.triggerBarcode?.trim()?.let { "ean:$it" }
            ?: candidate.triggerIpartId?.trim()?.let { "ipart:$it" }
        val event = upsertExternalShownEvent(req, ruleId, triggerIdentity, external)
        return RecommendationDto(
            eventId = event.id,
            ruleId = ruleId,
            kind = candidate.kind,
            triggerSku = external.triggerProduct?.id,
            triggerIpartId = external.triggerItem.sku?.trim()?.takeIf(String::isNotEmpty),
            triggerName = external.triggerItem.name?.trim()?.takeIf(String::isNotEmpty)
                ?: external.triggerProduct?.name,
            triggerVolume = external.triggerProduct?.volume?.ifBlank { null },
            triggerPrice = external.triggerProduct?.price,
            triggerBarcode = external.triggerItem.barcode?.trim()?.takeIf(String::isNotEmpty),
            recommendSku = event.recommendSku,
            recommendName = event.recommendName,
            recommendVendor = external.recommend.vendor.ifBlank { null },
            recommendVolume = external.recommend.volume.ifBlank { null },
            recommendPrice = snapshotMoney(event.expectedAmount),
            recommendBarcode = external.recommend.barcode?.ifBlank { null },
            partnerLabel = candidate.partnerLabel?.ifBlank { null } ?: "МЕРЧЕНДАЙЗИНГ",
            bonus = snapshotMoney(event.bonus),
            script = candidate.script,
            advantages = candidate.advantages,
            comparison = emptyList(),
            goalText = null,
            goalBonus = null,
            source = "merchandising",
            balance = balanceFor(external.recommend, balances, balanceBarcodes),
        )
    }

    private fun balanceFor(
        product: ProductEntity,
        balances: List<MerchProductBalance>,
        balanceBarcodes: Map<String, ProductEntity?>,
    ): ProductBalanceDto? {
        val balance = balances.firstOrNull { candidate ->
            val supplied = listOf(candidate.productId, candidate.barcode, candidate.ipartId)
                .count { !it.isNullOrBlank() }
            val matches = listOf(
                candidate.productId?.trim()?.let { it == product.id },
                candidate.barcode?.trim()?.let { balanceBarcodes[it]?.id == product.id },
                candidate.ipartId?.trim()?.let { it == product.ipartId?.trim() },
            ).filterNotNull()
            supplied > 0 && matches.size == supplied && matches.all { it }
        } ?: return null
        val now = Instant.now()
        val fresh = balance.observedAt?.let {
            !it.isAfter(now) && !it.isBefore(now.minusSeconds(MAX_CONFIRMED_STOCK_AGE_SECONDS))
        } ?: false
        val status = if (balance.status == "confirmed" && !fresh) {
            if (balance.observedAt == null) "unknown" else "stale"
        } else balance.status
        return ProductBalanceDto(
            pharmacyAvailableQty = balance.pharmacyAvailableQty,
            networkAvailableQty = balance.networkAvailableQty,
            warehouseAvailableQty = balance.warehouseAvailableQty,
            reservedQty = balance.reservedQty,
            observedAt = balance.observedAt,
            source = balance.source,
            status = status,
        )
    }

    private fun isConfirmedOutOfStock(
        product: ProductEntity,
        balances: List<MerchProductBalance>,
        balanceBarcodes: Map<String, ProductEntity?>,
    ): Boolean {
        val balance = balanceFor(product, balances, balanceBarcodes) ?: return false
        return balance.status == "confirmed" &&
            balance.pharmacyAvailableQty != null && balance.pharmacyAvailableQty.signum() == 0
    }

    /**
     * Динамическая цель «N/target замен <label>»: считает принятые этим фармацевтом события по
     * этому правилу с начала периода. Нет goalTarget в правиле → блок цели не показываем.
     */
    private fun buildGoal(card: RuleCard?, pharmacistId: String, ruleId: String, since: Instant): Pair<String?, Int?> {
        val target = card?.goalTarget ?: return null to null
        val current = eventRepository.countByPharmacistIdAndRuleIdAndOutcomeRawAndDecidedAtAfter(
            pharmacistId, ruleId, RecommendationOutcome.accepted.name, since,
        )
        val label = card.goalLabel?.takeIf { it.isNotBlank() }
        val text = if (label != null) "цель «$current/$target $label»" else "цель «$current/$target»"
        return text to card.goalBonus
    }

    /**
     * Фиксация фактического показа попапа (V032): касса шлёт client-время показа через outbox.
     * Идемпотентно — пишем displayedAt только если ещё пусто. Толерантно к неизвестному eventId
     * (no-op без ошибки), чтобы outbox-пинг не зацикливался на 404, если событие не дошло.
     */
    @Transactional
    fun markDisplayed(eventId: String, shownAt: Instant, expectedPharmacyId: String? = null) {
        val event = eventRepository.findById(eventId).orElse(null) ?: return
        if (expectedPharmacyId != null && event.pharmacyId != expectedPharmacyId) return
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", "recommendation:${event.sessionId}")
        entityManager.flush()
        entityManager.refresh(event)
        if (event.displayedAt == null) {
            event.displayedAt = shownAt
            eventRepository.save(event)
        }
        saleBonuses.afterDisplay(event)
    }

    @Transactional
    fun recordOutcome(eventId: String, req: OutcomeRequest, expectedPharmacyId: String? = null): OutcomeResponse {
        val event = eventRepository.findById(eventId).orElseThrow {
            AppException(ErrorCode.NOT_FOUND, "Recommendation $eventId not found", HttpStatus.NOT_FOUND)
        }
        if (expectedPharmacyId != null && event.pharmacyId != expectedPharmacyId) {
            throw AppException(ErrorCode.NOT_FOUND, "Recommendation $eventId not found", HttpStatus.NOT_FOUND)
        }
        // A group trigger can produce several events for one offered SKU in one receipt.
        // Serialize outcome decisions by receipt before checking other accepted events or
        // creating a pending bonus. Refresh also makes concurrent retries of this event idempotent.
        jdbc.queryForList(
            "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
            "recommendation:${event.sessionId}",
        )
        entityManager.flush()
        entityManager.refresh(event)
        // Идемпотентность: решение уже зафиксировано — возвращаем как есть.
        if (event.outcome != RecommendationOutcome.shown) {
            return OutcomeResponse(event.id, event.outcomeRaw, event.pendingBonusId)
        }

        when (req.outcome) {
            "rejected" -> {
                event.outcome = RecommendationOutcome.rejected
                event.decidedAt = Instant.now()
            }
            "accepted" -> {
                val sameOfferEvents = eventRepository.findAllBySessionId(event.sessionId).filter {
                    it.pharmacyId == event.pharmacyId && it.recommendSku == event.recommendSku
                }
                val alreadyAccepted = sameOfferEvents.any {
                    it.id != event.id &&
                        it.outcome == RecommendationOutcome.accepted
                }
                if (alreadyAccepted) {
                    throw AppException(
                        ErrorCode.CONFLICT,
                        "This product was already accepted in the current receipt",
                        HttpStatus.CONFLICT,
                    )
                }
                if (event.pharmacistId.isBlank()) {
                    throw AppException(
                        ErrorCode.USER_NOT_ACTIVE,
                        "POSM не определил активного фармацевта Standard-N или профиль ещё не сопоставлен",
                        HttpStatus.CONFLICT,
                    )
                }
                val existingPendingIds = sameOfferEvents.mapNotNull { it.pendingBonusId }.distinct()
                if (existingPendingIds.size > 1) {
                    throw AppException(ErrorCode.CONFLICT, "Conflicting reservations in the current receipt", HttpStatus.CONFLICT)
                }
                val pendingId = existingPendingIds.singleOrNull()?.let {
                    pendingBonusService.reuseForOutcome(it, event.pharmacistId, event.pharmacyId, event.recommendSku,
                        expectedAmount = event.expectedAmount, bonus = event.bonus)
                } ?: pendingBonusService.register(
                    pharmacistId = event.pharmacistId,
                    sku = event.recommendSku,
                    productName = event.recommendName,
                    expectedAmount = event.expectedAmount,
                    bonus = event.bonus,
                    ruleId = event.ruleId,
                ).id
                event.outcome = RecommendationOutcome.accepted
                event.pendingBonusId = pendingId
                event.decidedAt = Instant.now()
                log.info(
                    "Рекомендация {} принята → pending_bonus {} ({}₸) для фармацевта {}",
                    event.id, pendingId, event.bonus, event.pharmacistId,
                )
            }
        }
        val saved = eventRepository.save(event)
        return OutcomeResponse(saved.id, saved.outcomeRaw, saved.pendingBonusId)
    }

    /**
     * Идемпотентная фиксация показа: одна строка на (sessionId, ruleId, concrete trigger). Если правило уже
     * показано в этом чеке и решение ещё не принято — переиспользуем существующее событие
     * (касса дёргает /recommend на каждый товар, не плодим дубли).
     */
    private fun upsertShownEvent(req: RecommendRequest, m: RuleMatch): RecommendationEventEntity {
        val existing = eventRepository.findFirstBySessionIdAndRuleIdAndTriggerSkuOrderByShownAtDesc(
            req.sessionId, m.rule.id, m.triggerSku,
        )
        if (sameIssuedOffer(existing, req, m.recommend.id, m.rule.type.name)) return existing!!

        val event = RecommendationEventEntity(
            id = "rec_${UUID.randomUUID().toString().replace("-", "")}",
            sessionId = req.sessionId,
            pharmacistId = req.pharmacistId,
            pharmacyId = req.pharmacyId,
            ruleId = m.rule.id,
            triggerSku = m.triggerSku,
            triggerName = m.triggerName?.take(255),
            recommendSku = m.recommend.id,
            recommendName = m.recommend.name,
            expectedAmount = m.recommend.price.toLong(),
            bonus = m.rule.bonus.toLong(),
        ).also {
            it.kind = if (m.rule.type.name == "crosssell") RecommendationKind.crosssell else RecommendationKind.substitution
        }
        return eventRepository.save(event)
    }

    private fun upsertExternalShownEvent(
        req: RecommendRequest,
        ruleId: String,
        triggerIdentity: String?,
        external: ExternalRecommendation,
    ): RecommendationEventEntity {
        val safeTrigger = triggerIdentity?.take(64)
        val existing = eventRepository.findFirstBySessionIdAndRuleIdAndTriggerSkuOrderByShownAtDesc(
            req.sessionId,
            ruleId,
            safeTrigger,
        )
        if (sameIssuedOffer(existing, req, external.recommend.id, external.candidate.kind)) return existing!!

        val event = RecommendationEventEntity(
            id = "rec_${UUID.randomUUID().toString().replace("-", "")}",
            sessionId = req.sessionId,
            pharmacistId = req.pharmacistId,
            pharmacyId = req.pharmacyId,
            ruleId = ruleId,
            triggerSku = safeTrigger,
            triggerName = (external.triggerItem.name ?: external.triggerProduct?.name)?.take(255),
            recommendSku = external.recommend.id,
            recommendName = external.recommend.name,
            expectedAmount = external.recommend.price.toLong(),
            bonus = external.candidate.bonus.toLong(),
        ).also {
            it.kind = if (external.candidate.kind == "crosssell") {
                RecommendationKind.crosssell
            } else {
                RecommendationKind.substitution
            }
        }
        return eventRepository.save(event)
    }

    private fun sameIssuedOffer(event: RecommendationEventEntity?, req: RecommendRequest, sku: String, kind: String): Boolean =
        event != null && event.outcome == RecommendationOutcome.shown && event.pharmacyId == req.pharmacyId &&
            event.pharmacistId == req.pharmacistId && event.recommendSku == sku && event.kindRaw == kind

    /** One open receipt keeps its issued integer-KZT quote; later receipts use current terms. */
    private fun snapshotMoney(value: Long): Int {
        if (value !in 0L..Int.MAX_VALUE.toLong()) {
            throw AppException(ErrorCode.CONFLICT, "Сохранённые условия рекомендации выходят за допустимый диапазон суммы", HttpStatus.CONFLICT)
        }
        return value.toInt()
    }
}
