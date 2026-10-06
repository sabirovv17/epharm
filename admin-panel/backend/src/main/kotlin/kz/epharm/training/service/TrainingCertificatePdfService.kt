package kz.epharm.training.service

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import kz.epharm.shared.storage.MediaStorage
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
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.imageio.ImageIO

@Service
class TrainingCertificatePdfService(
    @Value("\${app.public-base-url:https://epharm.inkar.kz}") private val publicBaseUrl: String,
    private val mediaStorage: MediaStorage,
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
            // Noto Sans covers Kazakh Cyrillic (Ә, Ғ, Қ, Ң, Ұ, Ү, Һ, І), which
            // the Manrope subset used by the first template did not include.
            val font = ClassPathResource("fonts/NotoSans-Regular.ttf").inputStream.use {
                PDType0Font.load(document, it, true)
            }
            val boldFont = ClassPathResource("fonts/NotoSans-ExtraBold.ttf").inputStream.use {
                PDType0Font.load(document, it, true)
            }
            val width = page.mediaBox.width
            val height = page.mediaBox.height
            val ink = Color(10, 45, 52)
            val teal = Color(0, 154, 143)
            val tealDark = Color(4, 75, 81)
            val mint = Color(220, 247, 243)
            val muted = Color(92, 116, 128)
            val border = Color(218, 230, 229)

            PDPageContentStream(document, page).use { canvas ->
                canvas.setNonStrokingColor(Color(245, 249, 248))
                canvas.addRect(0f, 0f, width, height)
                canvas.fill()

                canvas.setNonStrokingColor(Color.WHITE)
                roundedRect(canvas, 12f, 12f, width - 24f, height - 24f, 11f)
                canvas.fill()
                canvas.setStrokingColor(border)
                canvas.setLineWidth(0.8f)
                roundedRect(canvas, 12f, 12f, width - 24f, height - 24f, 11f)
                canvas.stroke()

                canvas.setNonStrokingColor(tealDark)
                roundedRect(canvas, 22f, height - 158f, width - 44f, 136f, 9f)
                canvas.fill()

                certificate.coverUrl?.let(::loadImage)?.let { image ->
                    canvas.saveGraphicsState()
                    roundedRect(canvas, 22f, height - 158f, width - 44f, 136f, 9f)
                    canvas.clip()
                    canvas.setGraphicsStateParameters(alpha(0.16f))
                    drawImageCover(document, canvas, image, 22f, height - 158f, width - 44f, 136f)
                    canvas.restoreGraphicsState()
                }

                val epharmLogo = certificate.epharmLogoUrl?.let(::loadImage)
                if (epharmLogo != null) {
                    drawImageContained(document, canvas, epharmLogo, 43f, height - 136f, 154f, 91f)
                } else {
                    canvas.setNonStrokingColor(Color.WHITE)
                    emphasizedText(canvas, boldFont, 27f, "ePharm", 48f, height - 91f, Color.WHITE)
                    emphasizedText(canvas, boldFont, 7.2f, "ОБУЧЕНИЕ ДЛЯ ФАРМАЦЕВТОВ", 49f, height - 111f, Color.WHITE)
                }
                canvas.setStrokingColor(Color(89, 196, 190))
                canvas.setLineWidth(1.2f)
                canvas.moveTo(208f, height - 137f)
                canvas.lineTo(208f, height - 43f)
                canvas.moveTo(width - 208f, height - 137f)
                canvas.lineTo(width - 208f, height - 43f)
                canvas.stroke()

                canvas.setNonStrokingColor(Color.WHITE)
                centeredEmphasized(canvas, boldFont, 47f, "СЕРТИФИКАТ", height - 89f, width, Color.WHITE)
                centeredEmphasized(
                    canvas,
                    boldFont,
                    8.2f,
                    "З Н А Н И Я   С Е Г О Д Н Я   —   З Д О Р О В Ь Е   З А В Т Р А",
                    height - 119f,
                    width,
                    Color(187, 231, 227),
                    0.18f,
                )

                val partnerLogo = certificate.partnerLogoUrl?.let(::loadImage)
                if (partnerLogo != null) {
                    drawImageContained(document, canvas, partnerLogo, width - 194f, height - 136f, 154f, 91f)
                } else {
                    canvas.setNonStrokingColor(Color.WHITE)
                    centeredEmphasizedInBox(
                        canvas,
                        boldFont,
                        20f,
                        certificate.partnerCompanyName.uppercase(),
                        width - 199f,
                        166f,
                        height - 91f,
                        Color.WHITE,
                    )
                    centeredInBox(canvas, font, 7.3f, "КОМПАНИЯ-ПАРТНЁР", width - 199f, 166f, height - 111f)
                }

                canvas.setNonStrokingColor(muted)
                text(canvas, font, 11.5f, "Настоящим подтверждается, что", 42f, height - 196f)
                canvas.setNonStrokingColor(ink)
                fittedEmphasizedText(canvas, boldFont, 31f, 19f, certificate.pharmacistName, 42f, height - 239f, 535f, ink)
                canvas.setNonStrokingColor(muted)
                text(canvas, font, 11.5f, "завершил программу обучения", 42f, height - 273f)

                canvas.setNonStrokingColor(teal)
                roundedRect(canvas, 42f, height - 335f, 5f, 55f, 2.5f)
                canvas.fill()
                canvas.setNonStrokingColor(teal)
                val courseLines = wrappedFittedLines(boldFont, certificate.programName, 27f, 18f, 510f, 2)
                courseLines.second.forEachIndexed { index, line ->
                    emphasizedText(canvas, boldFont, courseLines.first, line, 57f, height - 310f - index * (courseLines.first + 5f), teal)
                }

                val format = when (certificate.format) {
                    TrainingFormat.online -> "Онлайн-формат"
                    TrainingFormat.hybrid -> "Гибридный формат"
                    TrainingFormat.offline -> "Очный формат"
                }
                canvas.setNonStrokingColor(mint)
                roundedRect(canvas, 42f, 137f, 106f, 27f, 13.5f)
                canvas.fill()
                canvas.setNonStrokingColor(tealDark)
                emphasizedText(canvas, boldFont, 9.5f, format.uppercase(), 55f, 146f, tealDark)
                canvas.setNonStrokingColor(muted)
                text(canvas, font, 10f, "Результат", 42f, 103f)
                canvas.setNonStrokingColor(teal)
                emphasizedText(canvas, boldFont, 33f, "${certificate.score ?: 100}%", 42f, 67f, teal)

                canvas.saveGraphicsState()
                canvas.setGraphicsStateParameters(alpha(0.11f))
                canvas.setNonStrokingColor(teal)
                emphasizedText(canvas, boldFont, 105f, "${certificate.score ?: 100}%", 250f, 65f, teal)
                canvas.restoreGraphicsState()

                canvas.setStrokingColor(Color(198, 218, 218))
                canvas.setLineWidth(1f)
                canvas.moveTo(width - 226f, 76f)
                canvas.lineTo(width - 226f, height - 174f)
                canvas.stroke()
                canvas.setNonStrokingColor(ink)
                centeredEmphasizedInBox(canvas, boldFont, 15.5f, "Проверить сертификат", width - 210f, 182f, height - 203f, ink)
                val qrSize = 136f
                val qrX = width - 188f
                val qrY = 174f
                val qr = LosslessFactory.createFromImage(document, qrImage(verificationUrl, 420))
                canvas.drawImage(qr, qrX, qrY, qrSize, qrSize)
                drawQrCorners(canvas, qrX - 7f, qrY - 7f, qrSize + 14f, teal)
                canvas.setNonStrokingColor(muted)
                wrappedLines(font, "Отсканируйте QR-код для проверки подлинности сертификата", 10f, 165f)
                    .take(3)
                    .forEachIndexed { index, line ->
                        text(canvas, font, 9.5f, line, width - 199f, 145f - index * 13f)
                    }

                canvas.setNonStrokingColor(mint)
                roundedRect(canvas, 22f, 22f, width - 44f, 39f, 6f)
                canvas.fill()
                canvas.setNonStrokingColor(ink)
                text(canvas, font, 9.1f, "№ ${certificate.number}", 42f, 37f)
                text(canvas, font, 9.1f, "Выдан ${dateFormatter.format(certificate.issuedAt)}", 222f, 37f)
                fittedText(canvas, font, 9.1f, 7.3f, "Подписант: ${certificate.signerName}", 382f, 37f, 265f)
                val validUntil = certificate.expiresAt?.let { dateFormatter.format(it) } ?: "бессрочно"
                text(canvas, font, 9.1f, "Действителен до: $validUntil", width - 186f, 37f)
                canvas.setStrokingColor(Color(104, 175, 170))
                canvas.setLineWidth(0.8f)
                listOf(202f, 363f, width - 205f).forEach { x ->
                    canvas.moveTo(x, 29f)
                    canvas.lineTo(x, 54f)
                }
                canvas.stroke()
            }
            ByteArrayOutputStream().use { output ->
                document.save(output)
                output.toByteArray()
            }
        }
    }

    private fun loadImage(url: String): BufferedImage? {
        val bytes = mediaStorage.read(url)?.takeIf { it.size <= MAX_CERTIFICATE_ASSET_BYTES } ?: return null
        return runCatching {
            ImageIO.createImageInputStream(ByteArrayInputStream(bytes))?.use { stream ->
                val readers = ImageIO.getImageReaders(stream)
                if (!readers.hasNext()) return@use null
                val reader = readers.next()
                try {
                    reader.input = stream
                    val width = reader.getWidth(0)
                    val height = reader.getHeight(0)
                    if (width <= 0 || height <= 0 || width > MAX_IMAGE_SIDE || height > MAX_IMAGE_SIDE ||
                        width.toLong() * height > MAX_IMAGE_PIXELS
                    ) {
                        null
                    } else {
                        reader.read(0)
                    }
                } finally {
                    reader.dispose()
                }
            }
        }.getOrNull()
    }

    private fun alpha(value: Float) = PDExtendedGraphicsState().apply {
        nonStrokingAlphaConstant = value
        strokingAlphaConstant = value
    }

    private fun drawImageContained(
        document: PDDocument,
        canvas: PDPageContentStream,
        image: BufferedImage,
        x: Float,
        y: Float,
        boxWidth: Float,
        boxHeight: Float,
    ) {
        val ratio = minOf(boxWidth / image.width, boxHeight / image.height)
        val drawWidth = image.width * ratio
        val drawHeight = image.height * ratio
        val pdfImage = LosslessFactory.createFromImage(document, image)
        canvas.drawImage(pdfImage, x + (boxWidth - drawWidth) / 2f, y + (boxHeight - drawHeight) / 2f, drawWidth, drawHeight)
    }

    private fun drawImageCover(
        document: PDDocument,
        canvas: PDPageContentStream,
        image: BufferedImage,
        x: Float,
        y: Float,
        boxWidth: Float,
        boxHeight: Float,
    ) {
        val ratio = maxOf(boxWidth / image.width, boxHeight / image.height)
        val drawWidth = image.width * ratio
        val drawHeight = image.height * ratio
        val pdfImage = LosslessFactory.createFromImage(document, image)
        canvas.saveGraphicsState()
        canvas.addRect(x, y, boxWidth, boxHeight)
        canvas.clip()
        canvas.drawImage(pdfImage, x + (boxWidth - drawWidth) / 2f, y + (boxHeight - drawHeight) / 2f, drawWidth, drawHeight)
        canvas.restoreGraphicsState()
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
                for (y in 0 until size) image.setRGB(x, y, if (matrix[x, y]) Color(16, 43, 51).rgb else Color.WHITE.rgb)
            }
        }
    }

    private fun centered(canvas: PDPageContentStream, font: PDFont, size: Float, value: String, y: Float, width: Float) =
        text(canvas, font, size, value, (width - textWidth(font, size, value)) / 2f, y)

    private fun centeredEmphasized(
        canvas: PDPageContentStream,
        font: PDFont,
        size: Float,
        value: String,
        y: Float,
        width: Float,
        color: Color,
        strokeWidth: Float? = null,
    ) = emphasizedText(canvas, font, size, value, (width - textWidth(font, size, value)) / 2f, y, color, strokeWidth)

    private fun centeredInBox(
        canvas: PDPageContentStream,
        font: PDFont,
        preferredSize: Float,
        value: String,
        x: Float,
        boxWidth: Float,
        y: Float,
    ) {
        var size = preferredSize
        while (size > 9f && textWidth(font, size, value) > boxWidth) size -= 1f
        text(canvas, font, size, value, x + (boxWidth - textWidth(font, size, value)) / 2f, y)
    }

    private fun centeredEmphasizedInBox(
        canvas: PDPageContentStream,
        font: PDFont,
        preferredSize: Float,
        value: String,
        x: Float,
        boxWidth: Float,
        y: Float,
        color: Color,
        strokeWidth: Float? = null,
    ) {
        var size = preferredSize
        while (size > 8f && textWidth(font, size, value) > boxWidth) size -= 1f
        emphasizedText(canvas, font, size, value, x + (boxWidth - textWidth(font, size, value)) / 2f, y, color, strokeWidth)
    }

    private fun fittedText(
        canvas: PDPageContentStream,
        font: PDFont,
        preferredSize: Float,
        minimumSize: Float,
        value: String,
        x: Float,
        y: Float,
        maxWidth: Float,
    ) {
        var size = preferredSize
        while (size > minimumSize && textWidth(font, size, value) > maxWidth) size -= 1f
        text(canvas, font, size, value, x, y)
    }

    private fun fittedEmphasizedText(
        canvas: PDPageContentStream,
        font: PDFont,
        preferredSize: Float,
        minimumSize: Float,
        value: String,
        x: Float,
        y: Float,
        maxWidth: Float,
        color: Color,
        strokeWidth: Float? = null,
    ) {
        var size = preferredSize
        while (size > minimumSize && textWidth(font, size, value) > maxWidth) size -= 1f
        emphasizedText(canvas, font, size, value, x, y, color, strokeWidth)
    }

    private fun wrappedFittedLines(
        font: PDFont,
        value: String,
        preferredSize: Float,
        minimumSize: Float,
        maxWidth: Float,
        maxLines: Int,
    ): Pair<Float, List<String>> {
        var size = preferredSize
        var lines = wrappedLines(font, value, size, maxWidth)
        while (size > minimumSize && lines.size > maxLines) {
            size -= 1f
            lines = wrappedLines(font, value, size, maxWidth)
        }
        return size to lines.take(maxLines)
    }

    private fun wrappedLines(font: PDFont, value: String, size: Float, maxWidth: Float): List<String> {
        val lines = mutableListOf<String>()
        var current = ""
        value.trim().split(Regex("\\s+")).forEach { word ->
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (current.isNotEmpty() && textWidth(font, size, candidate) > maxWidth) {
                lines += current
                current = word
            } else {
                current = candidate
            }
        }
        if (current.isNotEmpty()) lines += current
        return lines.ifEmpty { listOf("") }
    }

    private fun text(canvas: PDPageContentStream, font: PDFont, size: Float, value: String, x: Float, y: Float) {
        canvas.beginText()
        canvas.setFont(font, size)
        canvas.newLineAtOffset(x, y)
        canvas.showText(value)
        canvas.endText()
    }

    private fun emphasizedText(
        canvas: PDPageContentStream,
        font: PDFont,
        size: Float,
        value: String,
        x: Float,
        y: Float,
        color: Color,
        @Suppress("UNUSED_PARAMETER")
        strokeWidth: Float? = null,
    ) {
        canvas.setNonStrokingColor(color)
        text(canvas, font, size, value, x, y)
    }

    private fun roundedRect(
        canvas: PDPageContentStream,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        radius: Float,
    ) {
        val r = minOf(radius, width / 2f, height / 2f)
        val c = r * 0.55228475f
        canvas.moveTo(x + r, y)
        canvas.lineTo(x + width - r, y)
        canvas.curveTo(x + width - r + c, y, x + width, y + r - c, x + width, y + r)
        canvas.lineTo(x + width, y + height - r)
        canvas.curveTo(x + width, y + height - r + c, x + width - r + c, y + height, x + width - r, y + height)
        canvas.lineTo(x + r, y + height)
        canvas.curveTo(x + r - c, y + height, x, y + height - r + c, x, y + height - r)
        canvas.lineTo(x, y + r)
        canvas.curveTo(x, y + r - c, x + r - c, y, x + r, y)
        canvas.closePath()
    }

    private fun drawQrCorners(
        canvas: PDPageContentStream,
        x: Float,
        y: Float,
        size: Float,
        color: Color,
    ) {
        val segment = 17f
        canvas.setStrokingColor(color)
        canvas.setLineWidth(3f)
        canvas.moveTo(x, y + segment)
        canvas.lineTo(x, y)
        canvas.lineTo(x + segment, y)
        canvas.moveTo(x + size - segment, y)
        canvas.lineTo(x + size, y)
        canvas.lineTo(x + size, y + segment)
        canvas.moveTo(x + size, y + size - segment)
        canvas.lineTo(x + size, y + size)
        canvas.lineTo(x + size - segment, y + size)
        canvas.moveTo(x + segment, y + size)
        canvas.lineTo(x, y + size)
        canvas.lineTo(x, y + size - segment)
        canvas.stroke()
    }

    private fun textWidth(font: PDFont, size: Float, value: String): Float =
        font.getStringWidth(value) / 1_000f * size

    companion object {
        private const val MAX_CERTIFICATE_ASSET_BYTES = 5 * 1024 * 1024
        private const val MAX_IMAGE_SIDE = 6_000
        private const val MAX_IMAGE_PIXELS = 8_000_000L
        private val dateFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
            .withZone(ZoneId.of("Asia/Almaty"))
    }
}
