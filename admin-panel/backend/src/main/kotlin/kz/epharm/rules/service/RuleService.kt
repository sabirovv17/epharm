package kz.epharm.rules.service

import kz.epharm.catalog.repository.ProductRepository
import kz.epharm.rules.dto.CreateRuleRequest
import kz.epharm.rules.dto.RuleDto
import kz.epharm.rules.dto.TriggerDto
import kz.epharm.rules.dto.UpdateRuleRequest
import kz.epharm.rules.entity.RuleEntity
import kz.epharm.rules.entity.RuleStatus
import kz.epharm.rules.entity.RuleType
import kz.epharm.rules.repository.RuleRepository
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class RuleService(
    private val ruleRepository: RuleRepository,
    private val productRepository: ProductRepository,
) {

    @Transactional(readOnly = true)
    fun list(type: RuleType? = null, status: RuleStatus? = null): List<RuleDto> {
        val all = when {
            type != null && status != null -> ruleRepository.findAllByTypeRawOrderByUpdatedAtDesc(type.name)
                .filter { it.statusRaw == status.name }
            type != null  -> ruleRepository.findAllByTypeRawOrderByUpdatedAtDesc(type.name)
            status != null -> ruleRepository.findAllByStatusRawOrderByUpdatedAtDesc(status.name)
            else -> ruleRepository.findAllByOrderByUpdatedAtDesc()
        }
        return all.map(RuleDto::of)
    }

    @Transactional(readOnly = true)
    fun get(id: String): RuleDto = RuleDto.of(loadOrThrow(id))

    @Transactional
    fun create(req: CreateRuleRequest, createdBy: String): RuleDto {
        validateTriggerShape(req.trigger)
        ensureProductExists(req.recommend)
        ensureNotSelfReference(req.trigger, req.recommend)
        val newId = generateId(req.type)
        val entity = RuleEntity(
            id = newId,
            recommend = req.recommend,
            bonus = req.bonus,
            script = req.script,
            advantages = req.advantages,
            abTest = req.abTest?.toEntity(),
            trigger = req.trigger.toEntity(),
            createdBy = createdBy,
        ).also {
            it.type = req.type
            it.status = req.status ?: RuleStatus.draft
        }
        return RuleDto.of(ruleRepository.save(entity))
    }

    @Transactional
    fun update(id: String, req: UpdateRuleRequest): RuleDto {
        val entity = loadOrThrow(id)
        if (entity.status == RuleStatus.archived) {
            throw AppException(
                ErrorCode.CONFLICT,
                "Archived rule cannot be edited (restore first)",
                HttpStatus.CONFLICT,
            )
        }
        // PATCH не должен ставить status=archived — это side-channel в обход
        // dedicated /archive endpoint'а. Frontend полагается на /archive
        // для отдельного toast'а / audit-event'а.
        if (req.status == RuleStatus.archived) {
            throw AppException(
                ErrorCode.VALIDATION_FAILED,
                "Use POST /rules/{id}/archive to archive a rule",
                HttpStatus.BAD_REQUEST,
            )
        }
        req.trigger?.let {
            validateTriggerShape(it)
            entity.trigger = it.toEntity()
        }
        req.recommend?.let {
            ensureProductExists(it)
            entity.recommend = it
        }
        // Self-reference check после применения новых trigger / recommend.
        ensureNotSelfReference(
            triggerKind = entity.trigger.kind,
            triggerValue = entity.trigger.value,
            recommend = entity.recommend,
        )
        req.status?.let { entity.status = it }
        req.bonus?.let { entity.bonus = it }
        req.script?.let { entity.script = it }
        req.advantages?.let { entity.advantages = it }
        when {
            req.clearAbTest -> entity.abTest = null
            req.abTest != null -> entity.abTest = req.abTest.toEntity()
        }
        return RuleDto.of(ruleRepository.save(entity))
    }

    @Transactional
    fun archive(id: String): RuleDto {
        val entity = loadOrThrow(id)
        if (entity.status == RuleStatus.archived) return RuleDto.of(entity)
        entity.status = RuleStatus.archived
        return RuleDto.of(ruleRepository.save(entity))
    }

    @Transactional
    fun duplicate(id: String, createdBy: String): RuleDto {
        val src = loadOrThrow(id)
        val copy = RuleEntity(
            id = generateId(src.type),
            recommend = src.recommend,
            bonus = src.bonus,
            script = src.script,
            advantages = src.advantages.toList(),
            abTest = src.abTest,
            trigger = src.trigger.copy(),
            createdBy = createdBy,
        ).also {
            it.type = src.type
            // Дубликаты всегда draft — чтобы кто-то осознанно включил.
            it.status = RuleStatus.draft
        }
        return RuleDto.of(ruleRepository.save(copy))
    }

    // ── Internals ─────────────────────────────────────────────────────────

    private fun loadOrThrow(id: String): RuleEntity =
        ruleRepository.findById(id).orElseThrow {
            AppException(
                ErrorCode.NOT_FOUND,
                "Rule $id not found",
                HttpStatus.NOT_FOUND,
            )
        }

    private fun ensureProductExists(productId: String) {
        if (!productRepository.existsById(productId)) {
            throw AppException(
                ErrorCode.VALIDATION_FAILED,
                "Recommend product $productId does not exist",
                HttpStatus.BAD_REQUEST,
            )
        }
    }

    /**
     * Проверка соответствия shape поля `value` значению `kind`:
     *   kind=product      → value: String (productId)
     *   kind=product_any  → value: List<String> (productIds, не пустой)
     *   kind=mnn          → value: String (название МНН)
     *
     * До этой проверки `value: Any` принимает что угодно — потенциально
     * сохраняли rule с trigger.kind=product, value=["a","b"], что ломает
     * POSM-матчер в рантайме.
     */
    private fun validateTriggerShape(trigger: TriggerDto) {
        when (trigger.kind) {
            "product", "mnn" -> {
                if (trigger.value !is String || (trigger.value as String).isBlank()) {
                    throw AppException(
                        ErrorCode.VALIDATION_FAILED,
                        "trigger.value must be a non-empty string for kind=${trigger.kind}",
                        HttpStatus.BAD_REQUEST,
                    )
                }
            }
            "product_any" -> {
                val list = trigger.value as? List<*>
                if (list.isNullOrEmpty() || list.any { it !is String || it.isBlank() }) {
                    throw AppException(
                        ErrorCode.VALIDATION_FAILED,
                        "trigger.value must be a non-empty array of strings for kind=product_any",
                        HttpStatus.BAD_REQUEST,
                    )
                }
            }
            else -> {
                // @Pattern в DTO уже должен это поймать, но контракт явный.
                throw AppException(
                    ErrorCode.VALIDATION_FAILED,
                    "Unknown trigger.kind=${trigger.kind}",
                    HttpStatus.BAD_REQUEST,
                )
            }
        }
    }

    /**
     * Запрет на правило «рекомендую тот же товар, что и триггер» — бессмысленно
     * для substitution (заменить X на X) и для crosssell (купи X к покупке X).
     * Для kind=mnn проверка не имеет смысла (МНН-группа vs productId).
     */
    private fun ensureNotSelfReference(trigger: TriggerDto, recommend: String) {
        ensureNotSelfReference(
            triggerKind = trigger.kind,
            triggerValue = trigger.value,
            recommend = recommend,
        )
    }

    private fun ensureNotSelfReference(triggerKind: String, triggerValue: Any, recommend: String) {
        val conflict = when (triggerKind) {
            "product" -> triggerValue == recommend
            "product_any" -> (triggerValue as? List<*>)?.contains(recommend) == true
            else -> false // mnn — recommend это productId, trigger.value это название МНН
        }
        if (conflict) {
            throw AppException(
                ErrorCode.VALIDATION_FAILED,
                "Rule cannot recommend the same product that triggers it (recommend=$recommend)",
                HttpStatus.BAD_REQUEST,
            )
        }
    }

    private fun generateId(type: RuleType): String {
        val short = UUID.randomUUID().toString().substring(0, 8)
        // 'r_' prefix совпадает с seed-форматом, чтобы UI и логи были однородны.
        val typeMark = if (type == RuleType.crosssell) "x" else "s"
        return "r_${typeMark}_$short"
    }
}
