package kz.epharm.auth.access

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
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
    final val definitions: List<PermissionDefinition>
    final val policy: UnifiedAccessPolicy

    init {
        require(routes.map { it.api }.distinct().size == routes.size)
        val keys = routes.filter { it.mode != ScopeIntegration.NOT_READY }.map { it.permission }.groupBy { it.key() }
        require(keys.values.all { group -> group.map { it.kind }.distinct().size == 1 })
        definitions = keys.map { (key, group) -> PermissionDefinition(key, group.first().kind) } + listOf(
            PermissionDefinition(sensitive, PermissionKind.SENSITIVE_DATA),
            PermissionDefinition(financial, PermissionKind.FINANCIAL),
            PermissionDefinition(pharmacyFinancial, PermissionKind.FINANCIAL),
            PermissionDefinition(accountView, PermissionKind.SENSITIVE_DATA),
            PermissionDefinition(accountManage, PermissionKind.ACCESS_ADMINISTRATION, requiresPasswordReauthentication = true),
        )
        policy = UnifiedAccessPolicy(definitions, Duration.ofMinutes(5))
    }

    fun route(method: String, pattern: String) = routes.singleOrNull { it.api == "$method $pattern" }
    fun supported(key: PermissionKey) = key in setOf(sensitive, financial, pharmacyFinancial, accountView, accountManage) ||
        routes.any { it.permission.key() == key && it.mode != ScopeIntegration.NOT_READY }

    companion object {
        fun key(module: String, subsection: String, action: String) =
            requireNotNull(PermissionKey.parse(module, subsection, action))
    }
}
