package kz.epharm.reporting.producers

import kz.epharm.reporting.ReportCounts
import kz.epharm.reporting.xlsx.ReportSheet
import kz.epharm.reporting.xlsx.ReportWorkbook
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xddf.usermodel.chart.AxisCrosses
import org.apache.poi.xddf.usermodel.chart.AxisPosition
import org.apache.poi.xddf.usermodel.chart.ChartTypes
import org.apache.poi.xddf.usermodel.chart.LegendPosition
import org.apache.poi.xddf.usermodel.chart.XDDFDataSourcesFactory
import org.springframework.stereotype.Component

@Component
class ProducerReportWorkbook {
    fun render(report: ProducerMotivationReport): ByteArray = ReportWorkbook.render(listOf(
        ReportSheet("Параметры", listOf("Параметр", "Значение"), listOf(
            listOf("Отчёт", "Мотивационные кампании выбранного производителя"), listOf("ID производителя", report.producerId),
            listOf("Текущее название компании", report.producerName), listOf("Профиль компании заполнен", report.profileComplete),
            listOf("С", report.from), listOf("По (включительно)", report.to), listOf("Часовой пояс", report.timezone),
            listOf("Сформирован", report.generatedAt), listOf("Срез до (не включая)", report.asOfExclusive),
            listOf("Кампаний с подтверждёнными интервалами", report.campaigns.size),
            listOf("Знаменатель конверсии", "Сумма подтверждённых показов той же когорты; проценты кампаний не складываются"),
            listOf("Выручка / упаковки / банковская оплата", "Нет подтверждённого показателя в исходном отчёте"),
        ) + report.methods.map { listOf("Методика", it) }),
        ReportSheet("Сводка", columns("Разрез"), listOf(counts("Все рекомендации", report.totals)) + report.kinds.map { counts(it.label, it.counts) }),
        ReportSheet("Дни", columns("Дата"), report.days.map { counts(it.date, it.counts) }),
        ReportSheet("Кампании", listOf("ID кампании", "Кампания", "Подпись бренда (не ID производителя)", "С", "По", "Часовой пояс", "Срез до") + columns("Разрез"),
            report.campaigns.map { (r, _) -> listOf(r.campaignId, r.campaignTitle, r.brandLabel, r.from, r.to, r.timezone, r.asOfExclusive) + counts("Все рекомендации", r.totals) }),
        ReportSheet("Интервалы связей", listOf("ID кампании", "ID связи", "Версия связи", "Вся история", "Подтверждено с", "Подтверждено по", "Включено с", "Включено по"),
            report.campaigns.flatMap { c -> c.intervals.map { listOf(c.report.campaignId, it.bindingId, it.revision, it.wholeHistory,
                it.approvedFrom, it.approvedTo, it.includedFrom, it.includedTo) } }),
        ReportSheet("Дни кампаний", listOf("ID кампании") + columns("Дата"), report.campaigns.flatMap { c -> c.report.days.map {
            listOf(c.report.campaignId) + counts(it.date, it.counts) } }),
        ReportSheet("Типы кампаний", listOf("ID кампании") + columns("Тип"), report.campaigns.flatMap { c -> c.report.kinds.map {
            listOf(c.report.campaignId) + counts(it.label, it.counts) } }),
    )) { book ->
        if (report.days.isNotEmpty()) {
            val sheet = book.getSheet("Дни")
            val drawing = sheet.createDrawingPatriarch()
            val chart = drawing.createChart(drawing.createAnchor(0,0,0,0,12,1,25,19))
            chart.setTitleText("Подтверждённые показы и продажи по дням"); chart.setTitleOverlay(false)
            chart.getOrAddLegend().position = LegendPosition.BOTTOM
            val category = chart.createCategoryAxis(AxisPosition.BOTTOM)
            val values = chart.createValueAxis(AxisPosition.LEFT).apply { crosses = AxisCrosses.AUTO_ZERO }
            val data = chart.createData(ChartTypes.LINE, category, values)
            val dates = XDDFDataSourcesFactory.fromStringCellRange(sheet, CellRangeAddress(1,report.days.size,0,0))
            for ((column, title) in listOf(3 to "Показы с ACK", 5 to "Атрибутированные продажи", 7 to "Продажи за 0–120 секунд"))
                data.addSeries(dates, XDDFDataSourcesFactory.fromNumericCellRange(sheet, CellRangeAddress(1,report.days.size,column,column))).setTitle(title,null)
            chart.plot(data)
        }
    }
    private fun columns(label: String) = listOf(label, "Выдано сервером", "Без подтверждения показа", "Показы с ACK", "Принято фармацевтом",
        "Атрибутированные продажи", "Непроверенные заявления (исключены)", "Продажи за 0–120 секунд", "Конверсия в продажу, %", "Конверсия за 0–120 секунд, %")
    private fun counts(label: Any, c: ReportCounts): List<Any?> = listOf(label,c.issued,c.unconfirmed,c.confirmed,c.pharmacistAccepted,
        c.attributedSales,c.unverifiedSaleClaims,c.recommendationSalesUnder2m,c.attributedConversionPercent,c.under2mConversionPercent)
}
