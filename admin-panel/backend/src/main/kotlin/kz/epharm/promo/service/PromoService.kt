package kz.epharm.promo.service

import kz.epharm.medusa.service.MedusaPriceService
import kz.epharm.promo.dto.CreatePromoRequest
import kz.epharm.promo.dto.PromoDto
import kz.epharm.promo.dto.UpdatePromoRequest
import kz.epharm.promo.entity.PromoEntity
import kz.epharm.promo.entity.PromoStatus
import kz.epharm.promo.entity.PromoTier
import kz.epharm.promo.repository.PromoRepository
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Кампании-акции (ТЗ §3.2.3). T1: 1 кампания = 1 товар Medusa.
 *
 *  - цена товара берётся ТОЛЬКО из Medusa (read-only), кладётся в единственный ценовой порог
 *    `{minQty:1, price:<Medusa>, bonus:<бонус фармацевту>}`. Пользователь цену не задаёт;
 *  - бонус фармацевту за продажу задаётся в админке (поле `pharmacistBonus`);
 *  - фото/описание можно переопределить вручную (override-поля), если PIM-данные не устраивают;
 *  - один товар не может быть в двух живых (не-archived) кампаниях.
 */
@Service
class PromoService(
    private val promoRepository: PromoRepository,
    private val medusaPriceService: MedusaPriceService,
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
        val medusaProductId = req.medusaProductId?.trim()?.takeIf { it.isNotBlank() }
        val entity = PromoEntity(
            id = generateId(),
            title = req.title.trim(),
            brand = req.brand.trim(),
            period = req.period.trim(),
            budget = req.budget,
            kpi = req.kpi.trim(),
            cover = req.cover.trim(),
            medusaProductId = medusaProductId,
            productName = req.productName.trim(),
            productImage = req.productImage?.trim()?.takeIf { it.isNotBlank() },
            barcode = req.barcode?.trim()?.takeIf { it.isNotBlank() },
            overrideImage = req.overrideImage?.trim()?.takeIf { it.isNotBlank() },
            overrideDescription = req.overrideDescription?.trim()?.takeIf { it.isNotBlank() },
            overrideCharacteristics = req.overrideCharacteristics?.trim()?.takeIf { it.isNotBlank() },
            dateStart = req.dateStart,
            dateEnd = req.dateEnd,
            createdBy = createdBy,
        ).also {
            it.status = req.status ?: PromoStatus.draft
        }
        // Цена — из Medusa (read-only), бонус — из запроса; кладём в единственный порог.
        syncTier(entity, desiredBonus = req.pharmacistBonus, refetchPrice = true)
        // Штрих-код продвигаемого товара — из Medusa (для матчинга кассы); fallback на присланный.
        syncBarcode(entity, refetch = true)
        validatePromo(entity)
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
        // Урок Bug G (Rules): PATCH не должен ставить archived (только через POST /archive).
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
        // Товарная акция. Смена товара → надо перетянуть цену из Medusa.
        val productChanged = req.medusaProductId != null
        req.medusaProductId?.let { entity.medusaProductId = it.trim().takeIf { s -> s.isNotBlank() } }
        req.productName?.let { entity.productName = it.trim() }
        req.productImage?.let { entity.productImage = it.trim().takeIf { s -> s.isNotBlank() } }
        req.barcode?.let { entity.barcode = it.trim().takeIf { s -> s.isNotBlank() } }
        // Override: пустая строка = очистить, иначе установить.
        req.overrideImage?.let { entity.overrideImage = it.trim().takeIf { s -> s.isNotBlank() } }
        req.overrideDescription?.let { entity.overrideDescription = it.trim().takeIf { s -> s.isNotBlank() } }
        req.overrideCharacteristics?.let {
            entity.overrideCharacteristics = it.trim().takeIf { s -> s.isNotBlank() }
        }
        req.dateStart?.let { entity.dateStart = it }
        req.dateEnd?.let { entity.dateEnd = it }

        // Пересобираем порог: бонус — из запроса (или текущий), цена — из Medusa при смене товара
        // или если ещё не проставлена.
        val desiredBonus = req.pharmacistBonus ?: entity.pharmacistBonus
        syncTier(entity, desiredBonus = desiredBonus, refetchPrice = productChanged || entity.price == 0L)
        // Штрих-код перетягиваем из Medusa при смене товара (или если ещё не проставлен).
        syncBarcode(entity, refetch = productChanged || entity.barcode.isNullOrBlank())

        validatePromo(entity)
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
     * Bug L fix: восстановление кампании из архива → status=draft (явно включит после ревью).
     * Идемпотентно: на не-archived promo — no-op.
     */
    @Transactional
    fun restore(id: String): PromoDto {
        val entity = loadOrThrow(id)
        if (entity.status != PromoStatus.archived) return PromoDto.of(entity)
        entity.status = PromoStatus.draft
        return PromoDto.of(promoRepository.save(entity))
    }

    // ── Internals ─────────────────────────────────────────────────────────

    /**
     * Синхронизирует единственный ценовой порог кампании:
     *   - товар не привязан → пороги пусты;
     *   - привязан → [{minQty:1, price, bonus:desiredBonus}], где price из Medusa
     *     (при refetchPrice=true), иначе сохраняем текущую цену.
     */
    private fun syncTier(e: PromoEntity, desiredBonus: Long, refetchPrice: Boolean) {
        val mpid = e.medusaProductId
        if (mpid == null) {
            e.tiers = emptyList()
            return
        }
        val price = if (refetchPrice) {
            medusaPriceService.priceOf(mpid) ?: e.price
        } else {
            e.price
        }
        e.tiers = listOf(PromoTier(minQty = 1, price = price, bonus = desiredBonus.coerceAtLeast(0)))
    }

    /**
     * Синхронизирует штрих-код продвигаемого товара:
     *   - товар не привязан → штрих-код очищается;
     *   - привязан + refetch → тянем EAN-13 из Medusa (snapshot); недоступен → сохраняем текущий.
     * Штрих-код — ключ матчинга POSM-кассы; стампится на ProductEntity в PromoRulesService.
     */
    private fun syncBarcode(e: PromoEntity, refetch: Boolean) {
        val mpid = e.medusaProductId
        if (mpid == null) {
            e.barcode = null
            return
        }
        if (refetch) {
            val ean = medusaPriceService.snapshotOf(mpid)?.barcode?.trim()?.takeIf { it.isNotBlank() }
            if (ean != null) e.barcode = ean
        }
    }

    private fun loadOrThrow(id: String): PromoEntity =
        promoRepository.findById(id).orElseThrow {
            AppException(ErrorCode.NOT_FOUND, "Promo $id not found", HttpStatus.NOT_FOUND)
        }

    private fun generateId(): String = "pr_${UUID.randomUUID().toString().substring(0, 8)}"

    /**
     * Бизнес-валидация: даты согласованы; активная кампания обязана иметь товар (1:1);
     * один товар Medusa не может быть в двух живых (не-archived) кампаниях.
     */
    private fun validatePromo(e: PromoEntity) {
        val start = e.dateStart
        val end = e.dateEnd
        if (start != null && end != null && end.isBefore(start)) {
            throw AppException(
                ErrorCode.VALIDATION_FAILED,
                "Дата окончания акции раньше даты начала",
                HttpStatus.BAD_REQUEST,
            )
        }
        // Активная акция обязана быть привязана к товару (1 кампания = 1 товар).
        if (e.status == PromoStatus.active && e.medusaProductId == null) {
            throw AppException(
                ErrorCode.VALIDATION_FAILED,
                "Активную кампанию нужно привязать к товару",
                HttpStatus.BAD_REQUEST,
            )
        }
        // 1:1 — товар не должен быть в другой живой кампании.
        val mpid = e.medusaProductId
        if (mpid != null && e.status != PromoStatus.archived) {
            val clash = promoRepository.findAllByMedusaProductId(mpid)
                .any { it.id != e.id && it.status != PromoStatus.archived }
            if (clash) {
                throw AppException(
                    ErrorCode.CONFLICT,
                    "Этот товар уже привязан к другой активной кампании",
                    HttpStatus.CONFLICT,
                )
            }
        }
    }
}
