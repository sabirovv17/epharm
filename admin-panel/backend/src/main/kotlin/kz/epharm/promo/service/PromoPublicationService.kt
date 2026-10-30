package kz.epharm.promo.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kz.epharm.auth.access.AdministrativeScope
import kz.epharm.promo.dto.*
import kz.epharm.promo.entity.PromoEntity
import kz.epharm.promo.entity.PromoStatus
import kz.epharm.promo.repository.PromoRepository
import kz.epharm.rules.entity.RuleEntity
import kz.epharm.rules.repository.RuleRepository
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Published entities remain the POSM source of truth; prepared JSON never enters the matcher. */
@Service
class PromoPublicationService(
    private val promos: PromoRepository,
    private val rules: RuleRepository,
    private val campaigns: PromoService,
    private val campaignRules: PromoRulesService,
    private val scope: AdministrativeScope,
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
) {
    private data class Prepared(val revision: Long, val fingerprint: String?, val bundle: PromoChangesBundleDto?,
        val updatedAt: Instant, val updatedBy: String)

    @Transactional(readOnly = true)
    fun view(id: String): PromoChangesViewDto {
        requireView()
        val promo = load(id)
        return render(promo, prepared(id))
    }

    @Transactional
    fun save(id: String, request: SavePromoChangesRequest, actor: String): PromoChangesViewDto {
        requireView()
        requireEdit()
        if (request.discard && request.bundle != null || !request.discard && request.bundle == null) {
            invalid("Передайте подготовленные изменения или явное discard=true")
        }
        val promo = load(id, true)
        rules.findAllByPromoIdForUpdate(id)
        val previous = prepared(id)
        requireRevision(previous, request.expectedRevision)
        if (request.discard) {
            write(id, previous, null, null, actor)
            return render(promo, prepared(id))
        }
        if (promo.status == PromoStatus.archived) invalid("Восстановите кампанию перед подготовкой изменений")
        val currentFingerprint = fingerprint(promo)
        if (previous?.bundle != null && previous.fingerprint != currentFingerprint) conflict("Опубликованные условия изменились: отмените подготовленный черновик и загрузите актуальные данные")
        val bundle = request.bundle!!
        validate(bundle)
        if (changesMoney(promo, bundle)) scope.requireAllPermission(MotivationPermissions.configureBonus)
        write(id, previous, currentFingerprint, bundle, actor)
        return render(promo, prepared(id))
    }

    @Transactional
    fun apply(id: String, expectedRevision: Long, actor: String): PromoChangesViewDto {
        requireView()
        scope.requireAllPermission(MotivationPermissions.campaignPublish)
        scope.requireAllPermission(MotivationPermissions.recommendationPublish)
        val promo = load(id, true)
        rules.findAllByPromoIdForUpdate(id)
        val previous = prepared(id)
        requireRevision(previous, expectedRevision)
        val bundle = previous?.bundle ?: conflict("Нет сохранённых изменений для применения")
        if (promo.status == PromoStatus.archived) invalid("Нельзя применять изменения к архивной кампании")
        campaignRules.compatibilityIssue(promo)?.let(::conflict)
        validate(bundle)
        publicationIssues(bundle).firstOrNull()?.let(::invalid)
        val before = fingerprint(promo)
        if (before != previous.fingerprint) conflict("Опубликованные условия изменились после подготовки: применение отменено")
        if (changesMoney(promo, bundle)) scope.requireAllPermission(MotivationPermissions.configureBonus)

        val status = if (promo.status == PromoStatus.draft) PromoStatus.active else promo.status
        campaigns.applyPrepared(id, bundle.campaign.copy(status = status))
        campaignRules.replacePrepared(id, bundle.rules, actor)
        promos.flush()
        val after = fingerprint(promo)
        val revision = write(id, previous, null, null, actor)
        jdbc.update("""insert into promo_publication_history(promo_id,revision,applied_by,bundle_json,
            previous_fingerprint,published_fingerprint) values(?,?,?,?,?,?)""", id, revision, actor,
            mapper.writeValueAsString(bundle), before, after)
        return render(promo, prepared(id))
    }

    private fun requireView() {
        scope.requireAllPermission(MotivationPermissions.campaignView)
        scope.requireAllPermission(MotivationPermissions.recommendationView)
    }
    private fun requireEdit() {
        scope.requireAllPermission(MotivationPermissions.campaignEdit)
        scope.requireAllPermission(MotivationPermissions.recommendationEdit)
    }

    private fun validate(bundle: PromoChangesBundleDto) {
        if (bundle.campaign.status != null) invalid("Статус кампании меняется отдельным действием, а не в подготовленном наборе")
        if (bundle.campaign.title.isNullOrBlank()) invalid("Укажите название кампании")
        val start = bundle.campaign.dateStart
        val end = bundle.campaign.dateEnd
        if (start != null && end != null && end.isBefore(start)) invalid("Дата окончания раньше даты начала")
    }

    private fun changesMoney(promo: PromoEntity, bundle: PromoChangesBundleDto): Boolean {
        val patch = campaigns.preparedPatch(bundle.campaign)
        // Evaluate both paths, including taxonomy validity, even when campaign money already differs.
        val campaignMoney = campaigns.requiresBonusPermission(promo, patch)
        val ruleMoney = campaignRules.requiresBonusPermission(promo, bundle.rules,
            patch.medusaProductId?.trim()?.takeIf(String::isNotBlank), patch.pharmacistBonus ?: 0L)
        return campaignMoney || ruleMoney
    }

    private fun publicationIssues(bundle: PromoChangesBundleDto): List<String> = buildList {
        val product = bundle.campaign.medusaProductId?.trim().orEmpty()
        if (product.isEmpty()) add("Для публикации выберите предлагаемый товар")
        if ((bundle.rules.replacements + bundle.rules.crossSells).none { ref ->
            ref.active && when (ref.triggerKind) {
                "product" -> ref.medusaProductId.isNotBlank() && ref.medusaProductId.trim() != product
                "acc_group", "acc_subgroup", "acc_mnn", "mnn" -> !ref.triggerValue.isNullOrBlank()
                else -> false
            }
        }) add("Для запуска нужна хотя бы одна корректная включённая рекомендация")
        if (bundle.campaign.dateEnd?.isBefore(LocalDate.now(ZoneId.of("Asia/Almaty"))) == true) {
            add("Период акции уже закончился")
        }
    }

    private fun render(promo: PromoEntity, prepared: Prepared?): PromoChangesViewDto {
        val pending = prepared?.bundle
        val bundle = pending ?: currentBundle(promo)
        val conflicting = pending != null && prepared.fingerprint != fingerprint(promo)
        val issues = mutableListOf<String>()
        if (conflicting) issues += "Опубликованные условия изменились: отмените подготовленный черновик и загрузите актуальные данные"
        if (promo.status == PromoStatus.archived) issues += "Архивную кампанию сначала нужно восстановить"
        campaignRules.compatibilityIssue(promo)?.let { issues += it }
        issues += publicationIssues(bundle)
        var money = false
        try {
            validate(bundle)
            money = changesMoney(promo, bundle)
        } catch (e: AppException) {
            issues += e.message.orEmpty()
        }
        val finance = !money || scope.canAllPermission(MotivationPermissions.configureBonus)
        if (!finance) issues += "Изменение финансовых условий требует отдельного разрешения"
        val publication = scope.canAllPermission(MotivationPermissions.campaignPublish) &&
            scope.canAllPermission(MotivationPermissions.recommendationPublish)
        if (!publication) issues += "Нет права публикации кампании и рекомендаций"
        return PromoChangesViewDto(promo.id, prepared?.revision ?: 0L, pending != null, bundle, conflicting,
            prepared?.updatedAt, prepared?.updatedBy,
            scope.canAllPermission(MotivationPermissions.campaignEdit) &&
                scope.canAllPermission(MotivationPermissions.recommendationEdit) && promo.status != PromoStatus.archived,
            pending != null && issues.isEmpty() && publication && finance, issues.distinct())
    }

    private fun currentBundle(promo: PromoEntity) = PromoChangesBundleDto(
        UpdatePromoRequest(title = promo.title, brand = promo.brand, period = promo.period, budget = promo.budget,
            kpi = promo.kpi, cover = promo.cover, medusaProductId = promo.medusaProductId, productName = promo.productName,
            productImage = promo.productImage, barcode = promo.barcode, barcodes = promo.barcodes, ipartId = promo.ipartId,
            overrideImage = promo.overrideImage, overrideDescription = promo.overrideDescription,
            overrideCharacteristics = promo.overrideCharacteristics, pharmacistBonus = promo.pharmacistBonus,
            dateStart = promo.dateStart, dateEnd = promo.dateEnd), campaignRules.view(promo.id).config,
    )

    private fun fingerprint(promo: PromoEntity): String {
        val bundle = currentBundle(promo)
        fun ordered(refs: List<PromoRuleProductRefDto>) = refs.sortedWith(
            compareBy<PromoRuleProductRefDto> { it.triggerKind }.thenBy {
                if (it.triggerKind == "product") it.medusaProductId else it.triggerValue.orEmpty()
            },
        )
        val stableBundle = bundle.copy(rules = bundle.rules.copy(replacements = ordered(bundle.rules.replacements),
            crossSells = ordered(bundle.rules.crossSells)))
        val authoredRules = rules.findAllByPromoIdOrderByUpdatedAtDesc(promo.id).sortedBy { it.id }.map {
            mapOf("id" to it.id, "type" to it.type, "status" to it.status, "trigger" to it.trigger,
                "recommend" to it.recommend, "bonus" to it.bonus, "script" to it.script,
                "advantages" to it.advantages, "abTest" to it.abTest, "card" to it.card, "retiredAt" to it.retiredAt)
        }
        val data = mapper.valueToTree<JsonNode>(mapOf("bundle" to stableBundle, "status" to promo.status,
            "tiers" to promo.tiers.map { mapOf("minQty" to it.minQty, "bonus" to it.bonus) }, "rules" to authoredRules))
        return MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(canonical(data)))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun canonical(node: JsonNode): JsonNode = when {
        node.isObject -> mapper.createObjectNode().also { result ->
            node.fieldNames().asSequence().filter { it != "price" }.sorted()
                .forEach { result.set<JsonNode>(it, canonical(node[it])) }
        }
        node.isArray -> mapper.createArrayNode().also { result -> node.forEach { result.add(canonical(it)) } }
        else -> node
    }

    private fun load(id: String, mutation: Boolean = false) =
        (if (mutation) promos.findForUpdate(id) else promos.findById(id)).orElseThrow {
            AppException(ErrorCode.NOT_FOUND, "Кампания не найдена", HttpStatus.NOT_FOUND)
        }

    private fun prepared(id: String): Prepared? = jdbc.query(
        "select revision,base_fingerprint,bundle_json,updated_at,updated_by from promo_publication_changes where promo_id=?",
        { rs, _ -> Prepared(rs.getLong(1), rs.getString(2), rs.getString(3)?.let { mapper.readValue<PromoChangesBundleDto>(it) },
            rs.getTimestamp(4).toInstant(), rs.getString(5)) }, id,
    ).singleOrNull()

    private fun requireRevision(previous: Prepared?, expected: Long) {
        if (expected != (previous?.revision ?: 0L)) conflict("Черновик уже изменился: загрузите актуальное состояние")
    }

    private fun write(id: String, previous: Prepared?, fingerprint: String?, bundle: PromoChangesBundleDto?, actor: String): Long {
        val revision = (previous?.revision ?: 0L).let { if (it == Long.MAX_VALUE) conflict("Достигнут предел версии") else it + 1L }
        val body = bundle?.let(mapper::writeValueAsString)
        val now = Timestamp.from(Instant.now())
        if (previous == null) {
            jdbc.update("insert into promo_publication_changes(promo_id,revision,base_fingerprint,bundle_json,updated_at,updated_by) values(?,?,?,?,?,?)",
                id, revision, fingerprint, body, now, actor)
        } else if (jdbc.update("""update promo_publication_changes set revision=?,base_fingerprint=?,bundle_json=?,updated_at=?,updated_by=?
            where promo_id=? and revision=?""", revision, fingerprint, body, now, actor, id, previous.revision) != 1) {
            conflict("Черновик уже изменился")
        }
        return revision
    }

    private fun invalid(message: String): Nothing = throw AppException(ErrorCode.VALIDATION_FAILED, message, HttpStatus.BAD_REQUEST)
    private fun conflict(message: String): Nothing = throw AppException(ErrorCode.CONFLICT, message, HttpStatus.CONFLICT)
}
