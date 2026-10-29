package kz.epharm.auth.access

import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.entity.AdminUserEntity
import kz.epharm.auth.policy.*
import kz.epharm.auth.repository.AdminUserRepository
import kz.epharm.auth.security.AdminPrincipal
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import kz.epharm.shared.identity.*
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.util.UUID

// Regular classes deliberately avoid data-class toString() containing passwords.
class CreateManagedAccountRequest(
    @field:Email @field:NotBlank @field:Size(max = 255) val email: String,
    @field:NotBlank @field:Size(max = 255) val name: String,
    @field:Size(min = 12, max = 72) val password: String,
    val role: AdminRole,
    val verifiedPersonId: UUID,
    @field:Size(max = 255) val jobTitle: String = "",
    @field:Size(max = 128) val currentPassword: String,
)
class ReplaceGrantsRequest(
    val expectedVersion: Long,
    @field:Size(max = 200) val grants: List<StoredGrant>,
    @field:Size(max = 128) val currentPassword: String,
)

@RestController
@RequestMapping("/api/admin/access")
class UnifiedAccessController(private val service: UnifiedAccountAccessService, private val catalog: UnifiedAdminCatalog) {
    @GetMapping("/me")
    fun capabilities(@AuthenticationPrincipal principal: AdminPrincipal) = service.capabilities(principal)

    @GetMapping("/catalog")
    fun catalog(@AuthenticationPrincipal principal: AdminPrincipal): Map<String, Any> {
        service.requireManagementView(principal)
        return mapOf("routes" to catalog.routes, "permissions" to catalog.definitions.map {
            mapOf("module" to it.key.module.identifier, "subsection" to it.key.subsection,
                "action" to it.key.action, "kind" to it.kind, "requiresPasswordReauthentication" to it.requiresPasswordReauthentication)
        })
    }

    @GetMapping("/accounts/{id}")
    fun account(@AuthenticationPrincipal principal: AdminPrincipal, @PathVariable id: UUID): AccessProfile {
        service.requireManagementView(principal)
        return service.profile(id)
    }

    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@AuthenticationPrincipal principal: AdminPrincipal, @Valid @RequestBody req: CreateManagedAccountRequest) =
        service.create(principal, req)

    @PutMapping("/accounts/{id}/grants")
    fun replace(@AuthenticationPrincipal principal: AdminPrincipal, @PathVariable id: UUID,
        @Valid @RequestBody req: ReplaceGrantsRequest) = service.replace(principal, id, req)
}

data class AccessCapabilities(
    val managed: Boolean, val version: Long?, val grants: List<StoredGrant>,
    val roleCeiling: AdminRole,
    val note: String = "Explicit grants also require existing role checks; NOT_READY routes deny managed accounts",
)

@Service
class UnifiedAccountAccessService(
    private val store: UnifiedAccessStore,
    private val catalog: UnifiedAdminCatalog,
    private val users: AdminUserRepository,
    private val jdbc: JdbcTemplate,
    private val encoder: PasswordEncoder,
) {
    fun capabilities(principal: AdminPrincipal): AccessCapabilities {
        val access = store.load(principal) ?: AdministrativeScope.deny()
        return AccessCapabilities(access.managed, access.profile?.version, access.profile?.grants.orEmpty(), access.role)
    }

    fun requireManagementView(principal: AdminPrincipal) {
        val actor = store.load(principal)?.actor ?: AdministrativeScope.deny()
        if (!catalog.policy.decide(actor, AccessRequest(catalog.accountView, DataScope(PharmacyScope.All)), Instant.now()).allowed) {
            AdministrativeScope.deny()
        }
    }

    fun profile(id: UUID) = store.profile(id) ?: throw AppException(ErrorCode.NOT_FOUND, "Аккаунт доступа не найден", HttpStatus.NOT_FOUND)

    private fun passwordProof(principal: AdminPrincipal, password: String): Pair<LoadedAccess, ReauthenticationEvidence> {
        val access = store.load(principal) ?: AdministrativeScope.deny()
        if (access.profile == null) AdministrativeScope.deny()
        val hash = jdbc.queryForObject("select password_hash from admin_users where id=?", String::class.java, principal.userId)
        if (!access.actor.hqAccount || !encoder.matches(password, hash)) AdministrativeScope.deny()
        val proof = ReauthenticationEvidence(access.actor.account, ReauthenticationMethod.PASSWORD, Instant.now())
        // This permission is intentionally not granted by HQ title, legacy role or primary-owner bypass.
        if (access.actor.grants.none { it.permission == catalog.accountManage && it.scope.pharmacies is PharmacyScope.All }) {
            AdministrativeScope.deny()
        }
        return access to proof
    }

    @Transactional
    fun create(principal: AdminPrincipal, req: CreateManagedAccountRequest): AccessProfile {
        store.lockAccounts(setOf(principal.userId))
        val (access, _) = passwordProof(principal, req.currentPassword)
        val actor = access.actor
        if (actor.person?.id == req.verifiedPersonId.toString()) AdministrativeScope.deny()
        if (req.password.toByteArray(Charsets.UTF_8).size > 72) {
            throw AppException(ErrorCode.VALIDATION_FAILED, "Пароль превышает допустимую длину UTF-8")
        }
        // Human links must be verified before provisioning; a name/email match cannot create one.
        if (jdbc.queryForObject("select count(*) from unified_persons where id=?", Long::class.java, req.verifiedPersonId) != 1L) {
            throw AppException(ErrorCode.VALIDATION_FAILED, "Требуется проверенная связь с человеком")
        }
        if (users.findByEmailIgnoreCase(req.email).isPresent) throw AppException(ErrorCode.CONFLICT, "Email уже используется", HttpStatus.CONFLICT)
        // Persisted legacy role is a ceiling, not an authority borrowed from the delegator.
        if (req.role != access.role && access.role !in setOf(AdminRole.SYSTEM_ADMIN, AdminRole.HQ_HEAD)) AdministrativeScope.deny()
        val user = users.saveAndFlush(AdminUserEntity(email = req.email.trim().lowercase(), name = req.name.trim(),
            passwordHash = encoder.encode(req.password), roleRaw = req.role.name))
        jdbc.update("update admin_users set access_mode='MANAGED' where id=?", user.id)
        jdbc.update("insert into unified_admin_profiles(account_id,person_id,job_title) values(?,?,?)", user.id, req.verifiedPersonId, req.jobTitle)
        store.audit(principal.userId, user.id, "CREATE_EMPTY", 0)
        return profile(user.id) // Empty permissions: no accidental access at first login.
    }

    @Transactional
    fun replace(principal: AdminPrincipal, target: UUID, req: ReplaceGrantsRequest): AccessProfile {
        store.lockAccounts(setOf(principal.userId, target))
        val (access, proof) = passwordProof(principal, req.currentPassword)
        val recipientUser = users.findById(target).orElse(null) ?: AdministrativeScope.deny()
        val recipient = store.load(AdminPrincipal(target, recipientUser.email, recipientUser.name, recipientUser.role, recipientUser.company))
            ?: AdministrativeScope.deny()
        if (!recipient.managed || recipient.profile == null || recipient.actor.primaryHqOwner ||
            recipient.actor.account == access.actor.account || recipient.actor.person == access.actor.person) AdministrativeScope.deny()
        val grants = try { req.grants.map { it.toGrant() } } catch (_: IllegalArgumentException) {
            throw AppException(ErrorCode.VALIDATION_FAILED, "Некорректное разрешение или область аптек")
        }
        if (grants.map { it.permission }.distinct().size != grants.size || grants.any { !catalog.supported(it.permission) }) {
            throw AppException(ErrorCode.VALIDATION_FAILED, "Неизвестное, повторное или ещё не подключённое разрешение")
        }
        val ids = req.grants.flatMap { it.pharmacyIds }.toSet()
        if (ids.size > 2000) throw AppException(ErrorCode.VALIDATION_FAILED, "Слишком большая область аптек")
        val known = if (ids.isEmpty()) 0L else jdbc.queryForObject(
            "select count(*) from pharmacies where id in (${ids.joinToString(",") { "?" }})", Long::class.java, *ids.toTypedArray(),
        )
        if (known != ids.size.toLong()) {
            throw AppException(ErrorCode.VALIDATION_FAILED, "Аптеки не подтверждены")
        }
        for (grant in grants) {
            if (!catalog.policy.delegate(access.actor, recipient.actor, grant, catalog.accountManage, Instant.now(), proof).allowed) AdministrativeScope.deny()
        }
        // Empty replacement still cannot change oneself or bypass the separate control/password check above.
        if (!store.replace(principal.userId, target, req.expectedVersion, req.grants)) {
            throw AppException(ErrorCode.CONFLICT, "Права уже изменились: обновите страницу", HttpStatus.CONFLICT)
        }
        return profile(target)
    }
}
