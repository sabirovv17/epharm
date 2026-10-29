package kz.epharm.auth.access

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kz.epharm.auth.policy.*
import kz.epharm.auth.security.AdminPrincipal
import org.springframework.context.annotation.Configuration
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.HandlerMapping
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.time.Instant

@Configuration
class UnifiedAdminInterceptor(private val store: UnifiedAccessStore, private val catalog: UnifiedAdminCatalog) :
    HandlerInterceptor, WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(this).addPathPatterns("/api/admin/**")
    }

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        val path = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)?.toString()
            ?: return true
        if (path in SELF_ROUTES || handler is HandlerMethod && handler.beanType == UnifiedAccessController::class.java &&
            "${request.method} $path" in ACCESS_ROUTES) return true
        val principal = SecurityContextHolder.getContext().authentication?.principal as? AdminPrincipal ?: return true
        val access = store.load(principal) ?: AdministrativeScope.deny()
        if (!access.managed) return true
        if (access.profile == null || handler !is HandlerMethod) AdministrativeScope.deny()
        val route = catalog.route(request.method, path) ?: AdministrativeScope.deny()
        if (route.mode == ScopeIntegration.NOT_READY) AdministrativeScope.deny()
        val key = route.permission.key()
        val grant = access.actor.grants.singleOrNull { it.permission == key }
        val scope = if (access.actor.primaryHqOwner && route.permission.kind == PermissionKind.ORDINARY) {
            DataScope(PharmacyScope.All)
        } else grant?.scope ?: AdministrativeScope.deny()
        if (route.mode == ScopeIntegration.ALL_ONLY && scope.pharmacies !is PharmacyScope.All) AdministrativeScope.deny()
        if (!catalog.policy.decide(access.actor, AccessRequest(key, scope), Instant.now()).allowed) AdministrativeScope.deny()
        request.setAttribute(AdministrativeScope.ATTRIBUTE, AuthorizedAdminRequest(access, key, scope))
        return true
    }

    companion object {
        private val SELF_ROUTES = setOf("/api/admin/auth/login", "/api/admin/auth/refresh", "/api/admin/auth/me", "/api/admin/auth/logout")
        private val ACCESS_ROUTES = setOf("GET /api/admin/access/me", "GET /api/admin/access/catalog",
            "GET /api/admin/access/accounts", "GET /api/admin/access/people",
            "GET /api/admin/access/accounts/{id}", "POST /api/admin/access/accounts", "PUT /api/admin/access/accounts/{id}/grants")
    }
}
