package kz.epharm.auth.access

import kz.epharm.auth.policy.*
import kz.epharm.pharmacists.dto.PharmacistDto
import kz.epharm.pharmacists.dto.UpdatePharmacistRequest
import kz.epharm.pharmacies.dto.UpdatePharmacyRequest
import kz.epharm.pharmacies.dto.PharmacyDto
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import kz.epharm.shared.identity.*
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.time.Instant
import java.util.UUID

data class AuthorizedAdminRequest(val access: LoadedAccess, val permission: PermissionKey, val scope: DataScope)

/** Request-bound context, never a client filter or a scope supplied in JSON/query parameters. */
@Component
class AdministrativeScope(private val catalog: UnifiedAdminCatalog, private val store: UnifiedAccessStore) {
    private fun current(): AuthorizedAdminRequest? =
        (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request
            ?.getAttribute(ATTRIBUTE) as? AuthorizedAdminRequest

    fun currentPharmacyIds(): Set<String>? = when (val scope = current()?.scope?.pharmacies) {
        is PharmacyScope.Assigned -> scope.ids.map { it.id }.toSet()
        null, PharmacyScope.All -> null // Existing legacy/internal callers retain their old contract.
        else -> deny()
    }

    fun requireExplicitPermission(permission: PermissionKey) {
        val context = current() ?: deny()
        if (!catalog.policy.decide(context.access.actor, AccessRequest(permission, context.scope), Instant.now()).allowed) deny()
    }

    /** Lock the current authority before domain locks; revocation and money writes serialize. */
    fun lockCurrentActor() {
        check(TransactionSynchronizationManager.isActualTransactionActive()) { "Authority locks require a write transaction" }
        val previous = current() ?: deny()
        val id = UUID.fromString(previous.access.actor.account.id)
        store.lockAccounts(setOf(id))
        val fresh = store.loadAccount(id) ?: deny()
        if (fresh.profile == null || fresh.role !in catalog.availableRoles(previous.permission) ||
            !catalog.policy.authorizeScope(fresh.actor, AccessRequest(previous.permission, previous.scope), Instant.now()).allowed) deny()
        (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request
            ?.setAttribute(ATTRIBUTE, previous.copy(access = fresh)) ?: deny()
    }

    fun currentMerchBranchIds(): Set<UUID>? = when (val scope = (current() ?: deny()).scope.pharmacies) {
        PharmacyScope.AllMerchBranches -> null
        is PharmacyScope.MerchBranches -> scope.ids.map { UUID.fromString(it.id) }.toSet()
        else -> deny()
    }

    fun canMerchBranchPermission(permission: PermissionKey, branchId: UUID): Boolean {
        val context = current() ?: return false
        return catalog.policy.decide(context.access.actor, AccessRequest(permission, DataScope(
            PharmacyScope.MerchBranches(listOf(CanonicalIdentity(CanonicalEntityType.MERCH_BRANCH, branchId.toString()))))), Instant.now()).allowed
    }

    fun requireMerchBranchPermission(permission: PermissionKey, branchId: UUID) {
        if (!canMerchBranchPermission(permission, branchId)) deny()
    }

    fun currentProducerIds(): Set<UUID>? = when (val scope = (current() ?: deny()).scope.owners) {
        OwnerScope.AllCustomers -> null
        is OwnerScope.Customers -> scope.ids.map { UUID.fromString(it.id) }.toSet()
        else -> deny()
    }

    fun requireMerchPlacementPermission(permission: PermissionKey, branchIds: Set<UUID>, producerId: UUID) {
        if (!canMerchPlacementPermission(permission, branchIds, producerId)) deny()
    }

    fun canMerchPlacementPermission(permission: PermissionKey, branchIds: Set<UUID>, producerId: UUID): Boolean {
        val context = current() ?: return false
        if (branchIds.isEmpty()) return false
        val scope = DataScope(PharmacyScope.MerchBranches(branchIds.map {
            CanonicalIdentity(CanonicalEntityType.MERCH_BRANCH, it.toString()) }),
            OwnerScope.Customers(listOf(CanonicalIdentity(CanonicalEntityType.CUSTOMER, producerId.toString()))))
        return catalog.policy.decide(context.access.actor, AccessRequest(permission, scope), Instant.now()).allowed
    }

    fun requireTaskReviewPermission(permission: PermissionKey, branchId: UUID, executorPersonId: UUID) {
        val context = current() ?: deny()
        val person = CanonicalIdentity(CanonicalEntityType.PERSON, executorPersonId.toString())
        val scope = DataScope(PharmacyScope.MerchBranches(listOf(
            CanonicalIdentity(CanonicalEntityType.MERCH_BRANCH, branchId.toString()))), OwnerScope.Persons(listOf(person)))
        if (!catalog.policy.decide(context.access.actor, AccessRequest(permission, scope,
                ReviewContext(person)), Instant.now()).allowed) deny()
    }

    fun currentVerifiedPersonId(): UUID = (current() ?: deny()).access.actor.person?.id?.let(UUID::fromString) ?: deny()

    /** An explicit HQ exception for a free anonymous report, never proof of an independent executor. */
    fun requireManualUnverifiedTaskReview(permission: PermissionKey, branchId: UUID) {
        check(TransactionSynchronizationManager.isActualTransactionActive())
        val context = current() ?: deny()
        val actor = context.access.actor
        val scope = DataScope(PharmacyScope.MerchBranches(listOf(
            CanonicalIdentity(CanonicalEntityType.MERCH_BRANCH, branchId.toString()))))
        if (permission != catalog.taskReview || !actor.hqAccount || actor.person == null ||
            actor.grants.none { it.permission == permission && it.scope.covers(scope) } ||
            !catalog.policy.authorizeScope(actor, AccessRequest(permission, scope), Instant.now()).allowed) deny()
    }

    fun canPharmacyPermission(permission: PermissionKey, pharmacyId: String): Boolean {
        val context = current() ?: return false
        val scope = if (pharmacyId.isBlank()) DataScope(PharmacyScope.All) else DataScope(PharmacyScope.Assigned(
            listOf(CanonicalIdentity(CanonicalEntityType.PHARMACY, pharmacyId))))
        return catalog.policy.decide(context.access.actor, AccessRequest(permission, scope), Instant.now()).allowed
    }

    fun requireAllPermission(permission: PermissionKey) {
        if (!canAllPermission(permission)) deny()
    }

    fun canAllPermission(permission: PermissionKey): Boolean {
        val context = current() ?: return true
        return context.scope.pharmacies is PharmacyScope.All &&
            catalog.policy.decide(context.access.actor, AccessRequest(permission, DataScope(PharmacyScope.All)), Instant.now()).allowed
    }

    fun hasManagedRequest(): Boolean = current() != null

    fun requirePharmacistUpdate(req: UpdatePharmacistRequest) {
        if (current() == null) return
        // Generic edit never doubles as credential recovery, ledger adjustment or result correction.
        if (req.phone != null || req.password != null || req.balance != null || req.coursesDone != null ||
            req.coursesTotal != null || req.status != null || req.tier != null) deny()
    }

    fun requirePharmacyUpdate(req: UpdatePharmacyRequest) {
        if (current() != null && (req.receipts30d != null || req.gmv30d != null || req.rulesAccepted != null)) deny()
    }

    fun pharmacist(dto: PharmacistDto): PharmacistDto {
        val context = current() ?: return dto
        val scope = if (dto.pharmacyId.isBlank()) DataScope(PharmacyScope.All) else DataScope(PharmacyScope.Assigned(
            listOf(CanonicalIdentity(CanonicalEntityType.PHARMACY, dto.pharmacyId)),
        ))
        fun can(key: PermissionKey) = catalog.policy.decide(context.access.actor, AccessRequest(key, scope), Instant.now()).allowed
        val personal = can(catalog.sensitive)
        // Wallet/counters belong to the whole person, not their current pharmacy (D6).
        val finance = catalog.policy.decide(context.access.actor,
            AccessRequest(catalog.financial, DataScope(PharmacyScope.All)), Instant.now()).allowed
        return dto.copy(iin = if (personal) dto.iin else null, phone = if (personal) dto.phone else null,
            earned30d = if (finance) dto.earned30d else null, balance = if (finance) dto.balance else null,
            receipts30d = if (finance) dto.receipts30d else null, rulesAccepted30d = if (finance) dto.rulesAccepted30d else null)
    }

    fun pharmacy(dto: PharmacyDto): PharmacyDto {
        val context = current() ?: return dto
        val scope = DataScope(PharmacyScope.Assigned(listOf(CanonicalIdentity(CanonicalEntityType.PHARMACY, dto.id))))
        val finance = catalog.policy.decide(context.access.actor, AccessRequest(catalog.pharmacyFinancial, scope), Instant.now()).allowed
        return dto.copy(gmv30d = if (finance) dto.gmv30d else null)
    }

    companion object {
        const val ATTRIBUTE = "epharm.verified-admin-scope"
        fun deny(): Nothing = throw AppException(ErrorCode.FORBIDDEN, "Недостаточно прав для операции", HttpStatus.FORBIDDEN)
    }
}
