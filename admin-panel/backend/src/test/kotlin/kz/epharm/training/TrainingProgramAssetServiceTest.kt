package kz.epharm.training

import kz.epharm.shared.error.AppException
import kz.epharm.shared.storage.MediaStorage
import kz.epharm.training.service.TrainingProgramAssetService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockMultipartFile
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class TrainingProgramAssetServiceTest {
    @Test
    fun `uploads valid cover photo`() {
        val storage = RecordingStorage()
        val file = MockMultipartFile("file", "cover.png", "image/png", imageBytes(800, 600))

        val result = TrainingProgramAssetService(storage).uploadCover(file)

        assertThat(result.coverUrl).isEqualTo("https://media.test/cover.png")
        assertThat(storage.lastContentType).isEqualTo("image/png")
    }

    @Test
    fun `rejects too small cover`() {
        val file = MockMultipartFile("file", "cover.png", "image/png", imageBytes(120, 120))

        assertThatThrownBy { TrainingProgramAssetService(RecordingStorage()).uploadCover(file) }
            .isInstanceOf(AppException::class.java)
            .hasMessageContaining("240×240")
    }

    @Test
    fun `uploads compact transparent logo for certificate`() {
        val storage = RecordingStorage()
        val file = MockMultipartFile("file", "logo.png", "image/png", imageBytes(120, 64))

        val result = TrainingProgramAssetService(storage).uploadCertificateAsset(file)

        assertThat(result.assetUrl).isEqualTo("https://media.test/logo.png")
    }

    @Test
    fun `rejects image with excessive decompressed pixel count`() {
        val file = MockMultipartFile("file", "cover.png", "image/png", imageBytes(4_000, 2_500))

        assertThatThrownBy { TrainingProgramAssetService(RecordingStorage()).uploadCover(file) }
            .isInstanceOf(AppException::class.java)
            .hasMessageContaining("8000000")
    }

    private fun imageBytes(width: Int, height: Int): ByteArray {
        val output = ByteArrayOutputStream()
        ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", output)
        return output.toByteArray()
    }

    private class RecordingStorage : MediaStorage {
        var lastContentType: String? = null

        override fun upload(bytes: ByteArray, contentType: String, originalName: String): String {
            lastContentType = contentType
            return "https://media.test/$originalName"
        }

        override fun delete(url: String) = Unit

        override fun read(url: String): ByteArray? = null
    }
}
