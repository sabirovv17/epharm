package kz.epharm.training

import kz.epharm.shared.storage.InMemoryMediaStorage
import kz.epharm.training.domain.CertificateStatus
import kz.epharm.training.domain.TrainingFormat
import kz.epharm.training.dto.CertificateVerificationDto
import kz.epharm.training.service.TrainingCertificatePdfService
import org.apache.pdfbox.Loader
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.imageio.ImageIO

class TrainingCertificatePdfServiceTest {
    @Test
    fun `renders selected branded certificate with automatic program data`() {
        val storage = InMemoryMediaStorage()
        val coverUrl = storage.upload(courseCoverBytes(), "image/png", "cover.png")
        val epharmLogoUrl = storage.upload(logoBytes("ePharm", "ОБУЧЕНИЕ ДЛЯ ФАРМАЦЕВТОВ"), "image/png", "epharm.png")
        val partnerLogoUrl = storage.upload(logoBytes("INKAR", "ЗАБОТА В ОСНОВЕ РЕШЕНИЙ"), "image/png", "partner.png")
        val issuedAt = Instant.parse("2026-09-29T08:00:00Z")
        val certificate = CertificateVerificationDto(
            number = "EPH-2026-000154",
            pharmacistName = "Грущак Василий Григорьевич",
            programName = "Безопасный отпуск лекарственных средств",
            format = TrainingFormat.online,
            issuedAt = issuedAt,
            expiresAt = issuedAt.plus(1_095, ChronoUnit.DAYS),
            score = 100,
            signerName = "Руководитель учебного центра",
            partnerCompanyName = "INKAR",
            coverUrl = coverUrl,
            epharmLogoUrl = epharmLogoUrl,
            partnerLogoUrl = partnerLogoUrl,
            templateName = "modern_ribbon",
            status = CertificateStatus.valid,
            valid = true,
        )

        val pdf = TrainingCertificatePdfService("https://epharm.inkar.kz", storage)
            .render(UUID.fromString("2af1fbd9-e8cc-4dbf-84cc-1ef32735987f"), certificate)

        assertThat(String(pdf.copyOfRange(0, 4))).isEqualTo("%PDF")
        Loader.loadPDF(pdf).use { document ->
            assertThat(document.numberOfPages).isEqualTo(1)
            assertThat(document.documentInformation.title).isEqualTo("Сертификат EPH-2026-000154")
        }
        val output = Path.of("build", "reports", "certificate-preview.pdf")
        Files.createDirectories(output.parent)
        Files.write(output, pdf)
    }

    private fun logoBytes(title: String, subtitle: String): ByteArray {
        val image = BufferedImage(460, 180, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            graphics.composite = AlphaComposite.SrcOver
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.color = Color.WHITE
            graphics.font = Font("SansSerif", Font.BOLD, 94)
            val titleWidth = graphics.fontMetrics.stringWidth(title)
            graphics.drawString(title, (image.width - titleWidth) / 2, 100)
            graphics.font = Font("SansSerif", Font.BOLD, 14)
            val subtitleWidth = graphics.fontMetrics.stringWidth(subtitle)
            graphics.drawString(subtitle, (image.width - subtitleWidth) / 2, 135)
        } finally {
            graphics.dispose()
        }
        return ByteArrayOutputStream().use { output ->
            ImageIO.write(image, "png", output)
            output.toByteArray()
        }
    }

    private fun courseCoverBytes(): ByteArray {
        val image = BufferedImage(1_200, 800, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.paint = GradientPaint(0f, 0f, Color(4, 75, 81), image.width.toFloat(), image.height.toFloat(), Color(55, 191, 174))
            graphics.fillRect(0, 0, image.width, image.height)
            graphics.color = Color(255, 255, 255, 65)
            graphics.fillOval(610, -120, 620, 620)
            graphics.color = Color(255, 255, 255, 45)
            graphics.fillOval(760, 330, 520, 520)
        } finally {
            graphics.dispose()
        }
        return ByteArrayOutputStream().use { output ->
            ImageIO.write(image, "png", output)
            output.toByteArray()
        }
    }
}
