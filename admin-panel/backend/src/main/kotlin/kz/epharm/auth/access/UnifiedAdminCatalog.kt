package kz.epharm.auth.access

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.policy.*
import org.springframework.stereotype.Component
import java.time.Duration

enum class ScopeIntegration { SCOPED, ALL_ONLY, NOT_READY }
data class CatalogPermission(val module: String, val subsection: String, val action: String, val kind: PermissionKind) {
    fun key() = requireNotNull(PermissionKey.parse(module, subsection, action))
}
data class AdminRoute(val api: String, val permission: CatalogPermission, val mode: ScopeIntegration)

/** Exact Spring patterns. Unknown/new routes deny managed accounts until explicitly reviewed. */
@Component
class UnifiedAdminCatalog(mapper: ObjectMapper) {
    val routes: List<AdminRoute> = javaClass.getResourceAsStream("/unified-admin-routes.json")!!.use {
        mapper.readValue<List<AdminRoute>>(it)
    }
    val viewRegistry = key("pharmacists", "registry", "view")
    val sensitive = key("pharmacists", "registry", "personal_data")
    val financial = key("pharmacists", "registry", "financial_data")
    val pharmacyFinancial = key("pharmacies_products", "pharmacies", "financial_data")
    val accountView = key("management", "accounts", "view")
    val accountManage = key("management", "accounts", "manage")
    val campaignPublish = key("motivation", "campaigns", "publish")
    val recommendationPublish = key("motivation", "recommendations", "publish")
    val configureBonus = key("motivation", "bonuses", "configure_bonus")
    final val definitions: List<PermissionDefinition>
    final val policy: UnifiedAccessPolicy

    init {
        require(routes.map { it.api }.distinct().size == routes.size)
        val keys = routes.filter { it.mode != ScopeIntegration.NOT_READY }.map { it.permission }.groupBy { it.key() }
        require(keys.values.all { group -> group.map { it.kind }.distinct().size == 1 })
        val explicit = listOf(
            PermissionDefinition(sensitive, PermissionKind.SENSITIVE_DATA),
            PermissionDefinition(financial, PermissionKind.FINANCIAL),
            PermissionDefinition(pharmacyFinancial, PermissionKind.FINANCIAL),
            PermissionDefinition(accountView, PermissionKind.SENSITIVE_DATA),
            PermissionDefinition(accountManage, PermissionKind.ACCESS_ADMINISTRATION, requiresPasswordReauthentication = true),
            PermissionDefinition(campaignPublish),
            PermissionDefinition(recommendationPublish),
            PermissionDefinition(configureBonus, PermissionKind.FINANCIAL),
        )
        require(explicit.all { definition -> keys[definition.key]?.all { it.kind == definition.kind } != false })
        val explicitKeys = explicit.map { it.key }.toSet()
        definitions = explicit + keys.filterKeys { it !in explicitKeys }.map { (key, group) -> PermissionDefinition(key, group.first().kind) }
        policy = UnifiedAccessPolicy(definitions, Duration.ofMinutes(5))
    }

    fun route(method: String, pattern: String) = routes.singleOrNull { it.api == "$method $pattern" }
    fun supported(key: PermissionKey) = key in setOf(sensitive, financial, pharmacyFinancial, accountView, accountManage,
        campaignPublish, recommendationPublish, configureBonus) ||
        routes.any { it.permission.key() == key && it.mode != ScopeIntegration.NOT_READY }

    fun availableRoles(key: PermissionKey): Set<AdminRole> = when {
        key == accountManage -> setOf(AdminRole.SYSTEM_ADMIN, AdminRole.HQ_HEAD)
        key.module == AdministrationModule.TRAINING -> setOf(AdminRole.SYSTEM_ADMIN, AdminRole.HQ_HEAD,
            AdminRole.TRAINING_MANAGER, AdminRole.REGIONAL_MANAGER, AdminRole.TRAINER)
        else -> setOf(AdminRole.SYSTEM_ADMIN, AdminRole.HQ_HEAD, AdminRole.CATEGORY_LEAD, AdminRole.BRAND_MANAGER, AdminRole.FINANCE_REVIEWER)
    }

    fun integration(key: PermissionKey): ScopeIntegration = when {
        !supported(key) -> ScopeIntegration.NOT_READY
        key in setOf(accountView, accountManage, financial, campaignPublish, recommendationPublish, configureBonus) -> ScopeIntegration.ALL_ONLY
        routes.any { it.permission.key() == key && it.mode == ScopeIntegration.ALL_ONLY } -> ScopeIntegration.ALL_ONLY
        else -> ScopeIntegration.SCOPED
    }

    companion object {
        fun key(module: String, subsection: String, action: String) =
            requireNotNull(PermissionKey.parse(module, subsection, action))
    }
}
