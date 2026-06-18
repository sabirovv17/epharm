package kz.epharm.banners.controller

import jakarta.validation.Valid
import kz.epharm.banners.dto.BannerDto
import kz.epharm.banners.dto.BannerImageUploadDto
import kz.epharm.banners.dto.CreateBannerRequest
import kz.epharm.banners.dto.UpdateBannerRequest
import kz.epharm.banners.service.BannerService
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

// Баннеры главного экрана мобильного приложения (п.5): управление контентом из админки.
// Картинка грузится в MinIO (общий медиа-бакет), баннер ведёт на детальный экран в приложении.
// Доступ закрыт /api/admin/** → hasAnyRole в SecurityConfig (отдельно не настраиваем).
@RestController
@RequestMapping("/api/admin/banners")
class BannerController(
    private val bannerService: BannerService,
) {

    @GetMapping
    fun list(): List<BannerDto> = bannerService.list()

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody req: CreateBannerRequest): BannerDto =
        bannerService.create(req)

    @PatchMapping("/{id}")
    fun update(@PathVariable id: String, @Valid @RequestBody req: UpdateBannerRequest): BannerDto =
        bannerService.update(id, req)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: String) = bannerService.delete(id)

    /** Загрузка картинки баннера: multipart (file). Файл → MinIO, возвращает {imageUrl}. */
    @PostMapping("/image", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadImage(@RequestParam("file") file: MultipartFile): BannerImageUploadDto =
        bannerService.uploadImage(file)
}
