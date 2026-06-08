package kz.epharm.appupdate.controller

import jakarta.validation.Valid
import kz.epharm.appupdate.dto.AppReleaseDto
import kz.epharm.appupdate.dto.RegisterReleaseRequest
import kz.epharm.appupdate.service.AppReleaseService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * Управление релизами POSM-клиента из админки. Загрузка zip-сборки в MinIO делается отдельно
 * (тот же upload, что и медиа), сюда передаётся готовый url. register → релиз становится текущим,
 * кассы подхватывают его на следующем опросе.
 */
@RestController
@RequestMapping("/api/admin/app-releases")
class AppReleaseController(private val appReleaseService: AppReleaseService) {

    @GetMapping
    fun list(): List<AppReleaseDto> = appReleaseService.list()

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun register(@Valid @RequestBody req: RegisterReleaseRequest): AppReleaseDto =
        appReleaseService.register(req)
}
