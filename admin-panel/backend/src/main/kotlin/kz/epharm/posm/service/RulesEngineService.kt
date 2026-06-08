package kz.epharm.posm.service

import kz.epharm.catalog.entity.ProductEntity
import kz.epharm.catalog.repository.ProductRepository
import kz.epharm.posm.dto.CartItemDto
import kz.epharm.posm.repository.ProductPosCodeRepository
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
 * Чистый матчер правил (ТЗ §4). Без побочных эффектов — только подбор и ранжирование:
 *
 *   1. substitution: trigger матчит товар X в корзине, recommend(Y) ещё НЕ в корзине.
 *   2. crosssell:    trigger матчит корзину (A), recommend(B) ещё НЕ в корзине.
 *   3. порядок: сначала ВСЕ substitution (бонус DESC), затем crosssell (бонус DESC).
 *   4. dedup по recommend-товару (первый победил).
 *
 * Фильтр «не показывать отклонённое в этом чеке» и лимит top-2 — в RecommendationService
 * (нужна сессия из БД). Здесь — только ранжированный список кандидатов.
 */
@Service
class RulesEngineService(
    private val ruleRepository: RuleRepository,
    private val productRepository: ProductRepository,
    private val productPosCodeRepository: ProductPosCodeRepository,
) {

    @Transactional(readOnly = true)
    fun match(cart: List<CartItemDto>): List<RuleMatch> {
        // Нормализуем артикулы: числовой код кассы Стандарт-Н (iPartID) → наш productId
        // через product_pos_codes; если это уже productId (или код неизвестен) — оставляем как есть.
        val cartSkus = cart.map { resolveSku(it.sku) }.toSet()
        if (cartSkus.isEmpty()) return emptyList()

        val cartProducts: Map<String, ProductEntity> =
            productRepository.findAllById(cartSkus).associateBy { it.id }

        val active = ruleRepository.findAllByStatusRawOrderByUpdatedAtDesc(RuleStatus.active.name)

        val matches = active.mapNotNull { rule ->
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

        return matches
            .sortedWith(
                compareBy(
                    { if (it.rule.type.name == "substitution") 0 else 1 }, // substitution раньше crosssell
                    { -it.rule.bonus },                                     // больший бонус выше
                ),
            )
            .distinctBy { it.recommend.id }
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
