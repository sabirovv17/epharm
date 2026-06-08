package kz.epharm.appupdate.service

import kz.epharm.appupdate.dto.AppReleaseDto
import kz.epharm.appupdate.dto.AppVersionDto
import kz.epharm.appupdate.dto.RegisterReleaseRequest
import kz.epharm.appupdate.entity.AppReleaseEntity
import kz.epharm.appupdate.repository.AppReleaseRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class AppReleaseService(
    private val appReleaseRepository: AppReleaseRepository,
) {

    /** Текущий релиз для платформы (для кассы). Нет — AppVersionDto.none(). */
    @Transactional(readOnly = true)
    fun currentFor(platform: String): AppVersionDto =
        appReleaseRepository.findFirstByPlatformAndIsCurrentTrue(platform)
            ?.let(AppVersionDto::of)
            ?: AppVersionDto.none()

    @Transactional(readOnly = true)
    fun list(): List<AppReleaseDto> =
        appReleaseRepository.findAllByOrderByCreatedAtDesc().map(AppReleaseDto::of)

    /**
     * Регистрирует релиз и делает его текущим: снимает is_current со всех релизов этой платформы,
     * ставит новому. Так на платформе всегда ровно один «текущий».
     */
    @Transactional
    fun register(req: RegisterReleaseRequest): AppReleaseDto {
        appReleaseRepository.findAllByPlatformAndIsCurrentTrue(req.platform).forEach {
            it.isCurrent = false
            appReleaseRepository.save(it)
        }
        val entity = appReleaseRepository.save(
            AppReleaseEntity(
                id = "rel_${UUID.randomUUID().toString().substring(0, 8)}",
                platform = req.platform,
                version = req.version.trim(),
                url = req.url.trim(),
                sha256 = req.sha256.trim(),
                mandatory = req.mandatory,
                notes = req.notes.trim(),
                isCurrent = true,
            ),
        )
        return AppReleaseDto.of(entity)
    }
}
