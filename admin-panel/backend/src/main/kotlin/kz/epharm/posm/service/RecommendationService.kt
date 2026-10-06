package kz.epharm.posm.service

import jakarta.persistence.EntityManager
import kz.epharm.posm.dto.ComparisonRowDto
import kz.epharm.posm.dto.ConflictDto
import kz.epharm.posm.dto.OutcomeRequest
import kz.epharm.posm.dto.OutcomeResponse
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
 *    идемпотентная фиксация показа, фиксация результата, создание pending_bonus при accepted.
 */
@Service
class RecommendationService(
    private val rulesEngine: RulesEngineService,
    private val eventRepository: RecommendationEventRepository,
    private val pendingBonusService: PendingBonusService,
    private val jdbc: JdbcTemplate,
    private val entityManager: EntityManager,
) {

    private val log = LoggerFactory.getLogger(RecommendationService::class.java)

    companion object {
        /** UX-контракт popup: максимум пять замен и пять cross-sell одновременно. */
        private const val MAX_RECOMMENDATIONS_PER_KIND = 5
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

        // Начало текущего месяца — период для счётчика цели «N/target».
        val periodStart = java.time.YearMonth.now()
            .atDay(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant()

        val dtos = ranked.map { m ->
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
                recommendSku = m.recommend.id,
                recommendName = m.recommend.name,
                recommendVendor = m.recommend.vendor.ifBlank { null },
                recommendVolume = m.recommend.volume.ifBlank { null },
                recommendPrice = m.recommend.price,
                recommendBarcode = m.recommend.barcode?.ifBlank { null },
                partnerLabel = card?.partnerLabel?.ifBlank { null },
                bonus = m.rule.bonus,
                script = m.rule.script,
                advantages = m.rule.advantages,
                comparison = card?.comparison.orEmpty().map {
                    ComparisonRowDto(it.label, it.triggerValue, it.recommendValue, it.recommendHighlight)
                },
                goalText = goalText,
                goalBonus = goalBonus,
            )
        }
        val conflicts = matchResult.conflicts.map {
            ConflictDto(kind = it.kind, triggerName = it.triggerName, reason = it.reason, ruleIds = it.ruleIds)
        }
        return RecommendResponse(sessionId = req.sessionId, recommendations = dtos, conflicts = conflicts)
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
        if (event.displayedAt == null) {
            event.displayedAt = shownAt
            eventRepository.save(event)
        }
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
                val alreadyAccepted = eventRepository.findAllBySessionId(event.sessionId).any {
                    it.id != event.id && it.recommendSku == event.recommendSku &&
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
                val pb = pendingBonusService.register(
                    pharmacistId = event.pharmacistId,
                    sku = event.recommendSku,
                    productName = event.recommendName,
                    expectedAmount = event.expectedAmount,
                    bonus = event.bonus,
                    ruleId = event.ruleId,
                )
                event.outcome = RecommendationOutcome.accepted
                event.pendingBonusId = pb.id
                event.decidedAt = Instant.now()
                log.info(
                    "Рекомендация {} принята → pending_bonus {} ({}₸) для фармацевта {}",
                    event.id, pb.id, event.bonus, event.pharmacistId,
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
        if (existing != null && existing.outcome == RecommendationOutcome.shown) return existing

        val event = RecommendationEventEntity(
            id = "rec_${UUID.randomUUID().toString().substring(0, 8)}",
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
}
