package kz.epharm.receipts.dto

import java.math.BigDecimal
import java.time.Instant

/**
 * Вход сверки из источника №1 (лог кассы). POSM-клиент после печати чека шлёт состав
 * корзины; ReconcileService матчит позиции с открытыми pending-бонусами.
 */
data class LogSaleInput(
    val pharmacistId: String,
    val pharmacyId: String,
    val fiscalId: String?,
    val cashier: String?,
    val soldAt: Instant,
    val items: List<LogSaleItem>,
)

data class LogSaleItem(
    val sku: String,
    val qty: Double,
    val price: Long,
    val total: Long,
)

/**
 * Вход сверки из источника №2 (Excel-выгрузка Стандарт-Н). Одна строка = одна позиция чека.
 * Поля nullable — выгрузки бывают неполными; матчинг работает по доступным полям.
 */
data class ExcelRowInput(
    val fiscalId: String?,
    val pharmacyCode: String?,
    val cashier: String?,
    val sku: String?,
    val productName: String?,
    val qty: BigDecimal?,
    val amount: Long?,
    val soldAt: Instant?,
)
