package kz.epharm.appupdate.service

import kz.epharm.appupdate.dto.AppReleaseDto
import kz.epharm.appupdate.dto.AppVersionDto
import kz.epharm.appupdate.dto.RegisterReleaseRequest
import kz.epharm.appupdate.entity.AppReleaseEntity
import kz.epharm.appupdate.repository.AppReleaseRepository
import org.springframework.stereotype.Service
import org.springframework.jdbc.core.JdbcTemplate
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class AppReleaseService(
    private val appReleaseRepository: AppReleaseRepository,
    private val manifestVerifier: AppUpdateManifestVerifier,
    private val jdbc: JdbcTemplate,
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
        manifestVerifier.validate(req)
        val platform = req.platform.trim()
        if (platform.isEmpty()) throw AppException(ErrorCode.VALIDATION_FAILED, "Укажите платформу релиза")
        // Serializes publishers even when there is no existing release row to lock yet.
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 718096))", platform)
        appReleaseRepository.findAllByPlatformAndIsCurrentTrue(platform).forEach {
            it.isCurrent = false
            appReleaseRepository.save(it)
        }
        // Hibernate can insert before flushing updates; release the unique current slot first.
        appReleaseRepository.flush()
        val entity = appReleaseRepository.save(
            AppReleaseEntity(
                id = "rel_${UUID.randomUUID().toString().substring(0, 8)}",
                platform = platform,
                version = req.version.trim(),
                url = req.url.trim(),
                sha256 = req.sha256.trim(),
                manifestSignature = req.manifestSignature.trim(),
                mandatory = req.mandatory,
                notes = req.notes.trim(),
                isCurrent = true,
            ),
        )
        return AppReleaseDto.of(entity)
    }
}
