package kz.epharm.receipts.controller

import jakarta.validation.Valid
import kz.epharm.auth.security.AdminPrincipal
import kz.epharm.receipts.dto.ExcelImportResultDto
import kz.epharm.receipts.dto.ReceiptDto
import kz.epharm.receipts.dto.ReconcileSummaryDto
import kz.epharm.receipts.dto.RejectReceiptRequest
import kz.epharm.receipts.entity.ReceiptStatus
import kz.epharm.receipts.service.ExcelImportService
import kz.epharm.receipts.service.ReconcileService
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

// Сверка чеков (ТЗ §3.5). Очередь + ручные действия модератора.
// Загрузка чека фармацевтом (multipart) — здесь dev/демо-эндпоинт; в Pharmacist App
// (Этап 6) тот же ReconcileService.submitReceipt вызывается через /api/mobile/receipts.
@RestController
@RequestMapping("/api/admin/reconcile")
class ReconcileController(
    private val reconcileService: ReconcileService,
    private val excelImportService: ExcelImportService,
) {

    @GetMapping
    fun list(@RequestParam(required = false) status: ReceiptStatus?): List<ReceiptDto> =
        reconcileService.list(status = status)

    @GetMapping("/summary")
    fun summary(): ReconcileSummaryDto = reconcileService.summary()

    @GetMapping("/{id}")
    fun get(@PathVariable id: String): ReceiptDto = reconcileService.get(id)

    @PostMapping("/{id}/approve")
    fun approve(
        @PathVariable id: String,
        @AuthenticationPrincipal principal: AdminPrincipal?,
    ): ReceiptDto = reconcileService.approve(id, requireUserId(principal))

    @PostMapping("/{id}/reject")
    fun reject(
        @PathVariable id: String,
        @Valid @RequestBody req: RejectReceiptRequest,
        @AuthenticationPrincipal principal: AdminPrincipal?,
    ): ReceiptDto = reconcileService.reject(id, requireUserId(principal), req.reason)

    /**
     * Загрузка чека (фото) от имени фармацевта — dev/демо. multipart: file? + pharmacistId.
     * ДОП.8: аптека из профиля, акции — авто-матчинг (POSM-бронь). Матчинг → ветка (лог + Excel; anti-fraud).
     */
    @PostMapping("/submit", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    fun submit(
        @RequestParam("pharmacistId") pharmacistId: String,
        @RequestParam(name = "file", required = false) file: MultipartFile?,
    ): ReceiptDto = reconcileService.submitReceipt(
        pharmacistId = pharmacistId,
        photoBytes = file?.takeIf { !it.isEmpty }?.bytes,
        photoContentType = file?.contentType,
        photoName = file?.originalFilename,
    )

    /**
     * Импорт Excel-выгрузки Стандарт-Н (источник №2 сверки). multipart: file (xlsx/xls).
     * Каждая строка сверяется с открытыми pending-бонусами; подтверждённые обоими источниками
     * чеки авто-одобряются, подтверждённые одним → moderation_required.
     */
    @PostMapping("/import-excel", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun importExcel(
        @RequestParam("file") file: MultipartFile,
        @AuthenticationPrincipal principal: AdminPrincipal?,
    ): ExcelImportResultDto {
        val reviewer = requireUserId(principal)
        if (file.isEmpty) {
            throw AppException(ErrorCode.VALIDATION_FAILED, "Файл пуст", HttpStatus.BAD_REQUEST)
        }
        return excelImportService.import(file.originalFilename ?: "import.xlsx", reviewer, file.bytes)
    }

    private fun requireUserId(principal: AdminPrincipal?): String =
        principal?.userId?.toString()
            ?: throw AppException(ErrorCode.UNAUTHORIZED, "Not authenticated", HttpStatus.UNAUTHORIZED)
}
