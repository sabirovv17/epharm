package kz.epharm.promo.service

import kz.epharm.auth.access.AdministrativeScope
import kz.epharm.catalog.entity.ProductEntity
import kz.epharm.catalog.repository.ProductRepository
import kz.epharm.catalog.service.AccCatalogTaxonomy
import kz.epharm.catalog.service.AccScopeKind
import kz.epharm.medusa.service.MedusaPriceService
import kz.epharm.promo.dto.PromoComparisonRowDto
import kz.epharm.promo.dto.PromoOfferProductRefDto
import kz.epharm.promo.dto.PromoRuleProductRefDto
import kz.epharm.promo.dto.PromoRulesConfigDto
import kz.epharm.promo.dto.PromoRulesViewDto
import kz.epharm.promo.entity.PromoEntity
import kz.epharm.promo.entity.PromoStatus
import kz.epharm.promo.repository.PromoRepository
import kz.epharm.rules.entity.RuleCard
import kz.epharm.rules.entity.RuleComparisonRow
import kz.epharm.rules.entity.RuleEntity
import kz.epharm.rules.entity.RuleStatus
import kz.epharm.rules.entity.RuleTrigger
import kz.epharm.rules.entity.RuleType
import kz.epharm.rules.repository.RuleRepository
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import kz.epharm.shared.validation.ConfirmedBarcodeAliases
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import java.time.Instant

/**
 * Авторинг правил замены/кросс-селла ИЗ кампании (T2).
 *
 * Кампания продвигает один товар (promos.medusaProductId). Админ в её карточке выбирает товары,
 * которые уже есть/могут быть в чеке и должны привести к продаже продвигаемого:
 *  - replacements — какие товары ЗАМЕНЯЕМ на продвигаемый (substitution-правила),
 *  - crossSells   — с какими товарами в чеке ПРЕДЛАГАЕМ продвигаемый (crosssell-правила),
 *  - и весь текст фармацевту (script/advantages/карточка-сравнение/цель) — общий для всех правил.
 *
 * Публикация обновляет совпадающие пары с сохранением ID и истории; удалённые пары
 * архивируются с retiredAt. Под выбранный товар апсертим локальный [ProductEntity]
 * (legacy-поле medusaProductId), чтобы FK rules.recommend и POSM-матчер работали.
 * Действующий внешний каталог цен — aptekasosklada.kz через backend ePharm.
 *
 * То, что админ задал здесь, **попадает в рекомендацию фармацевта** через тот же
 * RulesEngineService/RecommendationService.
 */
@Service
class PromoRulesService(
    private val promoRepository: PromoRepository,
    private val ruleRepository: RuleRepository,
    private val productRepository: ProductRepository,
    private val medusaPriceService: MedusaPriceService,
    private val accCatalogTaxonomy: AccCatalogTaxonomy,
    private val administrativeScope: AdministrativeScope,
) {

    @Transactional(readOnly = true)
    fun view(promoId: String): PromoRulesViewDto {
        val promo = loadPromo(promoId)
        val rules = ruleRepository.findAllByPromoIdOrderByUpdatedAtDesc(promoId).filter { it.retiredAt == null }
        val subs = rules.filter { it.type == RuleType.substitution }
        val cross = rules.filter { it.type == RuleType.crosssell }

        // Восстанавливаем ссылки на товары из каталога + ВСЕ per-pair поля карточки
        // (скрипт/преимущества/партнёр/сравнение/цель) из самого правила.
        // Если товар удалён из каталога — НЕ теряем правило: показываем минимальный ref
        // (id + поля карточки), чтобы админ всё равно видел/мог отредактировать/убрать пару.
        val promotedId = promo.medusaProductId
        val replacements = reconstructGroups(subs, promotedId, legacyCrossSell = false)
        val crossSells = reconstructGroups(cross, promotedId, legacyCrossSell = true)

        // Цель — на уровне кампании (источник истины — promos.*), а не из правил:
        // так она не теряется, даже если у кампании пока нет ни одной пары.
        val config = PromoRulesConfigDto(
            replacements = replacements,
            crossSells = crossSells,
            goalLabel = promo.goalLabel,
            goalTarget = promo.goalTarget,
            goalBonus = promo.goalBonus,
        )
        return PromoRulesViewDto(
            promoId = promoId,
            config = config,
            ruleCount = rules.size,
            activeCount = rules.count { it.status == RuleStatus.active },
        )
    }

    /** Legacy authoring remains available only until that account enters the managed workflow. */
    @Transactional
    fun replace(promoId: String, config: PromoRulesConfigDto, createdBy: String): PromoRulesViewDto =
        replaceInternal(promoId, config, createdBy, allowManaged = false, replaceNullableIdentity = false)

    @Transactional
    internal fun replacePrepared(promoId: String, config: PromoRulesConfigDto, createdBy: String): PromoRulesViewDto =
        replaceInternal(promoId, config, createdBy, allowManaged = true, replaceNullableIdentity = true)

    @Transactional
    internal fun createInitialDraft(promoId: String, config: PromoRulesConfigDto, createdBy: String): PromoRulesViewDto {
        val promo = loadPromo(promoId, forUpdate = true)
        if (promo.status != PromoStatus.draft || ruleRepository.findAllByPromoIdForUpdate(promoId).isNotEmpty() ||
            (config.replacements + config.crossSells).any { it.active }) {
            throw AppException(ErrorCode.CONFLICT, "Начальные рекомендации доступны только для нового неактивного черновика", HttpStatus.CONFLICT)
        }
        return replaceInternal(promoId, config, createdBy, allowManaged = true, replaceNullableIdentity = false)
    }

    private fun replaceInternal(promoId: String, config: PromoRulesConfigDto, createdBy: String,
        allowManaged: Boolean, replaceNullableIdentity: Boolean): PromoRulesViewDto {
        val promo = loadPromo(promoId, forUpdate = true)
        val promotedMedusaId = promo.medusaProductId
            ?: throw AppException(
                ErrorCode.VALIDATION_FAILED,
                "Сначала привяжите товар к кампании, затем настраивайте замены/кросс-селл",
                HttpStatus.BAD_REQUEST,
            )
        // Validate and authorize before changing published entities.
        val replacementTriggers = config.replacements.map { it to triggerFor(it) }
        val crossSellTriggers = config.crossSells.map { it to triggerFor(it) }
        val currentRules = ruleRepository.findAllByPromoIdForUpdate(promoId).filter { it.retiredAt == null }
        compatibilityIssue(promo, currentRules)?.let {
            throw AppException(ErrorCode.CONFLICT, it, HttpStatus.CONFLICT)
        }
        val campaignBonus = checkedCampaignBonus(promo)
        authorizeReplacement(promo, config, currentRules, replacementTriggers, crossSellTriggers, campaignBonus)
        if (administrativeScope.hasManagedRequest() && !allowManaged) {
            throw AppException(ErrorCode.CONFLICT, "Изменяйте рекомендации кампании через подготовленный набор и «Применить»", HttpStatus.CONFLICT)
        }
        // Локальный товар-продвигаемый (recommend для замен и кросс-селла).
        val promoted = upsertPromotedProduct(promo, promotedMedusaId, replaceNullableIdentity)

        // Цель кампании — пишем на саму кампанию (источник истины), чтобы она не терялась
        // даже без пар. В card правил она потом денормализуется (для POSM-кассы).
        // Цель «всё-или-ничего»: без target цель бессмысленна (касса её не покажет).
        val goalLabel = config.goalLabel?.trim()?.takeIf { it.isNotBlank() }
        val goalTarget = config.goalTarget?.takeIf { it > 0 }
        promo.goalLabel = if (goalTarget != null) goalLabel else null
        promo.goalTarget = goalTarget
        promo.goalBonus = if (goalTarget != null) config.goalBonus?.takeIf { it >= 0 } else null
        promoRepository.save(promo)

        // Старые клиенты не передают per-offer bonus и сохраняют бонус кампании.
        // Явный 0 в новом редакторе означает «без бонуса» для конкретного препарата.
        // Кампания — мастер-выключатель: правило active только если И кампания active,
        // И сама пара active (ref.active). Иначе — draft.
        val campaignActive = promo.status == PromoStatus.active
        fun effectiveStatus(ref: PromoRuleProductRefDto): RuleStatus =
            if (campaignActive && ref.active) RuleStatus.active else RuleStatus.draft

        val created = mutableListOf<RuleEntity>()

        // Замены: триггер — заменяемый товар, рекомендация — продвигаемый.
        replacementTriggers
            .filter { (_, trigger) -> trigger.kind != "product" || trigger.value != promotedMedusaId }
            .distinctBy { (_, trigger) -> trigger.kind to trigger.value }
            .forEach { (ref, trigger) ->
                if (trigger.kind == "product") upsertProduct(ref, replaceNullableIdentity)
                recommendationsFor(ref, promoted, replaceNullableIdentity).forEachIndexed { offerRank, offer ->
                    created += RuleEntity(
                        id = generateRuleId(RuleType.substitution),
                        recommend = offer.product.id,
                        bonus = offer.bonus ?: campaignBonus,
                        // Поля карточки кассы — per-pair; пусто → общий дефолт из config.
                        script = ref.script.ifBlank { config.script },
                        advantages = ref.advantages.ifEmpty { config.advantages },
                        card = cardFor(ref, config, offerRank, offer.bonus == null),
                        trigger = trigger,
                        createdBy = createdBy,
                    ).also {
                        it.type = RuleType.substitution
                        it.status = effectiveStatus(ref)
                        it.promoId = promoId
                    }
                }
            }

        // Кросс-селл: триггер — товар уже в чеке, рекомендация — продвигаемый товар кампании.
        crossSellTriggers
            .filter { (_, trigger) -> trigger.kind != "product" || trigger.value != promotedMedusaId }
            .distinctBy { (_, trigger) -> trigger.kind to trigger.value }
            .forEach { (ref, trigger) ->
                if (trigger.kind == "product") upsertProduct(ref, replaceNullableIdentity)
                recommendationsFor(ref, promoted, replaceNullableIdentity).forEachIndexed { offerRank, offer ->
                    created += RuleEntity(
                        id = generateRuleId(RuleType.crosssell),
                        recommend = offer.product.id,
                        bonus = offer.bonus ?: campaignBonus,
                        // Поля карточки кассы — per-pair; пусто → общий дефолт из config.
                        script = ref.script.ifBlank { config.script },
                        advantages = ref.advantages.ifEmpty { config.advantages },
                        card = cardFor(ref, config, offerRank, offer.bonus == null),
                        trigger = trigger,
                        createdBy = createdBy,
                    ).also {
                        it.type = RuleType.crosssell
                        it.status = effectiveStatus(ref)
                        it.promoId = promoId
                    }
                }
            }

        persistWithoutLosingHistory(currentRules, created)
        return view(promoId)
    }

    // ── Internals ─────────────────────────────────────────────────────────

    private data class PairIntent(val type: RuleType, val kind: String, val value: Any)
    private data class PaidOffer(val pair: PairIntent, val productId: String, val bonus: Int)

    private data class StableRuleKey(val type: RuleType, val trigger: RuleTrigger, val productId: String)
    private fun stableKey(rule: RuleEntity) = StableRuleKey(rule.type, rule.trigger.copy(label = null), rule.recommend)

    internal fun compatibilityIssue(promo: PromoEntity): String? = compatibilityIssue(promo,
        ruleRepository.findAllByPromoIdOrderByUpdatedAtDesc(promo.id).filter { it.retiredAt == null })

    private fun compatibilityIssue(promo: PromoEntity, existing: List<RuleEntity>): String? {
        val ambiguousVariants = existing.groupBy(::stableKey).values.any { rows -> rows.size > 1 && rows.any { it.abTest != null } }
        val normalizedVariant = existing.any { it.abTest != null && (it.trigger.kind == "product_any" ||
            it.type == RuleType.crosssell && it.trigger.kind == "product" && it.trigger.value == promo.medusaProductId) }
        if (ambiguousVariants || normalizedVariant) {
            return "Конфигурация A/B-вариантов не представлена в редакторе кампании: используйте Rules Engine без объединения вариантов"
        }
        if (existing.any { !it.trigger.exclude.isNullOrEmpty() }) {
            return "В правилах есть исключения триггера: настройте их в Rules Engine, редактор кампании не должен их удалять"
        }
        return null
    }

    private fun persistWithoutLosingHistory(previous: List<RuleEntity>, proposed: List<RuleEntity>) {
        val available = previous.groupBy(::stableKey).mapValues { (_, rows) ->
            rows.sortedWith(compareBy<RuleEntity> { it.createdAt }.thenBy { it.id }).toMutableList()
        }
        val kept = proposed.map { next ->
            val existing = available[stableKey(next)]?.removeFirstOrNull() ?: return@map next
            val preserveUnknownBonus = existing.card?.bonusInherited == null && next.card?.bonusInherited == false &&
                existing.bonus == next.bonus
            existing.trigger = next.trigger
            existing.bonus = next.bonus
            existing.script = next.script
            existing.advantages = next.advantages
            // This editor has no A/B input; an unchanged pair must retain its existing assignment.
            existing.card = if (preserveUnknownBonus) next.card?.copy(bonusInherited = null) else next.card
            existing.status = next.status
            existing
        }
        val retired = available.values.flatten()
        val now = Instant.now()
        retired.forEach { it.status = RuleStatus.archived; it.retiredAt = now }
        ruleRepository.saveAllAndFlush(kept + retired)
    }

    private fun checkedCampaignBonus(promo: PromoEntity): Int {
        return checkedBonus(promo.pharmacistBonus)
    }

    private fun checkedBonus(value: Long): Int {
        if (value !in 0L..Int.MAX_VALUE.toLong()) {
            throw AppException(ErrorCode.VALIDATION_FAILED, "Бонус кампании превышает допустимый размер", HttpStatus.BAD_REQUEST)
        }
        return value.toInt()
    }

    internal fun requiresBonusPermission(promo: PromoEntity, config: PromoRulesConfigDto,
        proposedProductId: String?, proposedBonus: Long): Boolean {
        val pairs = selectedPairs(proposedProductId, config.replacements.map { it to triggerFor(it) },
            config.crossSells.map { it to triggerFor(it) })
        val current = ruleRepository.findAllByPromoIdOrderByUpdatedAtDesc(promo.id).filter { it.retiredAt == null }
        return financialChange(promo, config, current, pairs, proposedProductId, checkedBonus(proposedBonus))
    }

    private fun selectedPairs(productId: String?, replacements: List<Pair<PromoRuleProductRefDto, RuleTrigger>>,
        crossSells: List<Pair<PromoRuleProductRefDto, RuleTrigger>>): List<Pair<PairIntent, PromoRuleProductRefDto>> =
        listOf(RuleType.substitution to replacements, RuleType.crosssell to crossSells).flatMap { (type, refs) ->
            refs.filter { (_, trigger) -> trigger.kind != "product" || trigger.value != productId }
                .distinctBy { (_, trigger) -> trigger.kind to trigger.value }
                .map { (ref, trigger) -> PairIntent(type, trigger.kind, trigger.value) to ref }
        }

    private fun authorizeReplacement(
        promo: PromoEntity, config: PromoRulesConfigDto, currentRules: List<RuleEntity>,
        replacements: List<Pair<PromoRuleProductRefDto, RuleTrigger>>,
        crossSells: List<Pair<PromoRuleProductRefDto, RuleTrigger>>, campaignBonus: Int,
    ) {
        val pairs = selectedPairs(promo.medusaProductId, replacements, crossSells)
        val enabledBefore = currentRules.filter { it.card?.pairActive != false }
            .map { PairIntent(it.type, it.trigger.kind, it.trigger.value) }.toSet()
        val enabledAfter = pairs.filter { it.second.active }.map { it.first }.toSet()
        if (promo.status == PromoStatus.active || enabledBefore != enabledAfter) {
            administrativeScope.requireAllPermission(MotivationPermissions.recommendationPublish)
        }
        if (financialChange(promo, config, currentRules, pairs, promo.medusaProductId, campaignBonus)) {
            administrativeScope.requireAllPermission(MotivationPermissions.configureBonus)
        }
    }

    private fun financialChange(promo: PromoEntity, config: PromoRulesConfigDto, currentRules: List<RuleEntity>,
        pairs: List<Pair<PairIntent, PromoRuleProductRefDto>>, proposedProductId: String?, campaignBonus: Int): Boolean {
        val paidBefore = currentRules.filter { it.bonus != 0 }.map {
            PaidOffer(PairIntent(it.type, it.trigger.kind, it.trigger.value), it.recommend, it.bonus)
        }.toSet()
        val paidAfter = pairs.flatMap { (pair, ref) ->
            val offers = listOfNotNull(proposedProductId?.let { it to (ref.bonus ?: campaignBonus) }) +
                ref.additionalRecommendations.filter {
                    it.medusaProductId != proposedProductId && it.medusaProductId != ref.medusaProductId
                }.distinctBy { it.medusaProductId }.take(4).map { it.medusaProductId to (it.bonus ?: campaignBonus) }
            offers.filter { it.second != 0 }.map { PaidOffer(pair, it.first, it.second) }
        }.toSet()
        val inheritanceChanged = pairs.any { (pair, ref) ->
            val authoredOffers = listOfNotNull(proposedProductId?.let { it to ref.bonus }) +
                ref.additionalRecommendations.filter {
                    it.medusaProductId != proposedProductId && it.medusaProductId != ref.medusaProductId
                }.distinctBy { it.medusaProductId }.take(4).map { it.medusaProductId to it.bonus }
            authoredOffers.any { (product, bonus) ->
                currentRules.any { old ->
                    old.type == pair.type && old.trigger.kind == pair.kind && old.trigger.value == pair.value && old.recommend == product &&
                        (if (old.card?.bonusInherited == null) bonus == null
                        else old.card?.bonusInherited != (bonus == null))
                }
            }
        }
        fun paidGoal(target: Int?, bonus: Int?) =
            if (target != null && target > 0 && bonus != null && bonus != 0) target to bonus else null
        // Old per-rule cards may contain paid goals predating the campaign-level source of truth.
        val cardGoalsBefore = currentRules.mapNotNull { rule -> rule.card?.goalBonus?.takeIf { it != 0 }?.let {
            Triple(PairIntent(rule.type, rule.trigger.kind, rule.trigger.value), rule.card?.goalTarget, it)
        } }.toSet()
        val cardGoalsAfter = pairs.mapNotNull { (pair, _) -> paidGoal(config.goalTarget, config.goalBonus)?.let {
            Triple(pair, it.first, it.second)
        } }.toSet()
        return paidBefore != paidAfter || inheritanceChanged || cardGoalsBefore != cardGoalsAfter ||
            paidGoal(promo.goalTarget, promo.goalBonus) != paidGoal(config.goalTarget, config.goalBonus)
    }

    private fun loadPromo(promoId: String, forUpdate: Boolean = false): PromoEntity =
        (if (forUpdate) promoRepository.findForUpdate(promoId) else promoRepository.findById(promoId)).orElseThrow {
            AppException(ErrorCode.NOT_FOUND, "Promo $promoId not found", HttpStatus.NOT_FOUND)
        }

    /**
     * Карточка кассы для ПАРЫ: поля берутся из самой пары, пустые — из общего config-дефолта.
     * Если в итоге нечего показывать (всё пусто) → null (карточки на кассе не будет).
     */
    private fun cardFor(
        ref: PromoRuleProductRefDto,
        config: PromoRulesConfigDto,
        offerRank: Int? = null,
        bonusInherited: Boolean? = null,
    ): RuleCard? {
        val partner = (ref.partnerLabel ?: config.partnerLabel)?.takeIf { it.isNotBlank() }
        val rows = ref.comparison.ifEmpty { config.comparison }
        val comparison = rows.map {
            RuleComparisonRow(it.label, it.triggerValue, it.recommendValue, it.recommendHighlight)
        }
        // Цель — на уровне кампании, денормализуется в card для POSM-кассы.
        // «Всё-или-ничего»: без target>0 цель не пишем (касса всё равно её не покажет).
        val goalTarget = config.goalTarget?.takeIf { it > 0 }
        val goalLabel = if (goalTarget != null) config.goalLabel?.takeIf { it.isNotBlank() } else null
        val goalBonus = if (goalTarget != null) config.goalBonus else null
        // Намерение по статусу пары храним только когда «черновик» (false); активная — дефолт.
        val pairDraft = !ref.active
        val hasAny = partner != null || comparison.isNotEmpty() || goalLabel != null ||
            goalTarget != null || goalBonus != null || pairDraft || offerRank != null || bonusInherited != null
        return if (hasAny) {
            RuleCard(
                partnerLabel = partner,
                comparison = comparison,
                goalLabel = goalLabel,
                goalTarget = goalTarget,
                goalBonus = goalBonus,
                offerRank = offerRank,
                pairActive = if (pairDraft) false else null,
                bonusInherited = bonusInherited,
            )
        } else {
            null
        }
    }

    /**
     * Восстанавливает per-pair поля карточки в ref из правила (для view/GET).
     * Цель НЕ восстанавливаем в ref — она на уровне кампании (см. view()).
     * Статус пары берём из намерения (card.pairActive), а не из эффективного rule.status
     * (тот мог стать draft из-за paused/draft кампании) — чтобы тоггл не «сбрасывался».
     */
    private fun reconstructRef(base: PromoRuleProductRefDto, rule: RuleEntity): PromoRuleProductRefDto {
        val card = rule.card
        return base.copy(
            script = rule.script,
            advantages = rule.advantages,
            partnerLabel = card?.partnerLabel,
            comparison = card?.comparison.orEmpty().map {
                PromoComparisonRowDto(it.label, it.triggerValue, it.recommendValue, it.recommendHighlight)
            },
            active = card?.pairActive != false,
        )
    }

    /**
     * В БД одна строка rules = один предложенный препарат. Для админки собираем строки
     * с одинаковым trigger обратно в одну пару: товар кампании + до четырёх альтернатив.
     * Старый cross-sell (trigger=promoted, recommend=companion) нормализуем без потери данных.
     */
    private fun reconstructGroups(
        rules: List<RuleEntity>,
        promotedId: String?,
        legacyCrossSell: Boolean,
    ): List<PromoRuleProductRefDto> {
        data class Normalized(
            val triggerKind: String,
            val triggerValue: String,
            val triggerLabel: String?,
            val recommendId: String,
            val rule: RuleEntity,
        )

        val normalized = rules.flatMap { rule ->
            val triggerValues = when (rule.trigger.kind) {
                "product" -> listOfNotNull(rule.trigger.value as? String)
                "product_any" -> (rule.trigger.value as? List<*>)
                    ?.mapNotNull { it as? String }
                    .orEmpty()
                "mnn", "acc_group", "acc_subgroup", "acc_mnn" ->
                    listOfNotNull(rule.trigger.value as? String)
                else -> emptyList()
            }
            triggerValues.distinct().map { rawTrigger ->
                if (legacyCrossSell && promotedId != null &&
                    rule.trigger.kind in setOf("product", "product_any") && rawTrigger == promotedId
                ) {
                    Normalized("product", rule.recommend, null, promotedId, rule)
                } else {
                    val kind = if (rule.trigger.kind == "product_any") "product" else rule.trigger.kind
                    Normalized(kind, rawTrigger, rule.trigger.label, rule.recommend, rule)
                }
            }
        }

        return normalized.groupBy { it.triggerKind to it.triggerValue }.map { (key, group) ->
            val (kind, triggerValue) = key
            val ordered = group.sortedWith(
                compareBy<Normalized> { it.rule.card?.offerRank ?: Int.MAX_VALUE }
                    .thenBy { it.rule.id },
            )
            val first = (ordered.firstOrNull { it.recommendId == promotedId } ?: ordered.first()).rule
            val base = if (kind == "product") {
                productRef(triggerValue)
                    ?: PromoRuleProductRefDto(medusaProductId = triggerValue, name = triggerValue)
            } else {
                val label = group.firstNotNullOfOrNull { it.triggerLabel } ?: triggerValue
                PromoRuleProductRefDto(
                    medusaProductId = "",
                    triggerKind = kind,
                    triggerValue = triggerValue,
                    triggerLabel = label,
                    name = label,
                )
            }
            val extras = ordered.asSequence()
                .filter { it.recommendId != promotedId }
                .distinctBy { it.recommendId }
                .mapNotNull { offer ->
                    offerRef(offer.recommendId)?.copy(bonus = authoredBonus(offer.rule))
                }
                .take(4)
                .toList()
            reconstructRef(base, first).copy(bonus = authoredBonus(first), additionalRecommendations = extras)
        }
    }

    private fun authoredBonus(rule: RuleEntity): Int? = if (rule.card?.bonusInherited == true) null else rule.bonus

    /** The stable key determines membership; client-provided labels never do. */
    private fun triggerFor(ref: PromoRuleProductRefDto): RuleTrigger = when (ref.triggerKind) {
        "product" -> {
            val id = ref.medusaProductId.trim()
            if (id.isBlank() || !ref.triggerValue.isNullOrBlank()) {
                invalidTrigger("Exact-product trigger requires medusaProductId and no triggerValue")
            }
            RuleTrigger(kind = "product", value = id)
        }
        "mnn" -> {
            // Legacy campaign rules may use this kind; new ACC authoring uses acc_mnn.
            val value = ref.triggerValue?.trim().orEmpty()
            if (value.isBlank() || ref.medusaProductId.isNotBlank()) invalidTrigger("Invalid MNN trigger")
            RuleTrigger(kind = "mnn", value = value, label = ref.triggerLabel?.trim()?.takeIf(String::isNotBlank))
        }
        "acc_group", "acc_subgroup", "acc_mnn" -> {
            if (ref.medusaProductId.isNotBlank()) invalidTrigger("ACC trigger cannot include medusaProductId")
            val kind = AccScopeKind.parse(ref.triggerKind)
            val selected = accCatalogTaxonomy.requireActiveOption(kind, ref.triggerValue.orEmpty())
            val label = selected.parentLabel?.let { "$it / ${selected.label}" } ?: selected.label
            RuleTrigger(kind = kind.wire, value = selected.key, label = label.take(255))
        }
        else -> invalidTrigger("Unknown triggerKind=${ref.triggerKind}")
    }

    private fun invalidTrigger(message: String): Nothing = throw AppException(
        ErrorCode.VALIDATION_FAILED, message, HttpStatus.BAD_REQUEST,
    )

    private data class OfferRecommendation(val product: ProductEntity, val bonus: Int?)

    /** Основной товар кампании + дополнительные варианты, всего не более пяти. */
    private fun recommendationsFor(ref: PromoRuleProductRefDto, promoted: ProductEntity, replaceNullableIdentity: Boolean): List<OfferRecommendation> {
        val extras = ref.additionalRecommendations.asSequence()
            .filter { it.medusaProductId != promoted.id && it.medusaProductId != ref.medusaProductId }
            .distinctBy { it.medusaProductId }
            .take(4)
            .map { offer -> OfferRecommendation(upsertOfferProduct(offer, replaceNullableIdentity), offer.bonus) }
            .toList()
        return listOf(OfferRecommendation(promoted, ref.bonus)) + extras
    }

    /** Локальный товар под продвигаемый (id = medusaProductId; имя/цена из кампании/Medusa). */
    private fun upsertPromotedProduct(promo: PromoEntity, medusaId: String, replaceNullableIdentity: Boolean): ProductEntity {
        val existing = productRepository.findById(medusaId).orElse(null)
        val snapshot = medusaPriceService.snapshotOf(medusaId)
        val price = snapshot?.price?.toInt()
            ?: existing?.price?.takeIf { it > 0 }
            ?: promo.price.toInt()
        val p = existing ?: ProductEntity(id = medusaId)
        p.name = promo.productName.ifBlank { existing?.name?.ifBlank { null } ?: promo.title }
        p.brand = promo.brand.ifBlank { existing?.brand ?: "" }
        p.vendor = existing?.vendor?.ifBlank { null } ?: promo.brand
        p.mnn = existing?.mnn ?: ""
        p.price = price
        p.volume = existing?.volume ?: ""
        p.medusaProductId = medusaId
        val sourceCodes = snapshot?.let { listOfNotNull(it.barcode) + it.barcodes }.orEmpty()
        val freshIdentity = snapshot?.barcodeSourceComplete == true || sourceCodes.isNotEmpty()
        // One source snapshot supplies both price and identity. Editing an old campaign
        // must pick up current offered aliases without waiting for the hourly refresh.
        p.barcodes = ConfirmedBarcodeAliases.merge(
            source = if (freshIdentity) sourceCodes else promo.barcodes,
            primary = if (freshIdentity) snapshot?.barcode else promo.barcode,
            existing = if (replaceNullableIdentity) promo.barcodes + listOfNotNull(promo.barcode) else p.scanBarcodes(),
            sourceComplete = freshIdentity || promo.barcodeSourceComplete,
        )
        p.barcode = p.barcodes.firstOrNull()
        promo.barcodes = p.barcodes
        promo.barcode = p.barcode
        if (freshIdentity) promo.barcodeSourceComplete = true
        p.ipartId = promo.ipartId?.trim()?.takeIf { it.isNotBlank() }
            ?: existing?.ipartId.takeUnless { replaceNullableIdentity }
        return productRepository.save(p)
    }

    /** Локальный товар под выбранную в пикере позицию витрины (замена/компаньон). */
    private fun upsertProduct(ref: PromoRuleProductRefDto, replaceNullableIdentity: Boolean): ProductEntity {
        val id = ref.medusaProductId
        val existing = productRepository.findById(id).orElse(null)
        val snapshot = medusaPriceService.snapshotOf(id)
        val price = snapshot?.price?.toInt()
            ?: existing?.price?.takeIf { it > 0 }
            ?: ref.price
            ?: 0
        val p = existing ?: ProductEntity(id = id)
        p.name = ref.name.ifBlank { existing?.name?.ifBlank { null } ?: id }
        p.brand = ref.brand?.takeIf { it.isNotBlank() } ?: existing?.brand ?: ""
        p.vendor = existing?.vendor?.ifBlank { null } ?: (ref.brand ?: "")
        p.mnn = ref.mnn?.takeIf { it.isNotBlank() } ?: existing?.mnn ?: ""
        p.price = price
        p.volume = ref.volume?.takeIf { it.isNotBlank() } ?: existing?.volume ?: ""
        p.medusaProductId = id
        // Штрих-код заменяемого/компаньона: из ref (фронт), иначе из Medusa, иначе прежний.
        // Это ключ матчинга POSM-кассы для товара-триггера замены.
        val previousCodes = if (replaceNullableIdentity) ref.barcodes + listOfNotNull(ref.barcode) else p.scanBarcodes()
        p.barcodes = ConfirmedBarcodeAliases.merge(
            ref.barcodes, ref.barcode, snapshot?.let { listOfNotNull(it.barcode) + it.barcodes }.orEmpty(),
            previousCodes,
            sourceComplete = snapshot?.barcodeSourceComplete == true,
        )
        p.barcode = p.barcodes.firstOrNull()
        p.ipartId = ref.ipartId?.trim()?.takeIf { it.isNotBlank() }
            ?: existing?.ipartId.takeUnless { replaceNullableIdentity }
        return productRepository.save(p)
    }

    private fun upsertOfferProduct(ref: PromoOfferProductRefDto, replaceNullableIdentity: Boolean): ProductEntity {
        val id = ref.medusaProductId
        val existing = productRepository.findById(id).orElse(null)
        val snapshot = medusaPriceService.snapshotOf(id)
        val price = snapshot?.price?.toInt()
            ?: existing?.price?.takeIf { it > 0 }
            ?: ref.price
            ?: 0
        val p = existing ?: ProductEntity(id = id)
        p.name = ref.name.ifBlank { existing?.name?.ifBlank { null } ?: id }
        p.brand = ref.brand?.takeIf { it.isNotBlank() } ?: existing?.brand ?: ""
        p.vendor = existing?.vendor?.ifBlank { null } ?: (ref.brand ?: "")
        p.mnn = ref.mnn?.takeIf { it.isNotBlank() } ?: existing?.mnn ?: ""
        p.price = price
        p.volume = ref.volume?.takeIf { it.isNotBlank() } ?: existing?.volume ?: ""
        p.medusaProductId = id
        val previousCodes = if (replaceNullableIdentity) ref.barcodes + listOfNotNull(ref.barcode) else p.scanBarcodes()
        p.barcodes = ConfirmedBarcodeAliases.merge(
            ref.barcodes, ref.barcode, snapshot?.let { listOfNotNull(it.barcode) + it.barcodes }.orEmpty(),
            previousCodes,
            sourceComplete = snapshot?.barcodeSourceComplete == true,
        )
        p.barcode = p.barcodes.firstOrNull()
        p.ipartId = ref.ipartId?.trim()?.takeIf { it.isNotBlank() }
            ?: existing?.ipartId.takeUnless { replaceNullableIdentity }
        return productRepository.save(p)
    }

    private fun productRef(productId: String): PromoRuleProductRefDto? {
        val p = productRepository.findById(productId).orElse(null) ?: return null
        return PromoRuleProductRefDto(
            medusaProductId = p.medusaProductId ?: p.id,
            name = p.name,
            brand = p.brand.takeIf { it.isNotBlank() },
            mnn = p.mnn.takeIf { it.isNotBlank() },
            volume = p.volume.takeIf { it.isNotBlank() },
            barcode = p.barcode?.takeIf { it.isNotBlank() },
            barcodes = p.scanBarcodes(),
            ipartId = p.ipartId?.takeIf { it.isNotBlank() },
            price = p.price.takeIf { it > 0 },
        )
    }

    private fun offerRef(productId: String): PromoOfferProductRefDto? {
        val p = productRepository.findById(productId).orElse(null) ?: return null
        return PromoOfferProductRefDto(
            medusaProductId = p.medusaProductId ?: p.id,
            name = p.name,
            brand = p.brand.takeIf { it.isNotBlank() },
            mnn = p.mnn.takeIf { it.isNotBlank() },
            volume = p.volume.takeIf { it.isNotBlank() },
            barcode = p.barcode?.takeIf { it.isNotBlank() },
            barcodes = p.scanBarcodes(),
            ipartId = p.ipartId?.takeIf { it.isNotBlank() },
            price = p.price.takeIf { it > 0 },
        )
    }

    private fun generateRuleId(type: RuleType): String {
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val typeMark = if (type == RuleType.crosssell) "x" else "s"
        return "r_${typeMark}_$suffix"
    }
}
