package kz.epharm.receipts.service

import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Результат распознавания чека (OCR фото + парсинг QR/ОФД).
 * score — уверенность распознавания 0..1.
 */
data class ParsedReceipt(
    val sku: String,
    val amount: Long,
    val cashier: String,
    val fiscalId: String?,
    val parsedAt: Instant?,
    val score: Double,
)

/**
 * Распознавание чека. На MVP — `MockOcrService` (детерминированная заглушка).
 * Реальные провайдеры (Yandex Vision / ОФД-API) подключаются в Этапе 7 за тем же
 * интерфейсом — ReconcileService от реализации не зависит.
 */
interface OcrService {
    /**
     * @param hintSku ожидаемый SKU из pending-бонуса (помогает заглушке вернуть
     *   реалистичный «распознанный» результат). В реальном OCR не используется.
     */
    fun parse(
        photoBytes: ByteArray?,
        qrRaw: String?,
        hintSku: String?,
        hintAmount: Long?,
    ): ParsedReceipt
}

/**
 * Детерминированная заглушка OCR/ОФД для dev/test. Не делает сетевых вызовов.
 *  - Если есть QR — «распознаёт» из него фискальный id, высокая уверенность (0.97).
 *  - Если только фото — средняя уверенность (0.88), поля берутся из hint'ов
 *    (эмуляция совпадения с POSM-записью).
 * Реальный score/fields даст YandexVisionOcrService в Этапе 7.
 */
@Service
class MockOcrService : OcrService {
    override fun parse(
        photoBytes: ByteArray?,
        qrRaw: String?,
        hintSku: String?,
        hintAmount: Long?,
    ): ParsedReceipt {
        val hasQr = !qrRaw.isNullOrBlank()
        // Фискальный id: из QR (детерминированно) либо синтетический от фото-размера.
        val fiscal = when {
            hasQr -> "fp-" + qrRaw!!.hashCode().toUInt().toString(16)
            photoBytes != null -> "fp-img-" + photoBytes.size
            else -> null
        }
        return ParsedReceipt(
            sku = hintSku ?: "",
            amount = hintAmount ?: 0,
            cashier = "Кассир №1",
            fiscalId = fiscal,
            parsedAt = Instant.now(),
            score = if (hasQr) 0.97 else 0.88,
        )
    }
}
