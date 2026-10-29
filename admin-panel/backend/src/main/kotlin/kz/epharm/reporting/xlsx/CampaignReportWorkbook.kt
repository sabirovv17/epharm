package kz.epharm.reporting.xlsx

import kz.epharm.reporting.CampaignReport
import kz.epharm.reporting.ReportCounts
import kz.epharm.reporting.exports.CampaignPharmacyCoverage
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xddf.usermodel.chart.AxisCrosses
import org.apache.poi.xddf.usermodel.chart.AxisPosition
import org.apache.poi.xddf.usermodel.chart.ChartTypes
import org.apache.poi.xddf.usermodel.chart.LegendPosition
import org.apache.poi.xddf.usermodel.chart.XDDFDataSourcesFactory
import org.springframework.stereotype.Component

/** XLSX representation of the existing report, with its original cohort and denominator. */
@Component
class CampaignReportWorkbook {
    fun render(report: CampaignReport, pharmacies: List<CampaignPharmacyCoverage>? = null): ByteArray = ReportWorkbook.render(listOf(
        ReportSheet("Параметры", listOf("Параметр", "Значение"), listOf(
            listOf("Отчёт", "Результаты одной кампании"), listOf("ID кампании", report.campaignId),
            listOf("Кампания", report.campaignTitle), listOf("Подпись бренда (не ID производителя)", report.brandLabel),
            listOf("Принадлежность производителю", "Историческая связь с производителем по устойчивому ID не доказана"),
            listOf("С", report.from), listOf("По (включительно)", report.to), listOf("Часовой пояс", report.timezone),
            listOf("Срез до (не включая)", report.asOfExclusive), listOf("Сформирован", report.generatedAt),
            listOf("Число дневных строк", report.days.size), listOf("Знаменатель конверсии", "Подтверждённые показы той же когорты"),
            listOf("Аптек с известным ID в когорте", pharmacies?.count { it.pharmacyId != null }),
            listOf("Выручка / упаковки / банковская оплата", "Нет подтверждённого показателя в исходном отчёте"),
        ) + report.methods.map { listOf("Методика", it) }),
        ReportSheet("Сводка", countColumns("Разрез"), listOf(counts("Все рекомендации", report.totals)) +
            report.kinds.map { counts(it.label, it.counts) }),
        ReportSheet("Данные", countColumns("Дата"), report.days.map { counts(it.date, it.counts) }),
    ) + if (pharmacies == null) emptyList() else listOf(ReportSheet("Охват аптек", listOf("ID аптеки события", "Текущее название по ID", "Текущий город", "Текущий адрес"),
        pharmacies.map { listOf(it.pharmacyId, it.currentName, it.currentCity, it.currentAddress) }))) { book ->
        if (report.days.isNotEmpty()) {
            val sheet = book.getSheet("Данные")
            val drawing = sheet.createDrawingPatriarch()
            val anchor = drawing.createAnchor(0, 0, 0, 0, 12, 1, 25, 19)
            val chart = drawing.createChart(anchor)
            chart.setTitleText("Подтверждённые показы и продажи по дням")
            chart.setTitleOverlay(false)
            chart.getOrAddLegend().position = LegendPosition.BOTTOM
            val categoryAxis = chart.createCategoryAxis(AxisPosition.BOTTOM)
            val valueAxis = chart.createValueAxis(AxisPosition.LEFT)
            valueAxis.crosses = AxisCrosses.AUTO_ZERO
            val data = chart.createData(ChartTypes.LINE, categoryAxis, valueAxis)
            val dates = XDDFDataSourcesFactory.fromStringCellRange(sheet, CellRangeAddress(1, report.days.size, 0, 0))
            listOf(3 to "Показы с ACK", 5 to "Атрибутированные продажи", 7 to "Продажи за 0–120 секунд").forEach { (column, title) ->
                data.addSeries(dates, XDDFDataSourcesFactory.fromNumericCellRange(sheet,
                    CellRangeAddress(1, report.days.size, column, column))).setTitle(title, null)
            }
            chart.plot(data)
        }
    }

    private fun countColumns(label: String) = listOf(label, "Выдано сервером", "Без подтверждения показа", "Показы с ACK",
        "Принято фармацевтом", "Атрибутированные продажи", "Непроверенные заявления (исключены)", "Продажи за 0–120 секунд",
        "Конверсия в продажу, %", "Конверсия за 0–120 секунд, %")

    private fun counts(label: Any, counts: ReportCounts) = listOf(label, counts.issued, counts.unconfirmed, counts.confirmed,
        counts.pharmacistAccepted, counts.attributedSales, counts.unverifiedSaleClaims, counts.recommendationSalesUnder2m,
        counts.attributedConversionPercent, counts.under2mConversionPercent)
}
