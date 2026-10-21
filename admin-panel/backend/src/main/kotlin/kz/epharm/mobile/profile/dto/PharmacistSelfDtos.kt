package kz.epharm.mobile.profile.dto

import kz.epharm.rules.entity.RuleTrigger
import kz.epharm.rules.entity.RuleType
import java.time.Instant
import java.time.LocalDate

data class PharmacistBalanceDto(val balanceKzt: Long, val currency: String = "KZT")

data class PharmacistRecommendationsDto(
    val items: List<PharmacistRecommendationDto>,
    val page: Int,
    val size: Int,
    val total: Int,
    val currency: String = "KZT",
)

data class PharmacistRecommendationDto(
    val campaign: RecommendationCampaignDto?,
    val type: RuleType,
    val trigger: RuleTrigger,
    val triggerProducts: List<RecommendationProductDto>,
    val offers: List<PharmacistOfferDto>,
)

data class RecommendationCampaignDto(
    val id: String,
    val title: String,
    val dateStart: LocalDate?,
    val dateEnd: LocalDate?,
)

data class PharmacistOfferDto(
    val ruleId: String,
    val product: RecommendationProductDto,
    val bonusKzt: Int,
    val script: String,
    val advantages: List<String>,
)

data class RecommendationProductDto(
    val id: String,
    val name: String,
    val brand: String,
    val mnn: String,
    val volume: String,
    val barcodes: List<String>,
    val ipartId: String?,
    val catalogPriceKzt: Int,
    val catalogUpdatedAt: Instant,
)
