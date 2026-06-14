package kz.epharm.promo.service

import kz.epharm.catalog.entity.ProductEntity
import kz.epharm.catalog.repository.ProductRepository
import kz.epharm.medusa.service.MedusaPriceService
import kz.epharm.promo.dto.PromoComparisonRowDto
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
 * Кампания продвигает один товар (promos.medusaProductId). Админ в её карточке выбирает:
 *  - какие товары ЗАМЕНЯЕМ на продвигаемый (substitution-правила),
 *  - с какими товарами ПРЕДЛАГАЕМ продвигаемый (crosssell-правила),
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
) {

    @Transactional(readOnly = true)
    fun view(promoId: String): PromoRulesViewDto {
        loadPromo(promoId)
        val rules = ruleRepository.findAllByPromoIdOrderByUpdatedAtDesc(promoId)
        val subs = rules.filter { it.type == RuleType.substitution }
        val cross = rules.filter { it.type == RuleType.crosssell }

        // Восстанавливаем ссылки на товары из локального каталога.
        val replacements = subs.mapNotNull { r -> (r.trigger.value as? String)?.let { productRef(it) } }
        val crossSells = cross.mapNotNull { r -> productRef(r.recommend) }

        // Текст — общий, берём с первого правила (генерим одинаковым для всех).
        val sample = rules.firstOrNull()
        val card = sample?.card
        val config = PromoRulesConfigDto(
            replacements = replacements,
            crossSells = crossSells,
            script = sample?.script ?: "",
            advantages = sample?.advantages ?: emptyList(),
            partnerLabel = card?.partnerLabel,
            comparison = card?.comparison.orEmpty().map {
                PromoComparisonRowDto(it.label, it.triggerValue, it.recommendValue, it.recommendHighlight)
            },
            goalLabel = card?.goalLabel,
            goalTarget = card?.goalTarget,
            goalBonus = card?.goalBonus,
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
        // Локальный товар-продвигаемый (recommend для замен, trigger для кросс-селла).
        val promoted = upsertPromotedProduct(promo, promotedMedusaId)

        val card = buildCard(config)
        val bonus = promo.pharmacistBonus.toInt()
        val status = if (promo.status == PromoStatus.active) RuleStatus.active else RuleStatus.draft

        // Replace-семантика: удаляем прежние правила этой кампании.
        ruleRepository.deleteByPromoId(promoId)

        val created = mutableListOf<RuleEntity>()

        // Замены: триггер — заменяемый товар, рекомендация — продвигаемый.
        config.replacements
            .filter { it.medusaProductId != promotedMedusaId }
            .distinctBy { it.medusaProductId }
            .forEach { ref ->
                val trig = upsertProduct(ref)
                created += RuleEntity(
                    id = generateRuleId(RuleType.substitution),
                    recommend = promoted.id,
                    bonus = bonus,
                    script = config.script,
                    advantages = config.advantages,
                    card = card,
                    trigger = RuleTrigger(kind = "product", value = trig.id),
                    createdBy = createdBy,
                ).also { it.type = RuleType.substitution; it.status = status; it.promoId = promoId }
            }

        // Кросс-селл: триггер — продвигаемый товар, рекомендация — товар-компаньон.
        config.crossSells
            .filter { it.medusaProductId != promotedMedusaId }
            .distinctBy { it.medusaProductId }
            .forEach { ref ->
                val companion = upsertProduct(ref)
                created += RuleEntity(
                    id = generateRuleId(RuleType.crosssell),
                    recommend = companion.id,
                    bonus = bonus,
                    script = config.script,
                    advantages = config.advantages,
                    card = card,
                    trigger = RuleTrigger(kind = "product", value = promoted.id),
                    createdBy = createdBy,
                ).also { it.type = RuleType.crosssell; it.status = status; it.promoId = promoId }
            }

        ruleRepository.saveAll(created)
        return view(promoId)
    }

    // ── Internals ─────────────────────────────────────────────────────────

    private fun loadPromo(promoId: String): PromoEntity =
        promoRepository.findById(promoId).orElseThrow {
            AppException(ErrorCode.NOT_FOUND, "Promo $promoId not found", HttpStatus.NOT_FOUND)
        }

    private fun buildCard(config: PromoRulesConfigDto): RuleCard? {
        val partner = config.partnerLabel?.takeIf { it.isNotBlank() }
        val comparison = config.comparison.map {
            RuleComparisonRow(it.label, it.triggerValue, it.recommendValue, it.recommendHighlight)
        }
        val goalLabel = config.goalLabel?.takeIf { it.isNotBlank() }
        val hasAny = partner != null || comparison.isNotEmpty() || goalLabel != null ||
            config.goalTarget != null || config.goalBonus != null
        return if (hasAny) {
            RuleCard(
                partnerLabel = partner,
                comparison = comparison,
                goalLabel = goalLabel,
                goalTarget = config.goalTarget,
                goalBonus = config.goalBonus,
            )
        } else {
            null
        }
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
            price = p.price.takeIf { it > 0 },
        )
    }

    private fun generateRuleId(type: RuleType): String {
        val short = UUID.randomUUID().toString().substring(0, 8)
        val typeMark = if (type == RuleType.crosssell) "x" else "s"
        return "r_${typeMark}_$short"
    }
}
