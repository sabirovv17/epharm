package kz.epharm.promo.service

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
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Авторинг правил замены/кросс-селла ИЗ кампании (T2).
 *
 * Кампания продвигает один товар (promos.medusaProductId). Админ в её карточке выбирает товары,
 * которые уже есть/могут быть в чеке и должны привести к продаже продвигаемого:
 *  - replacements — какие товары ЗАМЕНЯЕМ на продвигаемый (substitution-правила),
 *  - crossSells   — с какими товарами в чеке ПРЕДЛАГАЕМ продвигаемый (crosssell-правила),
 *  - и весь текст фармацевту (script/advantages/карточка-сравнение/цель) — общий для всех правил.
 *
 * `replace()` перезаписывает все правила кампании. Под каждый выбранный товар витрины
 * апсертим локальный [ProductEntity] (id = medusaProductId), чтобы FK rules.recommend и
 * POSM-матчер работали, а цена тянулась из Medusa (и обновлялась планировщиком).
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
) {

    @Transactional(readOnly = true)
    fun view(promoId: String): PromoRulesViewDto {
        val promo = loadPromo(promoId)
        val rules = ruleRepository.findAllByPromoIdOrderByUpdatedAtDesc(promoId)
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

    /**
     * Перезаписывает правила кампании из конфигурации. Старые правила кампании удаляются,
     * создаются новые. Статус правил повторяет кампанию (active → active, иначе draft).
     */
    @Transactional
    fun replace(promoId: String, config: PromoRulesConfigDto, createdBy: String): PromoRulesViewDto {
        val promo = loadPromo(promoId)
        val promotedMedusaId = promo.medusaProductId
            ?: throw AppException(
                ErrorCode.VALIDATION_FAILED,
                "Сначала привяжите товар к кампании, затем настраивайте замены/кросс-селл",
                HttpStatus.BAD_REQUEST,
            )
        // Validate all scopes before deleting existing campaign rules.
        val replacementTriggers = config.replacements.map { it to triggerFor(it) }
        val crossSellTriggers = config.crossSells.map { it to triggerFor(it) }
        // Локальный товар-продвигаемый (recommend для замен и кросс-селла).
        val promoted = upsertPromotedProduct(promo, promotedMedusaId)

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
        val campaignBonus = promo.pharmacistBonus.toInt()
        // Кампания — мастер-выключатель: правило active только если И кампания active,
        // И сама пара active (ref.active). Иначе — draft.
        val campaignActive = promo.status == PromoStatus.active
        fun effectiveStatus(ref: PromoRuleProductRefDto): RuleStatus =
            if (campaignActive && ref.active) RuleStatus.active else RuleStatus.draft

        // Replace-семантика: удаляем прежние правила этой кампании.
        ruleRepository.deleteByPromoId(promoId)

        val created = mutableListOf<RuleEntity>()

        // Замены: триггер — заменяемый товар, рекомендация — продвигаемый.
        replacementTriggers
            .filter { (_, trigger) -> trigger.kind != "product" || trigger.value != promotedMedusaId }
            .distinctBy { (_, trigger) -> trigger.kind to trigger.value }
            .forEach { (ref, trigger) ->
                if (trigger.kind == "product") upsertProduct(ref)
                recommendationsFor(ref, promoted).forEachIndexed { offerRank, offer ->
                    created += RuleEntity(
                        id = generateRuleId(RuleType.substitution),
                        recommend = offer.product.id,
                        bonus = offer.bonus ?: campaignBonus,
                        // Поля карточки кассы — per-pair; пусто → общий дефолт из config.
                        script = ref.script.ifBlank { config.script },
                        advantages = ref.advantages.ifEmpty { config.advantages },
                        card = cardFor(ref, config, offerRank),
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
                if (trigger.kind == "product") upsertProduct(ref)
                recommendationsFor(ref, promoted).forEachIndexed { offerRank, offer ->
                    created += RuleEntity(
                        id = generateRuleId(RuleType.crosssell),
                        recommend = offer.product.id,
                        bonus = offer.bonus ?: campaignBonus,
                        // Поля карточки кассы — per-pair; пусто → общий дефолт из config.
                        script = ref.script.ifBlank { config.script },
                        advantages = ref.advantages.ifEmpty { config.advantages },
                        card = cardFor(ref, config, offerRank),
                        trigger = trigger,
                        createdBy = createdBy,
                    ).also {
                        it.type = RuleType.crosssell
                        it.status = effectiveStatus(ref)
                        it.promoId = promoId
                    }
                }
            }

        ruleRepository.saveAll(created)
        return view(promoId)
    }

    // ── Internals ─────────────────────────────────────────────────────────

    private fun loadPromo(promoId: String): PromoEntity =
        promoRepository.findById(promoId).orElseThrow {
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
            goalTarget != null || goalBonus != null || pairDraft || offerRank != null
        return if (hasAny) {
            RuleCard(
                partnerLabel = partner,
                comparison = comparison,
                goalLabel = goalLabel,
                goalTarget = goalTarget,
                goalBonus = goalBonus,
                offerRank = offerRank,
                pairActive = if (pairDraft) false else null,
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
            val first = ordered.first().rule
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
                    offerRef(offer.recommendId)?.copy(bonus = offer.rule.bonus)
                }
                .take(4)
                .toList()
            reconstructRef(base, first).copy(bonus = first.bonus, additionalRecommendations = extras)
        }
    }

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
    private fun recommendationsFor(ref: PromoRuleProductRefDto, promoted: ProductEntity): List<OfferRecommendation> {
        val extras = ref.additionalRecommendations.asSequence()
            .filter { it.medusaProductId != promoted.id && it.medusaProductId != ref.medusaProductId }
            .distinctBy { it.medusaProductId }
            .take(4)
            .map { offer -> OfferRecommendation(upsertOfferProduct(offer), offer.bonus) }
            .toList()
        return listOf(OfferRecommendation(promoted, ref.bonus)) + extras
    }

    /** Локальный товар под продвигаемый (id = medusaProductId; имя/цена из кампании/Medusa). */
    private fun upsertPromotedProduct(promo: PromoEntity, medusaId: String): ProductEntity {
        val existing = productRepository.findById(medusaId).orElse(null)
        val price = medusaPriceService.priceOf(medusaId)?.toInt()
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
        // Штрих-код продвигаемого — из кампании (источник истины для матчинга кассы),
        // иначе сохраняем прежний.
        p.barcode = promo.barcode?.trim()?.takeIf { it.isNotBlank() } ?: existing?.barcode
        p.ipartId = promo.ipartId?.trim()?.takeIf { it.isNotBlank() } ?: existing?.ipartId
        return productRepository.save(p)
    }

    /** Локальный товар под выбранную в пикере позицию витрины (замена/компаньон). */
    private fun upsertProduct(ref: PromoRuleProductRefDto): ProductEntity {
        val id = ref.medusaProductId
        val existing = productRepository.findById(id).orElse(null)
        val price = medusaPriceService.priceOf(id)?.toInt()
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
        p.barcode = ref.barcode?.trim()?.takeIf { it.isNotBlank() }
            ?: medusaPriceService.snapshotOf(id)?.barcode?.trim()?.takeIf { it.isNotBlank() }
            ?: existing?.barcode
        p.ipartId = ref.ipartId?.trim()?.takeIf { it.isNotBlank() } ?: existing?.ipartId
        return productRepository.save(p)
    }

    private fun upsertOfferProduct(ref: PromoOfferProductRefDto): ProductEntity {
        val id = ref.medusaProductId
        val existing = productRepository.findById(id).orElse(null)
        val price = medusaPriceService.priceOf(id)?.toInt()
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
        p.barcode = ref.barcode?.trim()?.takeIf { it.isNotBlank() }
            ?: medusaPriceService.snapshotOf(id)?.barcode?.trim()?.takeIf { it.isNotBlank() }
            ?: existing?.barcode
        p.ipartId = ref.ipartId?.trim()?.takeIf { it.isNotBlank() } ?: existing?.ipartId
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
            ipartId = p.ipartId?.takeIf { it.isNotBlank() },
            price = p.price.takeIf { it > 0 },
        )
    }

    private fun generateRuleId(type: RuleType): String {
        val short = UUID.randomUUID().toString().substring(0, 8)
        val typeMark = if (type == RuleType.crosssell) "x" else "s"
        return "r_${typeMark}_$short"
    }
}
