package kz.epharm.mobile.profile.service

import kz.epharm.catalog.entity.ProductEntity
import kz.epharm.catalog.repository.ProductRepository
import kz.epharm.mobile.profile.dto.PharmacistBalanceDto
import kz.epharm.mobile.profile.dto.PharmacistOfferDto
import kz.epharm.mobile.profile.dto.PharmacistRecommendationDto
import kz.epharm.mobile.profile.dto.PharmacistRecommendationsDto
import kz.epharm.mobile.profile.dto.RecommendationCampaignDto
import kz.epharm.mobile.profile.dto.RecommendationProductDto
import kz.epharm.pharmacists.entity.PharmacistEntity
import kz.epharm.pharmacists.entity.PharmacistStatus
import kz.epharm.pharmacists.repository.PharmacistRepository
import kz.epharm.promo.entity.PromoStatus
import kz.epharm.promo.repository.PromoRepository
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
import java.time.LocalDate
import java.time.ZoneId

@Service
class PharmacistSelfService(
    private val pharmacists: PharmacistRepository,
    private val rules: RuleRepository,
    private val promos: PromoRepository,
    private val products: ProductRepository,
) {
    @Transactional(readOnly = true)
    fun balance(pharmacistId: String): PharmacistBalanceDto = PharmacistBalanceDto(activeAccount(pharmacistId).balance)

    @Transactional(readOnly = true)
    fun recommendations(
        pharmacistId: String,
        page: Int,
        size: Int,
        today: LocalDate = LocalDate.now(ZoneId.of("Asia/Almaty")),
    ): PharmacistRecommendationsDto {
        activeAccount(pharmacistId)
        if (page < 0 || size !in 1..100) {
            throw AppException(ErrorCode.VALIDATION_FAILED, "page должен быть ≥ 0, size — от 1 до 100")
        }
        val activeRules = rules.findAllByStatusRawOrderByUpdatedAtDesc(RuleStatus.active.name)
        val campaigns = promos.findAllById(activeRules.mapNotNull { it.promoId }.toSet())
            .filter {
                it.status == PromoStatus.active &&
                    (it.dateStart == null || !today.isBefore(it.dateStart)) &&
                    (it.dateEnd == null || !today.isAfter(it.dateEnd))
            }.associateBy { it.id }
        val eligible = activeRules.filter { it.promoId == null || it.promoId in campaigns }
        val productIds = eligible.flatMap { listOf(it.recommend) + triggerIds(it.trigger) }.toSet()
        val catalog = products.findAllById(productIds).associateBy { it.id }
        val groups = eligible.filter { it.recommend in catalog }
            .groupBy { GroupKey(it.promoId, it.type, it.trigger) }
            .entries.sortedWith(
                compareByDescending<Map.Entry<GroupKey, List<RuleEntity>>> {
                    it.value.maxOf { rule -> rule.updatedAt }
                }.thenBy { it.value.minOf { rule -> rule.id } },
            )
        // Page whole groups so a five-offer replacement never splits across pages.
        val offset = page.toLong() * size
        val items = if (offset >= groups.size) emptyList() else groups.drop(offset.toInt()).take(size).map { (key, values) ->
            val campaign = key.promoId?.let(campaigns::get)
            PharmacistRecommendationDto(
                campaign = campaign?.let { RecommendationCampaignDto(it.id, it.title, it.dateStart, it.dateEnd) },
                type = key.type,
                trigger = key.trigger,
                triggerProducts = triggerIds(key.trigger).mapNotNull(catalog::get).map(::productDto),
                offers = values.sortedWith(compareBy({ it.card?.offerRank ?: Int.MAX_VALUE }, { -it.bonus }, { it.id }))
                    .distinctBy { it.recommend }.take(5).map {
                        PharmacistOfferDto(it.id, productDto(catalog.getValue(it.recommend)), it.bonus, it.script, it.advantages)
                    },
            )
        }
        return PharmacistRecommendationsDto(items, page, size, groups.size)
    }

    private fun activeAccount(id: String): PharmacistEntity {
        val account = pharmacists.findById(id).orElseThrow {
            AppException(ErrorCode.USER_NOT_FOUND, "Фармацевт не найден", HttpStatus.UNAUTHORIZED)
        }
        if (account.status != PharmacistStatus.active) {
            throw AppException(ErrorCode.USER_NOT_ACTIVE, "Аккаунт фармацевта не активен", HttpStatus.FORBIDDEN)
        }
        return account
    }

    private fun triggerIds(trigger: RuleTrigger): List<String> = when (trigger.kind) {
        "product" -> listOfNotNull(trigger.value as? String)
        "product_any" -> (trigger.value as? List<*>)?.filterIsInstance<String>().orEmpty()
        else -> emptyList()
    }

    private fun productDto(product: ProductEntity) = RecommendationProductDto(
        product.id, product.name, product.brand, product.mnn, product.volume,
        product.scanBarcodes(), product.ipartId, product.price, product.updatedAt,
    )

    private data class GroupKey(val promoId: String?, val type: RuleType, val trigger: RuleTrigger)
}
