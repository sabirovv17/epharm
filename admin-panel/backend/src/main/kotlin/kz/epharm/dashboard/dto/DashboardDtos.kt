package kz.epharm.dashboard.dto

/**
 * Сводка KPI для обзорного экрана HQ (ТЗ §3.2 — Dashboard).
 * Чисто read-only агрегация поверх существующих таблиц rules / pharmacies / payouts.
 * Никаких новых сущностей — поэтому миграции под этот эндпоинт нет.
 */
data class DashboardSummaryDto(
    val impressions: Int,
    val accepts: Int,
    val convRate: Double,
    val activeRules: Int,
    val totalRules: Int,
    val paidOut: Long,
    val pendingPayout: Long,
    val pilotPharmacies: Int,
    val avgLiftPct: Double,
    val topRules: List<TopRuleDto>,
    val topPharmacies: List<TopPharmacyDto>,
    val topProducts: List<TopProductDto>,
)

data class TopRuleDto(
    val id: String,
    val label: String,
    val convRate: Double,
    val accepts: Int,
)

data class TopPharmacyDto(
    val id: String,
    val name: String,
    val city: String,
    val rulesAccepted: Int,
)

data class TopProductDto(
    val id: String,
    val name: String,
    val revenue: Long,
)
