package kz.epharm.promo.service

import kz.epharm.auth.access.AdministrativeScope
import kz.epharm.medusa.service.MedusaPriceService
import kz.epharm.pharmacies.repository.PharmacyRepository
import kz.epharm.promo.dto.CreatePromoRequest
import kz.epharm.promo.dto.PromoDto
import kz.epharm.promo.dto.PromoRulesConfigDto
import kz.epharm.promo.dto.UpdatePromoRequest
import kz.epharm.promo.entity.PromoEntity
import kz.epharm.promo.entity.PromoStatus
import kz.epharm.promo.entity.PromoTier
import kz.epharm.promo.repository.PromoRepository
import kz.epharm.rules.entity.RuleStatus
import kz.epharm.rules.entity.RuleType
import kz.epharm.rules.repository.RuleRepository
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import kz.epharm.shared.validation.BarcodeNormalizer
import kz.epharm.shared.validation.ConfirmedBarcodeAliases
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
    private val ruleRepository: RuleRepository,
    private val pharmacyRepository: PharmacyRepository,
    private val promoRulesService: PromoRulesService,
    private val administrativeScope: AdministrativeScope,
) {

    @Transactional(readOnly = true)
    fun list(status: PromoStatus? = null): List<PromoDto> {
        val rows = if (status != null) {
            promoRepository.findAllByStatusRawOrderByUpdatedAtDesc(status.name)
        } else {
            promoRepository.findAllByOrderByUpdatedAtDesc()
        }
        val coverage = activePharmacyCount()
        return rows.map { PromoDto.of(it, coverage) }
    }

    @Transactional(readOnly = true)
    fun get(id: String): PromoDto = dto(loadOrThrow(id))

    @Transactional
    fun create(req: CreatePromoRequest, createdBy: String): PromoDto {
        if (req.status != null && req.status != PromoStatus.draft) {
            administrativeScope.requireAllPermission(MotivationPermissions.campaignPublish)
        }
        if (req.pharmacistBonus != 0L || req.budget != 0L) {
            administrativeScope.requireAllPermission(MotivationPermissions.configureBonus)
        }
        if (administrativeScope.hasManagedRequest() && req.status != null && req.status != PromoStatus.draft) {
            requirePreparedPublication()
        }
        val medusaProductId = req.medusaProductId?.trim()?.takeIf { it.isNotBlank() }
        val initialRecommendation = req.initialRecommendation
        if (initialRecommendation != null) {
            if (medusaProductId == null || (req.status != null && req.status != PromoStatus.draft)) {
                throw AppException(
                    ErrorCode.VALIDATION_FAILED,
                    "Первую рекомендацию можно создать только в черновике с выбранным товаром",
                    HttpStatus.BAD_REQUEST,
                )
            }
            val trigger = initialRecommendation.trigger
            if (trigger.triggerKind == "product" && trigger.medusaProductId.trim() == medusaProductId) {
                throw AppException(
                    ErrorCode.VALIDATION_FAILED,
                    "Товар не может одновременно запускать рекомендацию и предлагаться в ней",
                    HttpStatus.BAD_REQUEST,
                )
            }
        }
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
            barcode = normalizeBarcode(req.barcode),
            ipartId = req.ipartId?.trim()?.takeIf { it.isNotBlank() },
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
        entity.barcodes = ConfirmedBarcodeAliases.merge(
            req.barcodes, req.barcode, source = entity.barcodes,
            sourceComplete = entity.barcodeSourceComplete,
        )
        validatePromo(entity)
        val saved = promoRepository.save(entity)
        if (initialRecommendation != null) {
            // This joins the create transaction. Invalid taxonomy keys or rule failures roll
            // back both the campaign and its generated rule, leaving no partial draft.
            // A newly created pair always needs an explicit review before publication.
            val trigger = initialRecommendation.trigger.copy(active = false)
            val config = when (initialRecommendation.type) {
                RuleType.substitution -> PromoRulesConfigDto(replacements = listOf(trigger))
                RuleType.crosssell -> PromoRulesConfigDto(crossSells = listOf(trigger))
            }
            promoRulesService.createInitialDraft(saved.id, config, createdBy)
        }
        if (req.status != null) syncGeneratedRuleStatuses(saved)
        return dto(saved)
    }

    @Transactional
    fun update(id: String, req: UpdatePromoRequest): PromoDto = updateInternal(id, req, false)

    @Transactional
    internal fun applyPrepared(id: String, req: UpdatePromoRequest): PromoDto = updateInternal(id, preparedPatch(req), true)

    /** A prepared bundle is a complete editable projection; null explicitly clears optional fields. */
    internal fun preparedPatch(req: UpdatePromoRequest) = req.copy(
        title = req.title.orEmpty(), brand = req.brand.orEmpty(), period = req.period.orEmpty(), budget = req.budget ?: 0,
        kpi = req.kpi.orEmpty(), cover = req.cover.orEmpty(), medusaProductId = req.medusaProductId.orEmpty(),
        productName = req.productName.orEmpty(), productImage = req.productImage.orEmpty(), barcode = req.barcode.orEmpty(),
        ipartId = req.ipartId.orEmpty(), overrideImage = req.overrideImage.orEmpty(),
        overrideDescription = req.overrideDescription.orEmpty(), overrideCharacteristics = req.overrideCharacteristics.orEmpty(),
        pharmacistBonus = req.pharmacistBonus ?: 0, barcodes = req.barcodes.orEmpty(),
    )

    internal fun requiresBonusPermission(entity: PromoEntity, req: UpdatePromoRequest): Boolean {
        val nextProduct = req.medusaProductId?.trim()?.takeIf(String::isNotBlank)
            ?: entity.medusaProductId.takeIf { req.medusaProductId == null }
        val nextBonus = if (nextProduct == null) 0L else req.pharmacistBonus ?: entity.pharmacistBonus
        val paidTiers = entity.tiers.filter { it.bonus != 0L }.map { it.minQty to it.bonus }
        val nextPaidTiers = if (nextBonus != 0L) listOf(1 to nextBonus) else emptyList()
        val hasPaidTerms = nextBonus != 0L || paidTiers.isNotEmpty() || (entity.goalBonus ?: 0) != 0
        return req.budget != null && req.budget != entity.budget || paidTiers != nextPaidTiers ||
            hasPaidTerms && nextProduct != entity.medusaProductId
    }

    private fun updateInternal(id: String, req: UpdatePromoRequest, replaceNullableFields: Boolean): PromoDto {
        val entity = loadOrThrow(id, forUpdate = true)
        val managedLegacyRequest = !replaceNullableFields && administrativeScope.hasManagedRequest()
        val lifecycleOnly = req.status != null && req.copy(status = null) == UpdatePromoRequest()
        val pauseOrResume = entity.status in setOf(PromoStatus.active, PromoStatus.paused) &&
            req.status in setOf(PromoStatus.active, PromoStatus.paused)
        val managedLifecycle = managedLegacyRequest && lifecycleOnly && pauseOrResume
        if (entity.status == PromoStatus.active || req.status != null && req.status != entity.status) {
            administrativeScope.requireAllPermission(MotivationPermissions.campaignPublish)
        }
        if (!managedLifecycle && requiresBonusPermission(entity, req)) {
            administrativeScope.requireAllPermission(MotivationPermissions.configureBonus)
        }
        if (managedLegacyRequest) {
            if (entity.status in setOf(PromoStatus.active, PromoStatus.paused) && (!lifecycleOnly || !pauseOrResume) ||
                entity.status == PromoStatus.draft && req.status != null && req.status != PromoStatus.draft) {
                requirePreparedPublication()
            }
        }
        if (managedLifecycle) {
            entity.status = req.status!!
            val saved = promoRepository.save(entity)
            syncGeneratedRuleStatuses(saved)
            return dto(saved)
        }
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
        val productChanged = req.medusaProductId != null &&
            req.medusaProductId.trim().takeIf(String::isNotBlank) != entity.medusaProductId
        if (productChanged) {
            // An identity belongs to one exact item, never to the next selected dose/pack.
            entity.barcode = null
            entity.barcodes = emptyList()
            entity.barcodeSourceComplete = false
            entity.ipartId = null
        }
        req.medusaProductId?.let { entity.medusaProductId = it.trim().takeIf { s -> s.isNotBlank() } }
        req.productName?.let { entity.productName = it.trim() }
        req.productImage?.let { entity.productImage = it.trim().takeIf { s -> s.isNotBlank() } }
        req.barcode?.let { entity.barcode = normalizeBarcode(it) }
        req.ipartId?.let { entity.ipartId = it.trim().takeIf { s -> s.isNotBlank() } }
        // Override: пустая строка = очистить, иначе установить.
        req.overrideImage?.let { entity.overrideImage = it.trim().takeIf { s -> s.isNotBlank() } }
        req.overrideDescription?.let { entity.overrideDescription = it.trim().takeIf { s -> s.isNotBlank() } }
        req.overrideCharacteristics?.let {
            entity.overrideCharacteristics = it.trim().takeIf { s -> s.isNotBlank() }
        }
        req.dateStart?.let { entity.dateStart = it }
        req.dateEnd?.let { entity.dateEnd = it }
        if (replaceNullableFields) {
            entity.dateStart = req.dateStart
            entity.dateEnd = req.dateEnd
            entity.barcodes = req.barcodes.orEmpty()
        }

        // Пересобираем порог: бонус — из запроса (или текущий), цена — из Medusa при смене товара
        // или если ещё не проставлена.
        val desiredBonus = req.pharmacistBonus ?: entity.pharmacistBonus
        syncTier(entity, desiredBonus = desiredBonus, refetchPrice = productChanged || entity.price == 0L)
        // Штрих-код перетягиваем из Medusa при смене товара (или если ещё не проставлен).
        syncBarcode(entity, refetch = productChanged || entity.barcode.isNullOrBlank() || req.barcodes != null)
        entity.barcodes = ConfirmedBarcodeAliases.merge(
            req.barcodes.orEmpty(), req.barcode,
            source = if (entity.barcodeSourceComplete) entity.barcodes else emptyList(),
            existing = entity.barcodes + listOfNotNull(entity.barcode),
            sourceComplete = entity.barcodeSourceComplete,
        )

        validatePromo(entity)
        val saved = promoRepository.save(entity)
        if (req.status != null) syncGeneratedRuleStatuses(saved)
        return dto(saved)
    }

    @Transactional
    fun archive(id: String): PromoDto {
        val entity = loadOrThrow(id, forUpdate = true)
        if (entity.status == PromoStatus.archived) return dto(entity)
        administrativeScope.requireAllPermission(MotivationPermissions.campaignPublish)
        entity.status = PromoStatus.archived
        val saved = promoRepository.save(entity)
        syncGeneratedRuleStatuses(saved)
        return dto(saved)
    }

    /**
     * Bug L fix: восстановление кампании из архива → status=draft (явно включит после ревью).
     * Идемпотентно: на не-archived promo — no-op.
     */
    @Transactional
    fun restore(id: String): PromoDto {
        val entity = loadOrThrow(id, forUpdate = true)
        if (entity.status != PromoStatus.archived) return dto(entity)
        administrativeScope.requireAllPermission(MotivationPermissions.campaignPublish)
        entity.status = PromoStatus.draft
        val saved = promoRepository.save(entity)
        syncGeneratedRuleStatuses(saved)
        return dto(saved)
    }

    // ── Internals ─────────────────────────────────────────────────────────

    /**
     * Все активные кампании глобальны: Rules Engine применяет их на любой
     * авторизованной POSM-кассе. Поэтому API возвращает реальный размер целевой
     * сети, а не никогда не обновлявшееся денормализованное `promos.pharmacies`.
     */
    private fun dto(entity: PromoEntity): PromoDto = PromoDto.of(entity, activePharmacyCount())

    private fun activePharmacyCount(): Int = pharmacyRepository.countByActiveTrue()
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()

    /**
     * Синхронизирует единственный ценовой порог кампании:
     *   - товар не привязан → пороги пусты;
     *   - привязан → [{minQty:1, price, bonus:desiredBonus}], где price из Medusa
     *     (при refetchPrice=true), иначе сохраняем текущую цену.
     */
    private fun syncTier(e: PromoEntity, desiredBonus: Long, refetchPrice: Boolean) {
        if (desiredBonus !in 0L..Int.MAX_VALUE.toLong()) {
            throw AppException(ErrorCode.VALIDATION_FAILED, "Бонус превышает допустимый размер", HttpStatus.BAD_REQUEST)
        }
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
            e.barcodes = emptyList()
            e.barcodeSourceComplete = false
            e.ipartId = null
            return
        }
        if (refetch) {
            val snapshot = medusaPriceService.snapshotOf(mpid)
            e.barcodes = ConfirmedBarcodeAliases.merge(
                primary = snapshot?.barcode,
                source = snapshot?.let { listOfNotNull(it.barcode) + it.barcodes }.orEmpty(),
                existing = e.barcodes + listOfNotNull(e.barcode),
                sourceComplete = snapshot?.barcodeSourceComplete == true,
            )
            e.barcode = e.barcodes.firstOrNull()
            if (snapshot != null && (snapshot.barcodeSourceComplete || snapshot.barcodes.isNotEmpty() ||
                    snapshot.barcode != null)) e.barcodeSourceComplete = true
        }
    }

    /**
     * Кампания — мастер-выключатель для сгенерированных правил.
     * При активации включаем только пары, которые админ не оставил черновиками.
     */
    private fun syncGeneratedRuleStatuses(promo: PromoEntity) {
        val rules = ruleRepository.findAllByPromoIdForUpdate(promo.id).filter { it.retiredAt == null }
        if (rules.isEmpty()) return

        val effectiveStatus: (Boolean) -> RuleStatus = when (promo.status) {
            PromoStatus.active -> { pairEnabled -> if (pairEnabled) RuleStatus.active else RuleStatus.draft }
            PromoStatus.archived -> { _ -> RuleStatus.archived }
            PromoStatus.draft,
            PromoStatus.paused -> { _ -> RuleStatus.draft }
        }

        rules.forEach { rule ->
            rule.status = effectiveStatus(rule.card?.pairActive != false)
        }
        ruleRepository.saveAllAndFlush(rules)
    }

    private fun loadOrThrow(id: String, forUpdate: Boolean = false): PromoEntity =
        (if (forUpdate) promoRepository.findForUpdate(id) else promoRepository.findById(id)).orElseThrow {
            AppException(ErrorCode.NOT_FOUND, "Promo $id not found", HttpStatus.NOT_FOUND)
        }

    private fun generateId(): String = "pr_${UUID.randomUUID().toString().replace("-", "")}"

    private fun requirePreparedPublication(): Nothing = throw AppException(ErrorCode.CONFLICT,
        "Сохраните подготовленные изменения и примените проверенную версию кампании через «Применить»", HttpStatus.CONFLICT)

    /**
     * Accepts Medusa's occasional multi-GTIN value but never lets an oversized,
     * unusable identifier reach the varchar(32) campaign/product columns.
     */
    private fun normalizeBarcode(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return BarcodeNormalizer.first(value) ?: throw AppException(
            ErrorCode.VALIDATION_FAILED,
            "Штрих-код должен быть одним GTIN/EAN длиной до ${BarcodeNormalizer.MAX_STORED_LENGTH} символов",
            HttpStatus.BAD_REQUEST,
        )
    }

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
