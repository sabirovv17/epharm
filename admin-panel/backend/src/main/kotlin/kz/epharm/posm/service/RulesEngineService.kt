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
import java.time.LocalDate
import java.time.ZoneId

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
 *   3. КОНФЛИКТЫ (T2): противоречие замена↔кросс-селл — такие правила
 *      НЕ показываем, а возвращаем как conflicts (касса покажет «замена/кросс-селл невозможны»).
 *   4. порядок выживших: сначала ВСЕ substitution (бонус DESC), затем crosssell (бонус DESC).
 *   5. dedup по типу+recommend-товару (первый победил).
 *
 * РЕЗОЛВ корзины → наш productId: касса Стандарт-Н шлёт позиции с локальным PARTS.ID (sku),
 * EAN/GTIN (barcode) и/или названием (name). Матчим:
 *   (1) по штрих-коду (barcode) — стабильный межаптечный идентификатор товара;
 *   (2) иначе по iPartID (sku), если EAN недоступен;
 *   (3) иначе по имени (name) — нормализованное совпадение с ProductEntity.name (fallback);
 *   (4) иначе позиция не резолвится (в матчинге не участвует).
 *
 * Фильтр «не показывать отклонённое в этом чеке» и лимит top-5 на тип — в RecommendationService.
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
        val activeRules = ruleRepository.findAllByStatusRawOrderByUpdatedAtDesc(RuleStatus.active.name)
        // Кампания — мастер-выключатель: правило из неактивной кампании НЕ показываем,
        // даже если оно осталось active в БД (смена статуса кампании не пересохраняет правила).
        // Границы дат включительны и считаются в часовом поясе аптек Казахстана: активный статус
        // не должен оживлять ещё не начавшуюся или уже завершившуюся кампанию.
        // Правила без promoId (legacy ручные) проходят как есть.
        val promoIds = activeRules.mapNotNull { it.promoId }.toSet()
        val today = LocalDate.now(CAMPAIGN_ZONE)
        val activePromoIds =
            if (promoIds.isEmpty()) emptySet()
            else promoRepository.findAllById(promoIds)
                .filter {
                    it.status == PromoStatus.active &&
                        (it.dateStart == null || !today.isBefore(it.dateStart)) &&
                        (it.dateEnd == null || !today.isAfter(it.dateEnd))
                }
                .map { it.id }
                .toSet()
        val active = activeRules.filter { it.promoId == null || it.promoId in activePromoIds }
        if (active.isEmpty()) return RuleMatchResult(emptyList(), emptyList())

        // Для online-рекомендаций не загружаем и не нормализуем весь каталог Medusa (28k+ строк)
        // на каждый скан. Нужны только товары из действующих правил: это одновременно быстрее и
        // безопаснее для fuzzy-fallback — нерелевантный товар каталога не может стать триггером.
        val relevantProducts = relevantProducts(active)
        val relevantById = relevantProducts.associateBy(ProductEntity::id)
        val cartProducts: Map<String, ProductEntity> = resolveCart(cart, relevantProducts)
        val cartSkus: Set<String> = cartProducts.keys
        if (cartSkus.isEmpty()) return RuleMatchResult(emptyList(), emptyList())

        val raw = active.mapNotNull { rule ->
            val triggerSku = matchTrigger(rule.trigger, cartSkus, cartProducts) ?: return@mapNotNull null
            // recommend не должен уже лежать в корзине
            if (rule.recommend in cartSkus) return@mapNotNull null
            val recProduct = relevantById[rule.recommend] ?: return@mapNotNull null
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

        // Одна исходная позиция может иметь несколько альтернатив: это основной multi-offer сценарий.
        // Конфликтом остаётся только одна и та же пара, одновременно заведённая как замена и cross-sell.
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
                    { it.rule.card?.offerRank ?: Int.MAX_VALUE },            // порядок из админки
                    { it.rule.id },                                          // детерминированный fallback
                ),
            )
            .distinctBy { it.rule.type to it.recommend.id }

        return RuleMatchResult(survivors, conflicts)
    }

    /**
     * Резолв корзины кассы → наши товары (id → ProductEntity).
     *   (1) по iPartID (sku, trim, непустой) — точное совпадение ProductEntity.ipartId;
     *   (2) по штрих-коду (barcode, trim, непустой) — точное совпадение ProductEntity.barcode;
     *   (3) для позиций без точного матча — fallback по нормализованному имени.
     * Возврат — уникальные товары (по productId); один товар, даже если в корзине дважды, один раз.
     *
     * Коллизии (один iPartID / штрих-код / нормализованное имя у ≠ товаров) НЕ разрешаем «наугад»:
     * такой ключ считаем неоднозначным и НЕ матчим (с warn в лог) — лучше не показать рекомендацию,
     * чем показать рекомендацию чужого товара. Уникальность гарантируется только данными PIM.
     */
    private fun resolveCart(
        cart: List<CartItemDto>,
        candidates: List<ProductEntity>? = null,
    ): Map<String, ProductEntity> {
        val byBarcode = barcodeIndex(cart, candidates)
        val byIpart = ipartIndex(cart, candidates)
        val byName = nameIndex(cart, byBarcode, byIpart, candidates)
        val resolved = LinkedHashMap<String, ProductEntity>()
        cart.forEach { item ->
            resolveOne(item, byIpart, byBarcode, byName, candidates)
                ?.let { resolved.putIfAbsent(it.id, it) }
        }
        return resolved
    }

    /**
     * Резолв позиций (штрих-код → iPartID → имя) в наши productId — для сверки чека из лога кассы
     * (источник №1, /api/posm/sales). По каждой позиции в исходном порядке — productId или null
     * (не нашли / неоднозначно). Тот же коллизионно-устойчивый матчинг, что и в рекомендациях.
     */
    @Transactional(readOnly = true)
    fun resolveToProductIds(items: List<CartItemDto>): List<String?> {
        val byBarcode = barcodeIndex(items)
        val byIpart = ipartIndex(items)
        val byName = nameIndex(items, byBarcode, byIpart)
        return items.map { resolveOne(it, byIpart, byBarcode, byName)?.id }
    }

    /** Индекс iPartID Стандарт-Н → товар: только однозначные ключи (коллизия → warn + пропуск). */
    private fun ipartIndex(
        cart: List<CartItemDto>,
        candidates: List<ProductEntity>? = null,
    ): Map<String, ProductEntity> {
        val ipartIds = cart.mapNotNull { it.sku?.trim()?.takeIf { id -> id.isNotEmpty() } }.distinct()
        if (ipartIds.isEmpty()) return emptyMap()
        val products = candidates?.filter { it.ipartId?.trim() in ipartIds }
            ?: productRepository.findAllByIpartIdIn(ipartIds)
        return products
            .filter { !it.ipartId.isNullOrBlank() }
            .groupBy { it.ipartId!!.trim() }
            .mapNotNull { (id, products) -> uniqueOrWarn(id, products, "iPartID")?.let { id to it } }
            .toMap()
    }

    /** Индекс штрих-код → товар: только однозначные ключи (коллизия → warn + пропуск). */
    private fun barcodeIndex(
        cart: List<CartItemDto>,
        candidates: List<ProductEntity>? = null,
    ): Map<String, ProductEntity> {
        val barcodes = cart.mapNotNull { it.barcode?.trim()?.takeIf { b -> b.isNotEmpty() } }.distinct()
        if (barcodes.isEmpty()) return emptyMap()
        val products = candidates?.filter { it.barcode?.trim() in barcodes }
            ?: productRepository.findAllByBarcodeIn(barcodes)
        return products
            .filter { !it.barcode.isNullOrBlank() }
            .groupBy { it.barcode!!.trim() }
            .mapNotNull { (bc, products) -> uniqueOrWarn(bc, products, "штрих-код")?.let { bc to it } }
            .toMap()
    }

    /**
     * Индекс нормализованное-имя → товар. Строим только если есть позиции без точного матча, но с
     * именем (чтобы не грузить каталог зря). Детерминированный порядок + защита от коллизий имён.
     */
    private fun nameIndex(
        cart: List<CartItemDto>,
        byBarcode: Map<String, ProductEntity>,
        byIpart: Map<String, ProductEntity>,
        candidates: List<ProductEntity>? = null,
    ): Map<String, ProductEntity> {
        val needName = cart.any { item ->
            val ipart = item.sku?.trim()?.takeIf { it.isNotEmpty() }
            val bc = item.barcode?.trim()?.takeIf { it.isNotEmpty() }
            (bc == null || bc !in byBarcode) &&
                (ipart == null || ipart !in byIpart) &&
                !item.name.isNullOrBlank()
        }
        if (!needName) return emptyMap()
        return (candidates ?: productRepository.findAllByOrderByNameAsc())
            .filter { it.name.isNotBlank() }
            .groupBy { normalizeName(it.name) }
            .mapNotNull { (norm, products) -> uniqueOrWarn(norm, products, "имя")?.let { norm to it } }
            .toMap()
    }

    /** Резолв одной позиции: сначала EAN/GTIN, потом локальный iPartID, затем имя. */
    private fun resolveOne(
        item: CartItemDto,
        byIpart: Map<String, ProductEntity>,
        byBarcode: Map<String, ProductEntity>,
        byName: Map<String, ProductEntity>,
        candidates: List<ProductEntity>? = null,
    ): ProductEntity? {
        val ipart = item.sku?.trim()?.takeIf { it.isNotEmpty() }
        val bc = item.barcode?.trim()?.takeIf { it.isNotEmpty() }
        return bc?.let { byBarcode[it] }
            ?: ipart?.let { byIpart[it] }
            ?: item.name?.takeIf { it.isNotBlank() }?.let { byName[normalizeName(it)] }
            ?: item.name?.takeIf { it.isNotBlank() }
                ?.let { fuzzyNameCandidate(it, candidates.orEmpty()) }
    }

    /**
     * Загружает минимальный набор товаров, способных участвовать в действующих правилах.
     * По product/product_any берём trigger ids, по mnn — все товары указанного МНН;
     * recommend нужен и для ответа, и для проверки «уже в корзине».
     */
    private fun relevantProducts(rules: List<RuleEntity>): List<ProductEntity> {
        val productIds = LinkedHashSet<String>()
        val mnns = LinkedHashSet<String>()
        rules.forEach { rule ->
            productIds += rule.recommend
            when (rule.trigger.kind) {
                "product" -> (rule.trigger.value as? String)?.let(productIds::add)
                "product_any" -> (rule.trigger.value as? List<*>)
                    ?.mapNotNull { it as? String }
                    ?.let(productIds::addAll)
                "mnn" -> (rule.trigger.value as? String)?.let(mnns::add)
            }
        }
        return buildList {
            productIds.mapNotNullTo(this) { productRepository.findById(it).orElse(null) }
            mnns.flatMapTo(this) { productRepository.findAllByMnnOrderByNameAsc(it) }
        }.distinctBy(ProductEntity::id)
    }

    /**
     * Консервативный fallback для реальных касс Стандарт-Н, где EAN часто заменён внутренним
     * штрих-кодом, а название отличается порядком слов/сокращениями. Сравниваем только товары
     * активных правил, требуем одинаковые числа/дозировки и критичные квалификаторы (например,
     * детский/форте), высокий Jaccard и отрыв от второго кандидата. Неоднозначность = no match.
     */
    private fun fuzzyNameCandidate(raw: String, candidates: List<ProductEntity>): ProductEntity? {
        if (candidates.isEmpty()) return null
        val source = nameFingerprint(raw)
        if (source.tokens.size < 3) return null

        val ranked = candidates.asSequence()
            .filter { it.name.isNotBlank() }
            .map { it to nameFingerprint(it.name) }
            .filter { (_, target) ->
                source.numbers == target.numbers && source.qualifiers == target.qualifiers
            }
            .map { (product, target) ->
                val intersection = source.tokens.intersect(target.tokens).size.toDouble()
                val union = source.tokens.union(target.tokens).size.toDouble()
                product to if (union == 0.0) 0.0 else intersection / union
            }
            .sortedByDescending { it.second }
            .take(2)
            .toList()
        val best = ranked.firstOrNull() ?: return null
        val secondScore = ranked.getOrNull(1)?.second
        if (best.second < FUZZY_NAME_THRESHOLD ||
            (secondScore != null && best.second - secondScore < FUZZY_NAME_MARGIN)
        ) return null

        log.debug("POSM resolveCart: имя '{}' безопасно сопоставлено с productId={}", raw, best.first.id)
        return best.first
    }

    private fun nameFingerprint(raw: String): NameFingerprint {
        val tokens = WORD_OR_NUMBER.findAll(raw.lowercase())
            .map(MatchResult::value)
            .map { it.replace(',', '.') }
            .map { TOKEN_ALIASES[it] ?: it }
            .filterNot { it in NAME_STOP_WORDS }
            .toSet()
        return NameFingerprint(
            tokens = tokens,
            numbers = NUMBER.findAll(raw.lowercase())
                .map(MatchResult::value)
                .map { it.replace(',', '.') }
                .toSet(),
            qualifiers = tokens.filterTo(linkedSetOf()) { it in SAFETY_QUALIFIERS },
        )
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
        private val CAMPAIGN_ZONE: ZoneId = ZoneId.of("Asia/Almaty")
        private val WHITESPACE = Regex("\\s+")
        private val NUMBER = Regex("\\d+(?:[.,]\\d+)?")
        private val WORD_OR_NUMBER = Regex("[\\p{L}]+|\\d+(?:[.,]\\d+)?")
        private const val FUZZY_NAME_THRESHOLD = 0.82
        private const val FUZZY_NAME_MARGIN = 0.08
        private val NAME_STOP_WORDS = setOf("для", "применения", "прим", "лица")
        private val SAFETY_QUALIFIERS = setOf(
            "детский", "форте", "плюс", "макс", "лайт", "ночной", "дневной",
        )
        private val TOKEN_ALIASES = mapOf(
            "дет" to "детский",
            "детс" to "детский",
            "детей" to "детский",
            "детская" to "детский",
            "детское" to "детский",
            "таб" to "таблетка",
            "табл" to "таблетка",
            "таблетки" to "таблетка",
            "капс" to "капсула",
            "капсулы" to "капсула",
        )
    }

    private data class NameFingerprint(
        val tokens: Set<String>,
        val numbers: Set<String>,
        val qualifiers: Set<String>,
    )
}
