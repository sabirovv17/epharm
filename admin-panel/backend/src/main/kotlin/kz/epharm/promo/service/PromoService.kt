package kz.epharm.promo.service

import kz.epharm.promo.dto.CreatePromoRequest
import kz.epharm.promo.dto.PromoDto
import kz.epharm.promo.dto.UpdatePromoRequest
import kz.epharm.promo.entity.PromoEntity
import kz.epharm.promo.entity.PromoStatus
import kz.epharm.promo.repository.PromoRepository
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class PromoService(
    private val promoRepository: PromoRepository,
) {

    @Transactional(readOnly = true)
    fun list(status: PromoStatus? = null): List<PromoDto> {
        val rows = if (status != null) {
            promoRepository.findAllByStatusRawOrderByUpdatedAtDesc(status.name)
        } else {
            promoRepository.findAllByOrderByUpdatedAtDesc()
        }
        return rows.map(PromoDto::of)
    }

    @Transactional(readOnly = true)
    fun get(id: String): PromoDto = PromoDto.of(loadOrThrow(id))

    @Transactional
    fun create(req: CreatePromoRequest, createdBy: String): PromoDto {
        val entity = PromoEntity(
            id = generateId(),
            title = req.title.trim(),
            brand = req.brand.trim(),
            period = req.period.trim(),
            budget = req.budget,
            kpi = req.kpi.trim(),
            cover = req.cover.trim(),
            createdBy = createdBy,
        ).also {
            it.status = req.status ?: PromoStatus.draft
        }
        return PromoDto.of(promoRepository.save(entity))
    }

    @Transactional
    fun update(id: String, req: UpdatePromoRequest): PromoDto {
        val entity = loadOrThrow(id)
        if (entity.status == PromoStatus.archived) {
            throw AppException(
                ErrorCode.CONFLICT,
                "Archived promo cannot be edited (restore first)",
                HttpStatus.CONFLICT,
            )
        }
        // Применяем урок из Bug G в Rules: PATCH не должен ставить archived
        // (только через POST /archive — для audit-event'а и toast'а).
        if (req.status == PromoStatus.archived) {
            throw AppException(
                ErrorCode.VALIDATION_FAILED,
                "Use POST /promo/{id}/archive to archive a promo",
                HttpStatus.BAD_REQUEST,
            )
        }
        req.status?.let { entity.status = it }
        req.title?.let { entity.title = it.trim() }
        req.brand?.let { entity.brand = it.trim() }
        req.period?.let { entity.period = it.trim() }
        req.budget?.let { entity.budget = it }
        req.kpi?.let { entity.kpi = it.trim() }
        req.cover?.let { entity.cover = it.trim() }
        return PromoDto.of(promoRepository.save(entity))
    }

    @Transactional
    fun archive(id: String): PromoDto {
        val entity = loadOrThrow(id)
        if (entity.status == PromoStatus.archived) return PromoDto.of(entity)
        entity.status = PromoStatus.archived
        return PromoDto.of(promoRepository.save(entity))
    }

    /**
     * Bug L fix: восстановление кампании из архива. Возвращает status=draft
     * (а не active) — admin должен явно её включить после ревью.
     * Идемпотентно: на не-archived promo — no-op (возвращает текущий status).
     */
    @Transactional
    fun restore(id: String): PromoDto {
        val entity = loadOrThrow(id)
        if (entity.status != PromoStatus.archived) return PromoDto.of(entity)
        entity.status = PromoStatus.draft
        return PromoDto.of(promoRepository.save(entity))
    }

    // ── Internals ─────────────────────────────────────────────────────────

    private fun loadOrThrow(id: String): PromoEntity =
        promoRepository.findById(id).orElseThrow {
            AppException(
                ErrorCode.NOT_FOUND,
                "Promo $id not found",
                HttpStatus.NOT_FOUND,
            )
        }

    private fun generateId(): String = "pr_${UUID.randomUUID().toString().substring(0, 8)}"
}
