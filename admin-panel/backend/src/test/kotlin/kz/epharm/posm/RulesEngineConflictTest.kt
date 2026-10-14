package kz.epharm.posm

import io.mockk.every
import io.mockk.mockk
import kz.epharm.catalog.entity.ProductEntity
import kz.epharm.catalog.repository.ProductRepository
import kz.epharm.catalog.service.AccCatalogTaxonomy
import kz.epharm.posm.dto.CartItemDto
import kz.epharm.promo.entity.PromoEntity
import kz.epharm.promo.entity.PromoStatus
import kz.epharm.promo.repository.PromoRepository
import kz.epharm.posm.service.RulesEngineService
import kz.epharm.rules.entity.RuleCard
import kz.epharm.rules.entity.RuleEntity
import kz.epharm.rules.entity.RuleStatus
import kz.epharm.rules.entity.RuleTrigger
import kz.epharm.rules.entity.RuleType
import kz.epharm.rules.repository.RuleRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.Duration
import java.time.ZoneId
import java.util.Optional

/**
 * Детект конфликтов правил (T2): несколько аналогов — валидный multi-offer,
 * конфликтом остаётся только противоречие замена↔кросс-селл для одной пары.
 * Репозитории замоканы — без БД. Корзина матчится по штрих-коду (barcode == EAN-13).
 */
class RulesEngineConflictTest {

    private val ruleRepo = mockk<RuleRepository>()
    private val productRepo = mockk<ProductRepository>()
    // Правила теста без promoId (legacy) → гейтинг по кампании их не трогает,
    // promoRepo.findAllById не вызывается; mock нужен только для конструктора.
    private val promoRepo = mockk<PromoRepository>(relaxed = true)
    private val accCatalog = mockk<AccCatalogTaxonomy>(relaxed = true)
    private val engine = RulesEngineService(ruleRepo, productRepo, promoRepo, accCatalog)

    /** Товар с штрих-кодом «bar-<id>», чтобы корзина матчилась по barcode. */
    private fun product(id: String) =
        ProductEntity(id = id, name = "Товар $id", price = 100).also { it.barcode = "bar-$id" }

    private fun rule(
        id: String,
        type: RuleType,
        triggerProduct: String,
        recommend: String,
        bonus: Int = 100,
        promoId: String? = null,
        offerRank: Int? = null,
    ) =
        RuleEntity(
            id = id,
            recommend = recommend,
            bonus = bonus,
            trigger = RuleTrigger(kind = "product", value = triggerProduct),
        ).also {
            it.type = type
            it.status = RuleStatus.active
            it.promoId = promoId
            if (offerRank != null) it.card = RuleCard(offerRank = offerRank)
        }

    private fun stub(
        rules: List<RuleEntity>,
        cartProducts: List<ProductEntity>,
        recommends: List<ProductEntity>,
        promos: List<PromoEntity> = emptyList(),
    ) {
        every { ruleRepo.findAllByStatusRawOrderByUpdatedAtDesc("active") } returns rules
        if (rules.any { it.promoId != null }) {
            every { promoRepo.findAllById(any<Iterable<String>>()) } returns promos
        }
        // Резолв корзины идёт по штрих-коду.
        every { productRepo.findAllByBarcodeIn(any()) } answers {
            val wanted = firstArg<Collection<String>>().toSet()
            cartProducts.filter { it.barcode in wanted }
        }
        (cartProducts + recommends).forEach { p ->
            every { productRepo.findById(p.id) } returns Optional.of(p)
        }
    }

    /** Корзина из товаров (по их штрих-кодам). */
    private fun cart(vararg products: ProductEntity) =
        products.map { CartItemDto(barcode = it.barcode) }

    private fun stubNameTrigger(name: String) {
        val trigger = product("NAME_TRIGGER").also { it.name = name }
        val recommend = product("NAME_OFFER")
        stub(
            rules = listOf(rule("name-rule", RuleType.substitution, trigger.id, recommend.id)),
            cartProducts = listOf(trigger),
            recommends = listOf(recommend),
        )
    }

    @Test
    fun `длинное неизвестное число не вызывает квадратичный поиск единицы дозировки`() {
        stubNameTrigger("Омез 20мг капс №30")
        val raw = "Омез " + "9".repeat(100_000) + "x капс №30"

        assertTimeoutPreemptively(Duration.ofSeconds(2)) {
            assertTrue(engine.match(listOf(CartItemDto(name = raw))).matches.isEmpty())
        }
    }

    @Test
    fun `два аналога на один триггер проходят как multi-offer по бонусу`() {
        val x = product("X"); val y = product("Y"); val z = product("Z")
        stub(
            rules = listOf(
                rule("r1", RuleType.substitution, triggerProduct = "X", recommend = "Y", bonus = 100),
                rule("r2", RuleType.substitution, triggerProduct = "X", recommend = "Z", bonus = 200),
            ),
            cartProducts = listOf(x), recommends = listOf(y, z),
        )

        val res = engine.match(cart(x))

        assertEquals(listOf("Z", "Y"), res.matches.map { it.recommend.id })
        assertTrue(res.conflicts.isEmpty())
    }

    @Test
    fun `порядок вариантов из админки важнее размера бонуса`() {
        val trigger = product("X")
        val primary = product("Y")
        val noBonus = product("Z")
        val higherBonus = product("W")
        stub(
            rules = listOf(
                rule("r3", RuleType.substitution, "X", "W", bonus = 400, offerRank = 2),
                rule("r2", RuleType.substitution, "X", "Z", bonus = 0, offerRank = 1),
                rule("r1", RuleType.substitution, "X", "Y", bonus = 100, offerRank = 0),
            ),
            cartProducts = listOf(trigger),
            recommends = listOf(primary, noBonus, higherBonus),
        )

        val result = engine.match(cart(trigger))

        assertEquals(listOf("Y", "Z", "W"), result.matches.map { it.recommend.id })
        assertTrue(result.conflicts.isEmpty())
    }

    @Test
    fun `противоречие — одна пара триггер→рекомендация и как замена, и как кросс-селл → конфликт`() {
        val x = product("X"); val y = product("Y")
        stub(
            rules = listOf(
                rule("r1", RuleType.substitution, triggerProduct = "X", recommend = "Y"),
                rule("r2", RuleType.crosssell, triggerProduct = "X", recommend = "Y"),
            ),
            cartProducts = listOf(x), recommends = listOf(y),
        )

        val res = engine.match(cart(x))

        assertTrue(res.matches.isEmpty())
        assertEquals(1, res.conflicts.size)
        assertEquals("contradiction", res.conflicts[0].kind)
    }

    @Test
    fun `без конфликта — одна замена проходит, конфликтов нет`() {
        val x = product("X"); val y = product("Y")
        stub(
            rules = listOf(rule("r1", RuleType.substitution, triggerProduct = "X", recommend = "Y")),
            cartProducts = listOf(x), recommends = listOf(y),
        )

        val res = engine.match(cart(x))

        assertEquals(1, res.matches.size)
        assertEquals("Y", res.matches[0].recommend.id)
        assertTrue(res.conflicts.isEmpty())
    }

    @Test
    fun `кассовое сокращение детского товара безопасно матчится по имени`() {
        val trigger = product("CHILD").also {
            it.name = "Жидкий уголь Комплекс с пектином для детей саше 7г №10"
        }
        val recommend = product("REC")
        stub(
            rules = listOf(rule("r1", RuleType.substitution, triggerProduct = trigger.id, recommend = recommend.id)),
            cartProducts = listOf(trigger),
            recommends = listOf(recommend),
        )

        val result = engine.match(
            listOf(CartItemDto(name = "Жидкий уголь комплекс с пектином саше детс 7г №10")),
        )

        assertEquals(listOf("REC"), result.matches.map { it.recommend.id })
    }

    @Test
    fun `взрослый товар не матчится на детское правило по похожему имени`() {
        val trigger = product("CHILD").also {
            it.name = "Жидкий уголь Комплекс с пектином для детей саше 7г №10"
        }
        val recommend = product("REC")
        stub(
            rules = listOf(rule("r1", RuleType.substitution, triggerProduct = trigger.id, recommend = recommend.id)),
            cartProducts = listOf(trigger),
            recommends = listOf(recommend),
        )

        val result = engine.match(
            listOf(CartItemDto(name = "Жидкий уголь комплекс с пектином саше 7г №10")),
        )

        assertTrue(result.matches.isEmpty())
    }

    @Test
    fun `фасовка без пробела и необязательное слово формы не ломают матч`() {
        val trigger = product("WATER").also {
            it.name = "Ivatherm Термальная вода Геркулан спрей 100мл"
        }
        val recommend = product("REC")
        stub(
            rules = listOf(rule("r1", RuleType.crosssell, triggerProduct = trigger.id, recommend = recommend.id)),
            cartProducts = listOf(trigger),
            recommends = listOf(recommend),
        )

        val result = engine.match(
            listOf(CartItemDto(name = "Ivatherm Термальная вода Геркулан 100 мл")),
        )

        assertEquals(listOf("REC"), result.matches.map { it.recommend.id })
    }

    @Test
    fun `точное имя Омеза с неизвестным локальным SKU и пустым EAN выдаёт три варианта`() {
        val trigger = product("OMEZ").also {
            it.name = "Омез 20мг капс №30"
            it.barcode = "8901148217805"
            it.ipartId = null
        }
        val offers = listOf(product("OFFER_A"), product("OFFER_B"), product("OFFER_C"))
        stub(
            rules = offers.mapIndexed { rank, offer ->
                rule("omez-$rank", RuleType.substitution, trigger.id, offer.id, offerRank = rank)
            },
            cartProducts = listOf(trigger),
            recommends = offers,
        )

        val result = engine.match(
            listOf(CartItemDto(sku = "312813", barcode = "", name = "Омез 20мг капс №30")),
        )

        assertEquals(offers.map { it.id }, result.matches.map { it.recommend.id })
        assertTrue(result.conflicts.isEmpty())
    }

    @Test
    fun `известное сокращение капсул и пробелы в дозировке сохраняют совпадение Омеза`() {
        stubNameTrigger("Омез 20мг капс №30")
        listOf(
            "Омез капсулы 20 мг №30",
            " ОМЕЗ 20 мг капс. № 30 ",
            "Омез20мг капс №30",
        ).forEach { name ->
            val result = engine.match(listOf(CartItemDto(sku = "312813", barcode = "", name = name)))
            assertEquals(listOf("NAME_OFFER"), result.matches.map { it.recommend.id }, name)
        }
    }

    @Test
    fun `Омез ДСР с двумя дозировками не совпадает с обычным Омезом`() {
        stubNameTrigger("Омез 20мг капс №30")

        val result = engine.match(listOf(CartItemDto(name = "Омез ДСР 30мг+20мг капс №30")))

        assertTrue(result.matches.isEmpty(), "Repeated 30 is a second strength, not just the pack size")
    }

    @Test
    fun `иная дозировка и иная упаковка Омеза не совпадают с триггером`() {
        stubNameTrigger("Омез 20мг капс №30")
        listOf("Омез 40мг капс №30", "Омез 20мг капс №10").forEach { name ->
            val result = engine.match(listOf(CartItemDto(name = name)))
            assertTrue(result.matches.isEmpty(), name)
        }
    }

    @Test
    fun `числа дозировки и упаковки нельзя переставить сохранив совпадение`() {
        stubNameTrigger("Омез 20мг капс №30")

        val result = engine.match(listOf(CartItemDto(name = "Омез 30мг капс №20")))

        assertTrue(result.matches.isEmpty(), "20 mg pack 30 differs from 30 mg pack 20")
    }

    @Test
    fun `явно разные формы не совпадают даже при высоком сходстве длинных названий`() {
        val base = "Тестовый препарат с длинным торговым наименованием производителя"
        stubNameTrigger("$base 20мг капс №30")

        val result = engine.match(listOf(CartItemDto(name = "$base 20мг табл №30")))

        assertTrue(result.matches.isEmpty(), "Explicit tablets cannot match explicit capsules")
    }

    @Test
    fun `единицы дозировки не теряются при высоком сходстве длинных названий`() {
        val base = "Тестовый препарат с длинным торговым наименованием производителя"
        stubNameTrigger("$base 20мг капс №30")
        listOf("мл", "мкг").forEach { unit ->
            val result = engine.match(listOf(CartItemDto(name = "$base 20$unit капс №30")))
            assertTrue(result.matches.isEmpty(), "20 mg differs from 20 $unit")
        }
    }

    @Test
    fun `множественное число ампул не позволяет совпасть с капсулами`() {
        val base = "Тестовый препарат с длинным торговым наименованием производителя"
        stubNameTrigger("$base 20мг капс №30")

        val result = engine.match(listOf(CartItemDto(name = "$base 20мг ампулы №30")))

        assertTrue(result.matches.isEmpty(), "Explicit ampoules cannot match explicit capsules")
    }

    @Test
    fun `знаменатель концентрации не теряется при сравнении похожих названий`() {
        val base = "Тестовый препарат с длинным торговым наименованием производителя"
        stubNameTrigger("$base 20мг/мл раствор №30")

        val result = engine.match(listOf(CartItemDto(name = "$base 20мг/г раствор №30")))

        assertTrue(result.matches.isEmpty(), "20 mg/ml differs from 20 mg/g")
    }

    @Test
    fun `нормализованное точное имя не обходится без знака процентной концентрации`() {
        val base = "Тестовый препарат с длинным торговым наименованием производителя"
        stubNameTrigger("$base 20% раствор 100мл")

        val result = engine.match(listOf(CartItemDto(name = "$base 20 раствор 100мл")))

        assertTrue(result.matches.isEmpty(), "Percent is a concentration unit, not ignorable punctuation")
    }

    @Test
    fun `активный статус не обходит даты кампании`() {
        val today = LocalDate.now(ZoneId.of("Asia/Almaty"))
        val trigger = product("LIQUID_COAL")
        val current = product("CURRENT")
        val expired = product("EXPIRED")
        val future = product("FUTURE")

        fun promo(id: String, start: LocalDate?, end: LocalDate?) =
            PromoEntity(id = id).also {
                it.status = PromoStatus.active
                it.dateStart = start
                it.dateEnd = end
            }

        stub(
            rules = listOf(
                rule(
                    "r-current",
                    RuleType.substitution,
                    trigger.id,
                    current.id,
                    promoId = "p-current",
                ),
                rule(
                    "r-expired",
                    RuleType.substitution,
                    trigger.id,
                    expired.id,
                    promoId = "p-expired",
                ),
                rule(
                    "r-future",
                    RuleType.substitution,
                    trigger.id,
                    future.id,
                    promoId = "p-future",
                ),
            ),
            cartProducts = listOf(trigger),
            recommends = listOf(current, expired, future),
            promos = listOf(
                promo("p-current", today, today),
                promo("p-expired", today.minusDays(10), today.minusDays(1)),
                promo("p-future", today.plusDays(1), today.plusDays(10)),
            ),
        )

        val result = engine.match(cart(trigger))

        assertEquals(listOf(current.id), result.matches.map { it.recommend.id })
    }
}
