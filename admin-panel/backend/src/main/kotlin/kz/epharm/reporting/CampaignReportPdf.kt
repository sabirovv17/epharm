package kz.epharm.reporting

import java.awt.Color
import java.io.ByteArrayOutputStream
import java.time.format.DateTimeFormatter
import java.time.ZoneId
import java.util.Locale
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDDocumentInformation
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component

@Component
class CampaignReportPdf {
    fun render(report: CampaignReport): ByteArray = PDDocument().use { document ->
        document.documentInformation=PDDocumentInformation().apply {
            title="Отчёт по кампании ${report.campaignId}"; author="ePharm"
            subject="Агрегированные рекомендации: подтверждённые показы и действия фармацевта"
        }
        val normal=ClassPathResource("fonts/NotoSans-Regular.ttf").inputStream.use { PDType0Font.load(document,it,true) }
        val bold=ClassPathResource("fonts/NotoSans-ExtraBold.ttf").inputStream.use { PDType0Font.load(document,it,true) }
        val writer=Writer(document,normal,bold)
        try {
            writer.paragraph("ePharm · Отчёт по кампании",20f,true)
            writer.paragraph(report.campaignTitle,16f,true)
            writer.paragraph("Кампания: ${report.campaignId} · Бренд: ${report.brandLabel.ifBlank { "не указан" }}")
            writer.paragraph("Период: ${report.from} — ${report.to} включительно · ${report.timezone}")
            val stamp=DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.of(report.timezone))
            writer.paragraph("Сформирован: ${stamp.format(report.generatedAt)} · Данные до ${stamp.format(report.asOfExclusive)} (не включая)",9f)
            writer.space(12f)
            writer.paragraph("Результаты",14f,true)
            val counts=report.totals
            fun percent(value: Double?) = value?.let { String.format(Locale.forLanguageTag("ru"), "%.1f%%", it) } ?: "—"
            writer.metrics(listOf(
                "Показы с ACK" to counts.confirmed.toString(),
                "Одобрено замен" to report.confirmedSubstitutionSales.toString(),
                "Замены за 0–120 с" to report.confirmedSubstitutionSalesUnder2m.toString(),
                "Конверсия в продажу" to percent(counts.attributedConversionPercent),
            ))
            if (counts.confirmed == 0L) writer.paragraph("Нет подтверждённых показов за выбранный период. Это не доказывает отсутствие работы касс.",10f)
            writer.paragraph("Подтверждённые показы: ${counts.confirmed} · Принято фармацевтом: ${counts.pharmacistAccepted}")
            writer.paragraph("Атрибутированные продажи: ${counts.attributedSales} · Из них за 0–120 с: ${counts.recommendationSalesUnder2m}")
            writer.paragraph("Конверсия за 0–120 с: ${percent(counts.under2mConversionPercent)} · База: подтверждённые показы той же когорты",9f)
            writer.paragraph("Выдано сервером: ${counts.issued} · Без подтверждения: ${counts.unconfirmed}")
            writer.paragraph("Непроверенные заявления о продаже (исключены): ${counts.unverifiedSaleClaims}",9f)
            report.kinds.forEach { kind ->
                writer.paragraph("${kind.label}: показы ${kind.counts.confirmed}, принято фармацевтом ${kind.counts.pharmacistAccepted}, продажи ${kind.counts.attributedSales}, за 0–120 с ${kind.counts.recommendationSalesUnder2m}, конверсия ${percent(kind.counts.attributedConversionPercent)}")
            }
            writer.space(10f)
            writer.chart(report)
            writer.paragraph("Методика и ограничения",14f,true)
            report.methods.forEach { writer.paragraph(it,9f); writer.space(5f) }
            writer.paragraph("По дням · ${report.timezone}",14f,true)
            writer.dayTable(report.days)
        } finally { writer.close() }
        ByteArrayOutputStream().use { output -> document.save(output);output.toByteArray() }
    }

    private class Writer(private val document: PDDocument,private val regular: PDFont,private val bold: PDFont) {
        private var stream: PDPageContentStream?=null
        private var y=0f
        private val left=44f
        private val width=PDRectangle.A4.width-2*left
        private val ink=Color(33,43,48)
        init { page() }
        private fun page() {
            stream?.close()
            val page=PDPage(PDRectangle.A4);document.addPage(page)
            stream=PDPageContentStream(document,page);y=page.mediaBox.height-48f
            text("ePharm · ${document.numberOfPages}",left,24f,8f,regular,Color.GRAY)
        }
        private fun ensure(height: Float) { if (y-height<48f) page() }
        fun space(height: Float) { ensure(height);y-=height }
        // Strip control characters and replace unsupported glyphs rather than fail an entire export.
        private fun safe(value: String,font: PDFont): String = buildString {
            value.codePoints().forEach { point ->
                val char=String(Character.toChars(point))
                append(if (Character.isISOControl(point)) " " else try { font.getStringWidth(char);char } catch (_: IllegalArgumentException) { "?" })
            }
        }
        private fun text(value: String,x: Float,at: Float,size: Float,font: PDFont,color: Color=ink) {
            val content=requireNotNull(stream)
            content.beginText();content.setFont(font,size);content.setNonStrokingColor(color)
            content.newLineAtOffset(x,at);content.showText(value);content.endText()
        }
        fun paragraph(value: String,size: Float=10f,strong: Boolean=false) {
            val font=if(strong) bold else regular
            val lines=mutableListOf<String>()
            var line=""
            fun flush() { lines.add(line);line="" }
            for (word in safe(value,font).trim().split(Regex("\\s+"))) {
                val candidate=if(line.isEmpty()) word else "$line $word"
                if(font.getStringWidth(candidate)/1000*size<=width) { line=candidate;continue }
                if(line.isNotEmpty()) flush()
                // A long product identifier is the only case that needs character wrapping.
                for(point in word.codePoints().toArray()) {
                    val char=String(Character.toChars(point))
                    if(font.getStringWidth(line+char)/1000*size>width && line.isNotEmpty()) flush()
                    line+=char
                }
            }
            flush()
            // Keep ordinary prose paragraphs together; huge unbroken titles may span pages.
            val height=lines.size*size*1.5f
            if(height<=PDRectangle.A4.height-96f) ensure(height+if(strong) 20f else 0f)
            lines.forEach { part -> ensure(size*1.5f);text(part,left,y,size,font);y-=size*1.5f }
        }
        fun metrics(items: List<Pair<String,String>>) {
            ensure(78f)
            val gap=8f
            val cardWidth=(width-gap*(items.size-1))/items.size
            items.forEachIndexed { index,(label,value) ->
                val x=left+index*(cardWidth+gap)
                val content=requireNotNull(stream)
                content.setNonStrokingColor(Color(242,247,246))
                content.addRect(x,y-64f,cardWidth,64f);content.fill()
                text(safe(label,regular),x+8f,y-16f,7.2f,regular)
                // Keep large real counts inside their card too.
                val size=minOf(22f,(cardWidth-16f)*1000f/bold.getStringWidth(value))
                text(value,x+8f,y-46f,size,bold,Color(0,109,112))
            }
            y-=78f
        }
        fun dayTable(days: List<ReportDay>) {
            val widths=listOf(88f,59f,67f,65f,79f,76f,width-434f)
            fun row(values: List<String>,header: Boolean=false,shaded: Boolean=false) {
                val height=if(header) 22f else 17f
                val content=requireNotNull(stream)
                if(header || shaded) {
                    content.setNonStrokingColor(if(header) Color(225,237,236) else Color(247,249,249))
                    content.addRect(left,y-height+5f,width,height);content.fill()
                }
                var x=left
                values.forEachIndexed { index,value ->
                    val font=if(header) bold else regular
                    val fontSize=minOf(if(header) 8f else 9f,(widths[index]-12f)*1000f/font.getStringWidth(value))
                    val at=if(index==0) x+6f else x+widths[index]-6f-font.getStringWidth(value)*fontSize/1000f
                    text(value,at,y-10f,fontSize,font);x+=widths[index]
                }
                y-=height
            }
            fun header() { row(listOf("Дата","Выдано","Без ACK","Показы","Принято*","Продажи","0–120 с"),header=true) }
            ensure(45f);header()
            days.forEachIndexed { index,day ->
                if(y-22f<48f) { page();header() }
                val c=day.counts
                row(listOf(day.date.toString(),c.issued.toString(),c.unconfirmed.toString(),c.confirmed.toString(),c.pharmacistAccepted.toString(),c.attributedSales.toString(),c.recommendationSalesUnder2m.toString()),shaded=index%2==1)
            }
            space(8f)
            paragraph("* Принято фармацевтом — действие в окне, не подтверждённая продажа и не выплата.",8f)
        }
        fun chart(report: CampaignReport) {
            ensure(190f)
            paragraph("Динамика показов и подтверждённых продаж",12f,true)
            val top=y-8f;val bottom=top-105f
            val x0=left+30f;val chartWidth=width-40f
            val max=report.days.maxOfOrNull { it.counts.confirmed }?.coerceAtLeast(1) ?: 1L
            val content=requireNotNull(stream)
            content.setStrokingColor(Color.LIGHT_GRAY);content.setLineWidth(.6f)
            content.moveTo(x0,top);content.lineTo(x0,bottom);content.lineTo(x0+chartWidth,bottom);content.stroke()
            text(max.toString(),left,top,8f,regular);text("0",left,bottom,8f,regular)
            for (step in 1..3) {
                val at=bottom+105f*step/4
                content.setStrokingColor(Color(231,236,236));content.moveTo(x0,at);content.lineTo(x0+chartWidth,at);content.stroke()
            }
            val series=listOf(Color(0,125,128) to { day: ReportDay -> day.counts.confirmed },Color(172,76,35) to { day: ReportDay -> day.counts.attributedSales },Color(96,64,160) to { day: ReportDay -> day.counts.recommendationSalesUnder2m })
            series.forEach { (color,metric) ->
                content.setStrokingColor(color);content.setLineWidth(1.4f)
                report.days.forEachIndexed { index,day ->
                    val x=x0+chartWidth*index/(report.days.size-1).coerceAtLeast(1)
                    val at=bottom+105f*metric(day)/max
                    if(index==0) content.moveTo(x,at) else content.lineTo(x,at)
                    if(report.days.size==1) {content.lineTo(x+2,at)}
                }
                content.stroke()
            }
            val format=DateTimeFormatter.ofPattern("dd.MM.yy")
            text(report.from.format(format),x0,bottom-14f,8f,regular)
            text(report.to.format(format),x0+chartWidth-42f,bottom-14f,8f,regular)
            y=bottom-30f
            paragraph("Бирюзовый: показы · Коричневый: все продажи · Фиолетовый: продажи за 0–120 с",8f)
            space(12f)
        }
        fun close() { stream?.close() }
    }
}
