package kz.epharm.posm.service

import kz.epharm.catalog.entity.ProductEntity
import kz.epharm.catalog.repository.ProductRepository
import kz.epharm.posm.dto.CartItemDto
import kz.epharm.posm.repository.ProductPosCodeRepository
import kz.epharm.promo.entity.PromoStatus
import kz.epharm.promo.repository.PromoRepository
import kz.epharm.rules.entity.RuleEntity
import kz.epharm.rules.entity.RuleStatus
import kz.epharm.rules.entity.RuleTrigger
import kz.epharm.rules.repository.RuleRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Результат матчинга правила к корзине: само правило + что в корзине его триггернуло
 * + продукт-рекомендация (резолвится к каталогу для имени/цены).
 */
data class RuleMatch(
    val rule: RuleEntity,
    val triggerSku: String?,
    val triggerName: String?,
    val triggerProduct: ProductEntity?, // товар-триггер из каталога (для объёма/цены в карточке)
    val recommend: ProductEntity,
)

/**
 * Конфликт правил — почему замену/кросс-селл показать нельзя (T2).
 *  - ambiguous_substitution — на один товар настроено несколько РАЗНЫХ замен (какую выбрать?);
 *  - contradiction — один и тот же товар одновременно и заменяется, и допродаётся (противоречие).
 */
data class RuleConflict(
    val kind: String,
    val triggerSku: String?,
    val triggerName: String?,
    val reason: String,
    val ruleIds: List<String>,
)

/** Итог матчинга: что показать (matches) + о каких конфликтах сообщить фармацевту (conflicts). */
data class RuleMatchResult(
    val matches: List<RuleMatch>,
    val conflicts: List<RuleConflict>,
)

/**
 * Чистый матчер правил (ТЗ §4). Без побочных эффектов — подбор, детект конфликтов, ранжирование:
 *
 *   1. substitution: trigger матчит товар X в корзине, recommend(Y) ещё НЕ в корзине.
 *   2. crosssell:    trigger матчит корзину (A), recommend(B) ещё НЕ в корзине.
 *   3. КОНФЛИКТЫ (T2): неоднозначная замена / противоречие замена↔кросс-селл — такие правила
 *      НЕ показываем, а возвращаем как conflicts (касса покажет «замена/кросс-селл невозможны»).
 *   4. порядок выживших: сначала ВСЕ substitution (бонус DESC), затем crosssell (бонус DESC).
 *   5. dedup по recommend-товару (первый победил).
 *
 * Фильтр «не показывать отклонённое в этом чеке» и лимит top-2 — в RecommendationService.
 */
@Service
class RulesEngineService(
    private val ruleRepository: RuleRepository,
    private val productRepository: ProductRepository,
    private val productPosCodeRepository: ProductPosCodeRepository,
    private val promoRepository: PromoRepository,
) {

    @Transactional(readOnly = true)
    fun match(cart: List<CartItemDto>): RuleMatchResult {
        // Нормализуем артикулы: числовой код кассы Стандарт-Н (iPartID) → наш productId
        // через product_pos_codes; если это уже productId (или код неизвестен) — оставляем как есть.
        val cartSkus = cart.map { resolveSku(it.sku) }.toSet()
        if (cartSkus.isEmpty()) return RuleMatchResult(emptyList(), emptyList())

        val cartProducts: Map<String, ProductEntity> =
            productRepository.findAllById(cartSkus).associateBy { it.id }

        val activeRules = ruleRepository.findAllByStatusRawOrderByUpdatedAtDesc(RuleStatus.active.name)
        // Кампания — мастер-выключатель: правило из неактивной кампании НЕ показываем,
        // даже если оно осталось active в БД (смена статуса кампании не пересохраняет правила).
        // Правила без promoId (legacy ручные) проходят как есть.
        val promoIds = activeRules.mapNotNull { it.promoId }.toSet()
        val activePromoIds =
            if (promoIds.isEmpty()) emptySet()
            else promoRepository.findAllById(promoIds)
                .filter { it.status == PromoStatus.active }
                .map { it.id }
                .toSet()
        val active = activeRules.filter { it.promoId == null || it.promoId in activePromoIds }

        val raw = active.mapNotNull { rule ->
            val triggerSku = matchTrigger(rule.trigger, cartSkus, cartProducts) ?: return@mapNotNull null
            // recommend не должен уже лежать в корзине
            if (rule.recommend in cartSkus) return@mapNotNull null
            val recProduct = productRepository.findById(rule.recommend).orElse(null) ?: return@mapNotNull null
            RuleMatch(
                rule = rule,
                triggerSku = triggerSku,
                triggerName = cartProducts[triggerSku]?.name,
                triggerProduct = cartProducts[triggerSku],
                recommend = recProduct,
            )
        }

        // ── Детект конфликтов ────────────────────────────────────────────────
        val conflicts = mutableListOf<RuleConflict>()
        val suppressed = mutableSetOf<String>() // id правил, которые из-за конфликта не показываем

        // A) Неоднозначная замена: один товар-триггер → несколько РАЗНЫХ замен.
        raw.filter { it.rule.type.name == "substitution" && it.triggerSku != null }
            .groupBy { it.triggerSku }
            .filterValues { ms -> ms.map { it.recommend.id }.distinct().size >= 2 }
            .forEach { (sku, ms) ->
                conflicts += RuleConflict(
                    kind = "ambiguous_substitution",
                    triggerSku = sku,
                    triggerName = ms.first().triggerName,
                    reason = "Замена невозможна: на товар «${ms.first().triggerName ?: sku}» " +
                        "настроено несколько разных замен",
                    ruleIds = ms.map { it.rule.id },
                )
                suppressed += ms.map { it.rule.id }
            }

        // B) Противоречие: одна и та же пара (триггер → рекомендация) и как замена, и как кросс-селл.
        raw.groupBy { it.triggerSku to it.recommend.id }
            .filterValues { ms -> ms.map { it.rule.type.name }.distinct().size >= 2 }
            .forEach { (pair, ms) ->
                conflicts += RuleConflict(
                    kind = "contradiction",
                    triggerSku = pair.first,
                    triggerName = ms.first().triggerName,
                    reason = "Кросс-селл/замена невозможны: товар «${ms.first().triggerName ?: pair.first}» " +
                        "одновременно заменяется и допродаётся",
                    ruleIds = ms.map { it.rule.id },
                )
                suppressed += ms.map { it.rule.id }
            }

        val survivors = raw
            .filterNot { it.rule.id in suppressed }
            .sortedWith(
                compareBy(
                    { if (it.rule.type.name == "substitution") 0 else 1 }, // substitution раньше crosssell
                    { -it.rule.bonus },                                     // больший бонус выше
                ),
            )
            .distinctBy { it.recommend.id }

        return RuleMatchResult(survivors, conflicts)
    }

    /** Числовой код кассы → productId (если есть в product_pos_codes), иначе вход без изменений. */
    private fun resolveSku(raw: String): String {
        val posCode = raw.toLongOrNull() ?: return raw
        return productPosCodeRepository.findById(posCode).map { it.productId }.orElse(raw)
    }

    /**
     * Возвращает sku из корзины, которое триггернуло правило, или null если не сработало.
     *   kind=product      → trigger.value (productId) лежит в корзине
     *   kind=product_any  → любой из trigger.value лежит в корзине
     *   kind=mnn          → в корзине есть товар с этим МНН (кроме exclude)
     */
    private fun matchTrigger(
        trigger: RuleTrigger,
        cartSkus: Set<String>,
        cartProducts: Map<String, ProductEntity>,
    ): String? = when (trigger.kind) {
        "product" -> (trigger.value as? String)?.takeIf { it in cartSkus }
        "product_any" -> (trigger.value as? List<*>)
            ?.mapNotNull { it as? String }
            ?.firstOrNull { it in cartSkus }
        "mnn" -> {
            val mnn = trigger.value as? String
            val excluded = trigger.exclude ?: emptyList()
            if (mnn == null) null
            else cartProducts.values.firstOrNull { it.mnn == mnn && it.id !in excluded }?.id
        }
        else -> null
    }
}
