package kz.epharm.posm

import io.mockk.every
import io.mockk.mockk
import kz.epharm.catalog.entity.ProductEntity
import kz.epharm.catalog.repository.ProductRepository
import kz.epharm.posm.dto.CartItemDto
import kz.epharm.posm.repository.ProductPosCodeRepository
import kz.epharm.promo.repository.PromoRepository
import kz.epharm.posm.service.RulesEngineService
import kz.epharm.rules.entity.RuleEntity
import kz.epharm.rules.entity.RuleStatus
import kz.epharm.rules.entity.RuleTrigger
import kz.epharm.rules.entity.RuleType
import kz.epharm.rules.repository.RuleRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Optional

/**
 * Детект конфликтов правил (T2): неоднозначная замена и противоречие замена↔кросс-селл.
 * Репозитории замоканы — без БД.
 */
class RulesEngineConflictTest {

    private val ruleRepo = mockk<RuleRepository>()
    private val productRepo = mockk<ProductRepository>()
    private val posCodeRepo = mockk<ProductPosCodeRepository>(relaxed = true)
    // Правила теста без promoId (legacy) → гейтинг по кампании их не трогает,
    // promoRepo.findAllById не вызывается; mock нужен только для конструктора.
    private val promoRepo = mockk<PromoRepository>(relaxed = true)
    private val engine = RulesEngineService(ruleRepo, productRepo, posCodeRepo, promoRepo)

    private fun product(id: String) = ProductEntity(id = id, name = "Товар $id", price = 100)

    private fun rule(id: String, type: RuleType, triggerProduct: String, recommend: String, bonus: Int = 100) =
        RuleEntity(
            id = id,
            recommend = recommend,
            bonus = bonus,
            trigger = RuleTrigger(kind = "product", value = triggerProduct),
        ).also { it.type = type; it.status = RuleStatus.active }

    private fun stub(rules: List<RuleEntity>, cartProducts: List<ProductEntity>, recommends: List<ProductEntity>) {
        every { ruleRepo.findAllByStatusRawOrderByUpdatedAtDesc("active") } returns rules
        every { productRepo.findAllById(any<Iterable<String>>()) } returns cartProducts
        (cartProducts + recommends).forEach { p ->
            every { productRepo.findById(p.id) } returns Optional.of(p)
        }
    }

    @Test
    fun `неоднозначная замена — два разных рекомендованных на один триггер → конфликт, рекомендаций нет`() {
        val x = product("X"); val y = product("Y"); val z = product("Z")
        stub(
            rules = listOf(
                rule("r1", RuleType.substitution, triggerProduct = "X", recommend = "Y"),
                rule("r2", RuleType.substitution, triggerProduct = "X", recommend = "Z"),
            ),
            cartProducts = listOf(x), recommends = listOf(y, z),
        )

        val res = engine.match(listOf(CartItemDto(sku = "X")))

        assertTrue(res.matches.isEmpty(), "обе замены подавлены конфликтом")
        assertEquals(1, res.conflicts.size)
        assertEquals("ambiguous_substitution", res.conflicts[0].kind)
        assertTrue(res.conflicts[0].ruleIds.containsAll(listOf("r1", "r2")))
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

        val res = engine.match(listOf(CartItemDto(sku = "X")))

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

        val res = engine.match(listOf(CartItemDto(sku = "X")))

        assertEquals(1, res.matches.size)
        assertEquals("Y", res.matches[0].recommend.id)
        assertTrue(res.conflicts.isEmpty())
    }
}
