package kz.epharm.training.service

import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import kz.epharm.shared.storage.MediaStorage
import kz.epharm.training.dto.TrainingProgramCoverUploadDto
import kz.epharm.training.dto.TrainingProgramCertificateAssetUploadDto
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

@Service
class TrainingProgramAssetService(
    private val mediaStorage: MediaStorage,
) {
    fun uploadCover(file: MultipartFile): TrainingProgramCoverUploadDto {
        val url = uploadImage(file, "обложки", MIN_COVER_SIDE)
        return TrainingProgramCoverUploadDto(coverUrl = url)
    }

    fun uploadCertificateAsset(file: MultipartFile): TrainingProgramCertificateAssetUploadDto {
        val url = uploadImage(file, "логотипа", MIN_LOGO_SIDE)
        return TrainingProgramCertificateAssetUploadDto(assetUrl = url)
    }

    private fun uploadImage(file: MultipartFile, kind: String, minSide: Int): String {
        if (file.isEmpty) invalid("Выберите изображение для $kind")
        if (file.size > MAX_COVER_BYTES) invalid("Размер изображения не должен превышать 5 МБ")

        val extension = file.originalFilename.orEmpty().substringAfterLast('.', "").lowercase()
        val contentType = file.contentType.orEmpty().lowercase()
        val expectedType = SUPPORTED_TYPES[extension]
            ?: invalid("Поддерживаются только JPG и PNG")
        if (contentType != expectedType && contentType != "application/octet-stream") {
            invalid("Расширение файла не соответствует типу изображения")
        }

        val bytes = file.bytes
        val dimensions = imageDimensions(bytes)
        if (dimensions.first < minSide || dimensions.second < minSide) {
            invalid("Размер изображения должен быть не менее ${minSide}×${minSide} пикселей")
        }
        if (dimensions.first > MAX_COVER_SIDE || dimensions.second > MAX_COVER_SIDE) {
            invalid("Размер обложки не должен превышать ${MAX_COVER_SIDE}×${MAX_COVER_SIDE} пикселей")
        }
        if (dimensions.first.toLong() * dimensions.second > MAX_IMAGE_PIXELS) {
            invalid("Изображение не должно превышать $MAX_IMAGE_PIXELS пикселей")
        }

        return mediaStorage.upload(bytes, expectedType, file.originalFilename ?: "training-image.$extension")
    }

    private fun imageDimensions(bytes: ByteArray): Pair<Int, Int> {
        val stream = runCatching {
            ImageIO.createImageInputStream(ByteArrayInputStream(bytes))
        }.getOrNull() ?: invalid("Файл не является корректным изображением")
        stream.use {
            val readers = ImageIO.getImageReaders(it)
            if (!readers.hasNext()) invalid("Файл не является корректным изображением")
            val reader = readers.next()
            return try {
                reader.input = it
                reader.getWidth(0) to reader.getHeight(0)
            } catch (_: Exception) {
                invalid("Файл не является корректным изображением")
            } finally {
                reader.dispose()
            }
        }
    }

    private fun invalid(message: String): Nothing =
        throw AppException(ErrorCode.VALIDATION_FAILED, message, HttpStatus.BAD_REQUEST)

    private companion object {
        const val MAX_COVER_BYTES = 5L * 1024 * 1024
        const val MIN_COVER_SIDE = 240
        const val MIN_LOGO_SIDE = 64
        const val MAX_COVER_SIDE = 6_000
        const val MAX_IMAGE_PIXELS = 8_000_000L
        val SUPPORTED_TYPES = mapOf(
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "png" to "image/png",
        )
    }
}
