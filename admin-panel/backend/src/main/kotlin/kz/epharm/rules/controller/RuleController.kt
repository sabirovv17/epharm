package kz.epharm.rules.controller

import jakarta.validation.Valid
import kz.epharm.auth.security.AdminPrincipal
import kz.epharm.rules.dto.CreateRuleRequest
import kz.epharm.rules.dto.RuleDto
import kz.epharm.rules.dto.UpdateRuleRequest
import kz.epharm.rules.entity.RuleStatus
import kz.epharm.rules.entity.RuleType
import kz.epharm.rules.service.RuleService
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

// Главный CRUD-ресурс ТЗ §3.2.
// Аутентификация: JwtAuthenticationFilter. Все endpoint'ы требуют admin-токена.
// Role-based access (CATEGORY_LEAD создаёт/правит, BRAND_MANAGER read-only) — добавим
// в Этапе 3.3 через @PreAuthorize. Сейчас любой залогиненный admin может всё.
@RestController
@RequestMapping("/api/admin/rules")
class RuleController(
    private val ruleService: RuleService,
) {

    @GetMapping
    fun list(
        @RequestParam(required = false) type: RuleType?,
        @RequestParam(required = false) status: RuleStatus?,
    ): List<RuleDto> = ruleService.list(type = type, status = status)

    @GetMapping("/{id}")
    fun get(@PathVariable id: String): RuleDto = ruleService.get(id)

    @PostMapping
    fun create(
        @Valid @RequestBody req: CreateRuleRequest,
        @AuthenticationPrincipal principal: AdminPrincipal?,
    ): RuleDto = ruleService.create(req, createdBy = requireUserId(principal))

    @PatchMapping("/{id}")
    fun update(
        @PathVariable id: String,
        @Valid @RequestBody req: UpdateRuleRequest,
    ): RuleDto = ruleService.update(id, req)

    @PostMapping("/{id}/archive")
    fun archive(@PathVariable id: String): RuleDto = ruleService.archive(id)

    @PostMapping("/{id}/duplicate")
    fun duplicate(
        @PathVariable id: String,
        @AuthenticationPrincipal principal: AdminPrincipal?,
    ): RuleDto = ruleService.duplicate(id, createdBy = requireUserId(principal))

    private fun requireUserId(principal: AdminPrincipal?): String =
        principal?.userId?.toString()
            ?: throw AppException(ErrorCode.UNAUTHORIZED, "Not authenticated", HttpStatus.UNAUTHORIZED)
}
