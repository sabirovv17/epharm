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
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.time.Instant

data class AuthorizedAdminRequest(val access: LoadedAccess, val permission: PermissionKey, val scope: DataScope)

/** Request-bound context, never a client filter or a scope supplied in JSON/query parameters. */
@Component
class AdministrativeScope(private val catalog: UnifiedAdminCatalog) {
    private fun current(): AuthorizedAdminRequest? =
        (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request
            ?.getAttribute(ATTRIBUTE) as? AuthorizedAdminRequest

    fun currentPharmacyIds(): Set<String>? = when (val scope = current()?.scope?.pharmacies) {
        is PharmacyScope.Assigned -> scope.ids.map { it.id }.toSet()
        else -> null // Legacy/internal callers and explicitly verified ALL requests retain their contract.
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
