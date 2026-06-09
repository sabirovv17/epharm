package kz.epharm.mobile.receipts.controller

import kz.epharm.mobile.auth.security.PharmacistPrincipal
import kz.epharm.mobile.receipts.dto.MobileReceiptDto
import kz.epharm.mobile.receipts.service.MobileReceiptService
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

/**
 * Чеки фармацевта (мобильное приложение). Под JWT ROLE_PHARMACIST (путь вне /auth →
 * `.authenticated()` в SecurityConfig). pharmacistId берётся ИЗ ТОКЕНА — нельзя загрузить
 * чек за другого.
 */
@RestController
@RequestMapping("/api/mobile/receipts")
class MobileReceiptController(
    private val mobileReceiptService: MobileReceiptService,
) {

    /**
     * Отправка чека: фото (multipart `file`) + выбранная фармацевтом аптека
     * (`pharmacyId`/`pharmacyName` — где совершена покупка).
     */
    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    fun submit(
        @AuthenticationPrincipal principal: PharmacistPrincipal?,
        @RequestParam(name = "file", required = false) file: MultipartFile?,
        @RequestParam(name = "pharmacyId", required = false) pharmacyId: String?,
        @RequestParam(name = "pharmacyName", required = false) pharmacyName: String?,
    ): MobileReceiptDto {
        val p = principal ?: throw AppException(ErrorCode.UNAUTHORIZED, "Не авторизован", HttpStatus.UNAUTHORIZED)
        return mobileReceiptService.submit(
            pharmacistId = p.pharmacistId,
            photoBytes = file?.takeIf { !it.isEmpty }?.bytes,
            photoContentType = file?.contentType,
            photoName = file?.originalFilename,
            pharmacyId = pharmacyId,
            pharmacyName = pharmacyName,
        )
    }

    /** История своих чеков (свежие первыми). */
    @GetMapping
    fun list(@AuthenticationPrincipal principal: PharmacistPrincipal?): List<MobileReceiptDto> {
        val p = principal ?: throw AppException(ErrorCode.UNAUTHORIZED, "Не авторизован", HttpStatus.UNAUTHORIZED)
        return mobileReceiptService.list(p.pharmacistId)
    }
}
