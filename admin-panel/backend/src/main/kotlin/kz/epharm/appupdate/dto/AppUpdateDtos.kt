package kz.epharm.appupdate.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import kz.epharm.appupdate.entity.AppReleaseEntity
import java.time.Instant

/**
 * Ответ кассе на GET /api/posm/app/version. Если current=false — текущего релиза нет, клиент
 * ничего не качает. Если current=true — клиент сравнивает version со своей и решает обновляться.
 */
data class AppVersionDto(
    val current: Boolean,
    val version: String,
    val url: String,
    val sha256: String,
    val mandatory: Boolean,
    val notes: String,
) {
    companion object {
        fun none() = AppVersionDto(current = false, version = "", url = "", sha256 = "", mandatory = false, notes = "")
        fun of(e: AppReleaseEntity) = AppVersionDto(
            current = true,
            version = e.version,
            url = e.url,
            sha256 = e.sha256,
            mandatory = e.mandatory,
            notes = e.notes,
        )
    }
}

/** Админ регистрирует новый релиз (zip уже залит в MinIO/внешний URL). */
data class RegisterReleaseRequest(
    @field:NotBlank @field:Size(max = 32)
    val version: String,
    @field:NotBlank @field:Size(max = 512)
    val url: String,
    @field:Size(max = 32)
    val platform: String = "win-x64",
    @field:Size(max = 128)
    val sha256: String = "",
    val mandatory: Boolean = false,
    @field:Size(max = 1024)
    val notes: String = "",
)

data class AppReleaseDto(
    val id: String,
    val platform: String,
    val version: String,
    val url: String,
    val sha256: String,
    val mandatory: Boolean,
    val notes: String,
    val isCurrent: Boolean,
    val createdAt: Instant,
) {
    companion object {
        fun of(e: AppReleaseEntity) = AppReleaseDto(
            id = e.id, platform = e.platform, version = e.version, url = e.url, sha256 = e.sha256,
            mandatory = e.mandatory, notes = e.notes, isCurrent = e.isCurrent, createdAt = e.createdAt,
        )
    }
}
