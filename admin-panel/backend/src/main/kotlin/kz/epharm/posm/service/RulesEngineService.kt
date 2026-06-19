package kz.epharm.posm.service

import kz.epharm.catalog.entity.ProductEntity
import kz.epharm.catalog.repository.ProductRepository
import kz.epharm.posm.dto.CartItemDto
import kz.epharm.promo.entity.PromoStatus
import kz.epharm.promo.repository.PromoRepository
import kz.epharm.rules.entity.RuleEntity
import kz.epharm.rules.entity.RuleStatus
import kz.epharm.rules.entity.RuleTrigger
import kz.epharm.rules.repository.RuleRepository
import org.slf4j.LoggerFactory
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
 * РЕЗОЛВ корзины → наш productId (по ТЗ штрих-кода): касса Стандарт-Н шлёт позиции с EAN-13
 * (barcode) и/или названием (name). Матчим:
 *   (1) по штрих-коду (barcode) — ProductEntity.barcode == EAN-13 (первичный ключ);
 *   (2) иначе по имени (name) — нормализованное совпадение с ProductEntity.name (fallback,
 *       пока касса не шлёт штрих-код);
 *   (3) иначе позиция не резолвится (в матчинге не участвует).
 *
 * Фильтр «не показывать отклонённое в этом чеке» и лимит top-2 — в RecommendationService.
 */
@Service
class RulesEngineService(
    private val ruleRepository: RuleRepository,
    private val productRepository: ProductRepository,
    private val promoRepository: PromoRepository,
) {
    private val log = LoggerFactory.getLogger(RulesEngineService::class.java)

    @Transactional(readOnly = true)
    fun match(cart: List<CartItemDto>): RuleMatchResult {
        // Резолвим позиции корзины в наши productId (штрих-код → имя), собираем уникальные товары.
        val cartProducts: Map<String, ProductEntity> = resolveCart(cart)
        val cartSkus: Set<String> = cartProducts.keys
        if (cartSkus.isEmpty()) return RuleMatchResult(emptyList(), emptyList())

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

    /**
     * Резолв корзины кассы → наши товары (id → ProductEntity).
     *   (1) по штрих-коду (barcode, trim, непустой) — точное совпадение ProductEntity.barcode;
     *   (2) для позиций без barcode (или не нашедшихся по barcode) — fallback по нормализованному
     *       имени (только если такие позиции есть, чтобы не грузить весь каталог зря).
     * Возврат — уникальные товары (по productId); один товар, даже если в корзине дважды, один раз.
     *
     * Коллизии (один штрих-код / одно нормализованное имя у ≠ товаров) НЕ разрешаем «наугад»:
     * такой ключ считаем неоднозначным и НЕ матчим (с warn в лог) — лучше не показать рекомендацию,
     * чем показать рекомендацию чужого товара. Уникальность гарантируется только данными PIM.
     */
    private fun resolveCart(cart: List<CartItemDto>): Map<String, ProductEntity> {
        val byBarcode = barcodeIndex(cart)
        val byName = nameIndex(cart, byBarcode)
        val resolved = LinkedHashMap<String, ProductEntity>()
        cart.forEach { item ->
            resolveOne(item, byBarcode, byName)?.let { resolved.putIfAbsent(it.id, it) }
        }
        return resolved
    }

    /**
     * Резолв позиций (штрих-код → имя) в наши productId — для сверки чека из лога кассы
     * (источник №1, /api/posm/sales). По каждой позиции в исходном порядке — productId или null
     * (не нашли / неоднозначно). Тот же коллизионно-устойчивый матчинг, что и в рекомендациях.
     */
    @Transactional(readOnly = true)
    fun resolveToProductIds(items: List<CartItemDto>): List<String?> {
        val byBarcode = barcodeIndex(items)
        val byName = nameIndex(items, byBarcode)
        return items.map { resolveOne(it, byBarcode, byName)?.id }
    }

    /** Индекс штрих-код → товар: только однозначные ключи (коллизия → warn + пропуск). */
    private fun barcodeIndex(cart: List<CartItemDto>): Map<String, ProductEntity> {
        val barcodes = cart.mapNotNull { it.barcode?.trim()?.takeIf { b -> b.isNotEmpty() } }.distinct()
        if (barcodes.isEmpty()) return emptyMap()
        return productRepository.findAllByBarcodeIn(barcodes)
            .filter { !it.barcode.isNullOrBlank() }
            .groupBy { it.barcode!!.trim() }
            .mapNotNull { (bc, products) -> uniqueOrWarn(bc, products, "штрих-код")?.let { bc to it } }
            .toMap()
    }

    /**
     * Индекс нормализованное-имя → товар. Строим только если есть позиции без barcode-матча, но с
     * именем (чтобы не грузить каталог зря). Детерминированный порядок + защита от коллизий имён.
     */
    private fun nameIndex(cart: List<CartItemDto>, byBarcode: Map<String, ProductEntity>): Map<String, ProductEntity> {
        val needName = cart.any { item ->
            val bc = item.barcode?.trim()?.takeIf { it.isNotEmpty() }
            (bc == null || bc !in byBarcode) && !item.name.isNullOrBlank()
        }
        if (!needName) return emptyMap()
        return productRepository.findAllByOrderByNameAsc()
            .filter { it.name.isNotBlank() }
            .groupBy { normalizeName(it.name) }
            .mapNotNull { (norm, products) -> uniqueOrWarn(norm, products, "имя")?.let { norm to it } }
            .toMap()
    }

    /** Резолв одной позиции: сначала штрих-код, потом нормализованное имя. */
    private fun resolveOne(
        item: CartItemDto,
        byBarcode: Map<String, ProductEntity>,
        byName: Map<String, ProductEntity>,
    ): ProductEntity? {
        val bc = item.barcode?.trim()?.takeIf { it.isNotEmpty() }
        return bc?.let { byBarcode[it] }
            ?: item.name?.takeIf { it.isNotBlank() }?.let { byName[normalizeName(it)] }
    }

    /**
     * Один товар на ключ → возвращаем его; несколько разных товаров на один ключ → неоднозначно,
     * warn в лог и null (не матчим). distinctBy.id — несколько строк одного товара коллизией не считаем.
     */
    private fun uniqueOrWarn(key: String, products: List<ProductEntity>, kind: String): ProductEntity? {
        val distinct = products.distinctBy { it.id }
        if (distinct.size > 1) {
            log.warn(
                "POSM resolveCart: {} '{}' указывает на {} разных товаров {} — позицию не матчим (неоднозначно)",
                kind, key, distinct.size, distinct.map { it.id },
            )
            return null
        }
        return distinct.firstOrNull()
    }

    /**
     * Нормализация имени для fallback-матчинга: lowercase, trim, схлопывание внутренних
     * пробелов в один, выбрасываем всю пунктуацию — оставляем буквы (вкл. кириллицу),
     * цифры и пробелы. «Аквалор Норм спрей, 50 мл!» → «аквалор норм спрей 50 мл».
     */
    private fun normalizeName(raw: String): String =
        raw.lowercase()
            .map { ch -> if (ch.isLetterOrDigit() || ch.isWhitespace()) ch else ' ' }
            .joinToString("")
            .trim()
            .replace(WHITESPACE, " ")

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

    private companion object {
        private val WHITESPACE = Regex("\\s+")
    }
}
