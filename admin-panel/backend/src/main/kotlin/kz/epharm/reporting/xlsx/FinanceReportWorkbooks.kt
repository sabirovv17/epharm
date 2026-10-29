package kz.epharm.reporting.xlsx

import kz.epharm.reporting.exports.*
import org.springframework.stereotype.Component

@Component
class FinanceReportWorkbooks {
    fun ledger(report: LedgerReport): ByteArray = ReportWorkbook.render(listOf(
        parameters("Проводки общего кошелька", listOf(
            "С" to report.from, "По (включительно)" to report.to, "Часовой пояс" to report.timezone,
            "Срез до (не включая)" to report.asOfExclusive, "Сформирован" to report.generatedAt,
            "Источник" to report.sourceFilter, "ID фармацевта" to report.pharmacistFilter,
            "Область аптек" to scopeLabel(report.scopePharmacyIds), "Строк" to report.entries.size,
            "Строк без исторической аптеки" to report.unresolvedHistoricalRows,
            "Полнота истории" to report.coverage, "Историческая область" to report.historicalScopeNotice,
            "Суммы" to "Сумма проводок — изменение кошелька, не повторное сложение исходных бонусов и не банковская выплата",
        )),
        ReportSheet("Сводка", listOf("Источник", "Проводок", "Изменение кошелька, KZT", "Начислено, KZT"),
            report.totals.map { listOf(it.source, it.entries, it.amountKzt, it.earnedDeltaKzt) }),
        ReportSheet("Данные", listOf("ID проводки", "ID фармацевта", "ФИО", "Источник", "ID основания", "Сумма, KZT",
            "Начислено, KZT", "Валюта", "Контекст основания", "Баланс после, KZT (только ALL)", "Дата проводки",
            "ID исторической аптеки", "Название аптеки (текущее по историческому ID)", "Происхождение аптеки", "Статус основания", "Статус проводки"),
            report.entries.map { listOf(it.id, it.pharmacistId, it.pharmacistName, it.source, it.sourceId, it.amountKzt,
                it.earnedDeltaKzt, it.currency, it.contextKey, it.balanceAfterKzt, it.createdAt, it.historicalPharmacyId,
                it.historicalPharmacyName, it.historicalPharmacyStatus, it.basisStatus, it.state) }),
    ))

    fun payouts(report: PayoutReport): ByteArray = ReportWorkbook.render(listOf(
        parameters("Пакеты выплат", listOf("С (дата формирования пакета)" to report.period.from,
            "По (включительно)" to report.period.to, "Часовой пояс" to report.period.timezone,
            "Срез до (не включая)" to report.asOfExclusive, "Сформирован" to report.generatedAt,
            "Статус" to report.statusFilter, "Область" to "Явная ALL", "Пакетов" to report.batches.size,
            "Строк состава" to report.items.size, "Методика" to report.notice)),
        ReportSheet("Сводка", listOf("Показатель", "Значение"), listOf(
            listOf("Сумма пакетов, KZT", FinanceReportReader.exactSum(report.batches.map { it.amount })),
            listOf("Сумма строк состава, KZT (для сверки, не добавляется к пакетам)", FinanceReportReader.exactSum(report.items.map { it.amount })),
        )),
        ReportSheet("Данные", listOf("ID пакета", "Период источника", "Статус", "Фармацевтов (источник)", "Сумма, KZT",
            "Строк (источник)", "ID проверяющего", "Проверяющий", "Подтверждено", "Создан", "Обновлён"),
            report.batches.map { listOf(it.id, it.period, it.status, it.pharmacists, it.amount, it.items,
                it.reviewerId, it.reviewerName, it.approvedAt, it.createdAt, it.updatedAt) }),
        ReportSheet("Состав пакетов", listOf("ID строки", "ID пакета", "ID фармацевта", "ФИО (источник)",
            "Историческое название аптеки (источник)", "Город (источник)", "Чеков (источник)", "Правил (источник)",
            "Сумма, KZT", "Предупреждение", "Создана", "ID исторической аптеки (не записан)"),
            report.items.map { listOf(it.id, it.batchId, it.pharmacistId, it.pharmacistName, it.pharmacy, it.city,
                it.receipts, it.rules, it.amount, it.flag, it.createdAt, it.historicalPharmacyId) }),
    ))

    fun pharmacists(report: PharmacistReport): ByteArray = ReportWorkbook.render(listOf(
        parameters("Реестр фармацевтов", listOf("Сформирован / срез" to report.generatedAt,
            "Часовой пояс" to "Asia/Almaty", "Область аптек" to scopeLabel(report.scopePharmacyIds),
            "Статус профиля" to report.statusFilter, "Фильтр аптеки" to report.pharmacyFilter,
            "Строк" to report.entries.size, "Глобальные финансовые поля разрешены" to report.globalFinancialFieldsIncluded,
            "Методика" to report.notice)),
        ReportSheet("Сводка", listOf("Показатель", "Значение"), listOf(
            listOf("Фармацевтов (без размножения по активностям)", report.entries.size),
            listOf("Текущий баланс, KZT (только при ALL financial_data)",
                if (report.globalFinancialFieldsIncluded) FinanceReportReader.exactSum(report.entries.mapNotNull { it.balance }) else null),
        )),
        ReportSheet("Данные", listOf("ID", "ФИО", "ИИН (по праву)", "Телефон (по праву)", "Статус профиля", "Уровень",
            "Пароль установлен", "Тип входа", "Логин (по праву)", "Состояние доступа", "ID текущей аптеки", "Текущая аптека",
            "ID сети", "Сеть", "Город профиля", "Город аптеки", "Адрес", "Район (не дивизион)", "Чеков 30д",
            "Принято правил 30д", "Начислено 30д, KZT", "Текущий баланс, KZT", "Курсов завершено", "Курсов всего",
            "Дата регистрации", "Создан", "Обновлён"),
            report.entries.map { listOf(it.id, it.name, it.iin, it.phone, it.status, it.tier, it.hasPassword, it.loginType,
                it.loginValue, it.accessStatus, it.currentPharmacyId, it.currentPharmacyName, it.chainId, it.chainName,
                it.city, it.pharmacyCity, it.address, it.district, it.receipts30d, it.rulesAccepted30d, it.earned30d,
                it.balance, it.coursesDone, it.coursesTotal, it.joinedAt, it.createdAt, it.updatedAt) }),
    ))

    private fun parameters(title: String, entries: List<Pair<String, Any?>>) = ReportSheet("Параметры", listOf("Параметр", "Значение"),
        listOf(listOf("Отчёт", title)) + entries.map { listOf(it.first, it.second) })
    private fun scopeLabel(ids: Set<String>?) = ids?.sorted()?.joinToString(", ") ?: "Явная ALL"
}
