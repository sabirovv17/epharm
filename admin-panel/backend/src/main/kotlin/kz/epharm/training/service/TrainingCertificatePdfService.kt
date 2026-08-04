package kz.epharm.training.service

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import kz.epharm.training.domain.TrainingFormat
import kz.epharm.training.dto.CertificateVerificationDto
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDDocumentInformation
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

@Service
class TrainingCertificatePdfService(
    @Value("\${app.public-base-url:https://epharm.inkar.kz}") private val publicBaseUrl: String,
) {
    fun render(token: UUID, certificate: CertificateVerificationDto): ByteArray {
        val verificationUrl = "${publicBaseUrl.trimEnd('/')}/api/public/training/certificates/$token"
        return PDDocument().use { document ->
            document.documentInformation = PDDocumentInformation().apply {
                title = "Сертификат ${certificate.number}"
                author = "ePharm"
                subject = certificate.programName
            }
            val page = PDPage(PDRectangle(PDRectangle.A4.height, PDRectangle.A4.width))
            document.addPage(page)
            val font = ClassPathResource("fonts/Manrope-Variable.ttf").inputStream.use {
                PDType0Font.load(document, it, true)
            }
            val width = page.mediaBox.width
            val height = page.mediaBox.height
            val green = Color(28, 108, 88)
            val ink = Color(34, 35, 31)
            val muted = Color(101, 103, 96)

            PDPageContentStream(document, page).use { canvas ->
                canvas.setStrokingColor(green)
                canvas.setLineWidth(3f)
                canvas.addRect(24f, 24f, width - 48f, height - 48f)
                canvas.stroke()
                canvas.setStrokingColor(Color(214, 224, 218))
                canvas.setLineWidth(1f)
                canvas.addRect(34f, 34f, width - 68f, height - 68f)
                canvas.stroke()

                canvas.setNonStrokingColor(green)
                centered(canvas, font, 18f, "ePharm · INKAR", height - 88f, width)
                canvas.setNonStrokingColor(ink)
                centered(canvas, font, 34f, "СЕРТИФИКАТ", height - 146f, width)
                canvas.setNonStrokingColor(muted)
                centered(canvas, font, 13f, "подтверждает успешное завершение программы обучения", height - 178f, width)

                canvas.setNonStrokingColor(ink)
                centeredFitted(canvas, font, 26f, 18f, certificate.pharmacistName, height - 226f, width - 150f, width)
                canvas.setNonStrokingColor(green)
                centeredFitted(canvas, font, 22f, 15f, certificate.programName, height - 276f, width - 170f, width)

                canvas.setNonStrokingColor(muted)
                val format = when (certificate.format) {
                    TrainingFormat.online -> "Онлайн"
                    TrainingFormat.hybrid -> "Гибридный"
                    TrainingFormat.offline -> "Очный"
                }
                centered(
                    canvas,
                    font,
                    12f,
                    "Формат: $format${certificate.score?.let { "  ·  Результат: $it%" }.orEmpty()}",
                    height - 316f,
                    width,
                )

                val qrSize = 112f
                val qrX = width - 180f
                val qrY = 64f
                val qr = LosslessFactory.createFromImage(document, qrImage(verificationUrl, 360))
                canvas.drawImage(qr, qrX, qrY, qrSize, qrSize)

                canvas.setNonStrokingColor(ink)
                text(canvas, font, 11f, "№ ${certificate.number}", 62f, 148f)
                text(canvas, font, 10f, "Выдан: ${dateFormatter.format(certificate.issuedAt)}", 62f, 125f)
                text(canvas, font, 10f, "Подписант: ${certificate.signerName}", 62f, 104f)
                certificate.expiresAt?.let {
                    text(canvas, font, 10f, "Действителен до: ${dateFormatter.format(it)}", 62f, 83f)
                }
                canvas.setNonStrokingColor(muted)
                text(canvas, font, 8.5f, "Проверка подлинности по QR-коду", qrX - 4f, 50f)
            }
            ByteArrayOutputStream().use { output ->
                document.save(output)
                output.toByteArray()
            }
        }
    }

    private fun qrImage(value: String, size: Int): BufferedImage {
        val matrix = MultiFormatWriter().encode(
            value,
            BarcodeFormat.QR_CODE,
            size,
            size,
            mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.CHARACTER_SET to "UTF-8"),
        )
        return BufferedImage(size, size, BufferedImage.TYPE_INT_RGB).also { image ->
            for (x in 0 until size) {
                for (y in 0 until size) image.setRGB(x, y, if (matrix[x, y]) Color.BLACK.rgb else Color.WHITE.rgb)
            }
        }
    }

    private fun centered(
        canvas: PDPageContentStream,
        font: PDFont,
        fontSize: Float,
        value: String,
        y: Float,
        pageWidth: Float,
    ) {
        val x = (pageWidth - textWidth(font, fontSize, value)) / 2f
        text(canvas, font, fontSize, value, x, y)
    }

    private fun centeredFitted(
        canvas: PDPageContentStream,
        font: PDFont,
        preferredSize: Float,
        minimumSize: Float,
        value: String,
        y: Float,
        maxWidth: Float,
        pageWidth: Float,
    ) {
        var size = preferredSize
        while (size > minimumSize && textWidth(font, size, value) > maxWidth) size -= 1f
        centered(canvas, font, size, value, y, pageWidth)
    }

    private fun text(
        canvas: PDPageContentStream,
        font: PDFont,
        fontSize: Float,
        value: String,
        x: Float,
        y: Float,
    ) {
        canvas.beginText()
        canvas.setFont(font, fontSize)
        canvas.newLineAtOffset(x, y)
        canvas.showText(value)
        canvas.endText()
    }

    private fun textWidth(font: PDFont, fontSize: Float, value: String): Float =
        font.getStringWidth(value) / 1_000f * fontSize

    companion object {
        private val dateFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
            .withZone(ZoneId.of("Asia/Almaty"))
    }
}
