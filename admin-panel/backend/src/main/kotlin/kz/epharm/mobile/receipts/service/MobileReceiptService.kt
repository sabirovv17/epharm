package kz.epharm.mobile.receipts.service

import kz.epharm.catalog.repository.ProductRepository
import kz.epharm.mobile.receipts.dto.MobileReceiptDto
import kz.epharm.receipts.service.ReconcileService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Чеки со стороны мобильного приложения. Намеренно ТОНКАЯ обёртка над ReconcileService —
 * валидация чека идёт через ЕДИНЫЙ проверенный пайплайн, как требовал заказчик:
 *
 *  1. Отправленное фото → сохраняется в MinIO как доказательство для модератора.
 *  2. Матчинг с открытым pending-бонусом фармацевта (бонус «забронирован» POSM-заменой на кассе).
 *  3. Подтверждение ДВУМЯ источниками сверки: №1 — лог Стандарт-Н (программа на C# → /api/posm/sales),
 *     №2 — Excel-выгрузка. То, что в чеке, сверяется с логом и Excel. Обе галочки → авто-approve + начисление.
 *  4. Не прошло автоматику (одна или ноль галочек / анти-фрод) → moderation_required →
 *     ручная модерация менеджером в админ-панели (/api/admin/reconcile).
 *
 * Таким образом мобилка НЕ вводит новый путь доверия — переиспользует тот же ReconcileService,
 * что и POSM/Excel/админка. Это и есть «качественная проверка по моим критериям».
 */
@Service
class MobileReceiptService(
    private val reconcileService: ReconcileService,
    private val productRepository: ProductRepository,
) {
    @Transactional
    fun submit(
        pharmacistId: String,
        photoBytes: ByteArray?,
        photoContentType: String?,
        photoName: String?,
        pharmacyId: String?,
        pharmacyName: String?,
        claimedPromoIds: String?,
    ): MobileReceiptDto {
        val dto = reconcileService.submitReceipt(
            pharmacistId = pharmacistId,
            photoBytes = photoBytes,
            photoContentType = photoContentType,
            photoName = photoName,
            pharmacyId = pharmacyId,
            pharmacyName = pharmacyName,
            claimedPromoIds = claimedPromoIds,
        )
        return MobileReceiptDto.from(dto, productNameOf(dto.parsedSku))
    }

    @Transactional(readOnly = true)
    fun list(pharmacistId: String): List<MobileReceiptDto> =
        reconcileService.listForPharmacist(pharmacistId)
            .map { MobileReceiptDto.from(it, productNameOf(it.parsedSku)) }

    /** Человекочитаемое имя из каталога по SKU (golden rule), иначе сам SKU / «Чек». */
    private fun productNameOf(sku: String): String =
        if (sku.isBlank()) "Чек" else productRepository.findById(sku).map { it.name }.orElse(sku)
}
