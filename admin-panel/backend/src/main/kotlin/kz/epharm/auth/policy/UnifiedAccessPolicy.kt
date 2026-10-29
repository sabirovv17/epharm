package kz.epharm.auth.policy

import kz.epharm.shared.identity.CanonicalEntityType
import kz.epharm.shared.identity.CanonicalIdentity
import java.time.Duration
import java.time.Instant
import java.util.Collections

private val IDENTIFIER = Regex("[a-z][a-z0-9_]{0,63}")
private fun <T> frozen(values: Collection<T>): Set<T> = Collections.unmodifiableSet(values.toSet())

enum class AdministrationModule(val identifier: String) {
    ANALYTICS("analytics"), MOTIVATION("motivation"), TRAINING("training"),
    MERCHANDISING("merchandising"), PHARMACIES_PRODUCTS("pharmacies_products"),
    PHARMACISTS("pharmacists"), MANAGEMENT("management"),
}

/** Exact identifiers only. There are no wildcard, title, prefix or inherited-role permissions. */
data class PermissionKey(val module: AdministrationModule, val subsection: String, val action: String) {
    init {
        require(IDENTIFIER.matches(subsection) && IDENTIFIER.matches(action)) { "Invalid permission identifier" }
    }

    companion object {
        fun parse(module: String?, subsection: String?, action: String?): PermissionKey? {
            val known = AdministrationModule.entries.singleOrNull { it.identifier == module } ?: return null
            if (subsection == null || action == null || !IDENTIFIER.matches(subsection) || !IDENTIFIER.matches(action)) return null
            return PermissionKey(known, subsection, action)
        }
    }
}

enum class PermissionKind { ORDINARY, SENSITIVE_DATA, FINANCIAL, EXPORT, CREDENTIAL_EXPORT, POSM_TECHNICAL, ACCESS_ADMINISTRATION }
enum class AccessRealm { ADMINISTRATION, LEARNER, DEVICE, PRODUCER }
enum class ReauthenticationMethod { PASSWORD, SMS, REFRESH }

sealed interface PharmacyScope {
    data object All : PharmacyScope
    class Assigned(ids: Collection<CanonicalIdentity>) : PharmacyScope {
        val ids = frozen(ids)
        init { require(this.ids.all { it.entityType == CanonicalEntityType.PHARMACY }) }
    }
}

sealed interface OwnerScope {
    data object All : OwnerScope
    class Persons(ids: Collection<CanonicalIdentity>) : OwnerScope {
        val ids = frozen(ids)
        init { require(this.ids.all { it.entityType == CanonicalEntityType.PERSON }) }
    }
}

/** Historical rows use their original pharmacy; a person's current pharmacy cannot replace it. */
class DataScope(
    val pharmacies: PharmacyScope,
    val owners: OwnerScope = OwnerScope.All,
    fields: Collection<String> = emptySet(),
) {
    val fields = frozen(fields)
    init { require(this.fields.all(IDENTIFIER::matches)) { "Invalid field identifier" } }

    internal fun valid() =
        (pharmacies !is PharmacyScope.Assigned || pharmacies.ids.isNotEmpty()) &&
            (owners !is OwnerScope.Persons || owners.ids.isNotEmpty())

    internal fun withFields(fields: Collection<String>) = DataScope(pharmacies, owners, fields)

    internal fun covers(other: DataScope): Boolean = valid() && other.valid() &&
        (pharmacies is PharmacyScope.All || pharmacies is PharmacyScope.Assigned &&
            other.pharmacies is PharmacyScope.Assigned && pharmacies.ids.containsAll(other.pharmacies.ids)) &&
        (owners is OwnerScope.All || owners is OwnerScope.Persons &&
            other.owners is OwnerScope.Persons && owners.ids.containsAll(other.owners.ids)) &&
        fields.containsAll(other.fields)
}

data class PermissionGrant(val permission: PermissionKey, val scope: DataScope)

/** Server-owned metadata, not an API for choosing one's own permission category or field policy. */
class PermissionDefinition(
    val key: PermissionKey,
    val kind: PermissionKind = PermissionKind.ORDINARY,
    fields: Collection<String> = emptySet(),
    val viewPermission: PermissionKey? = null,
    fieldPermissions: Map<String, PermissionKey> = emptyMap(),
    val independentReview: Boolean = false,
    val requiresPasswordReauthentication: Boolean = false,
) {
    val fields = frozen(fields)
    val fieldPermissions: Map<String, PermissionKey> = Collections.unmodifiableMap(fieldPermissions.toMap())
    init {
        require(this.fields.all(IDENTIFIER::matches))
        require(this.fields.containsAll(this.fieldPermissions.keys))
        require(kind !in setOf(PermissionKind.EXPORT, PermissionKind.CREDENTIAL_EXPORT) || viewPermission != null) {
            "An export requires an explicit row-view prerequisite"
        }
    }
}

/** Trusted facts from future account/identity resolution. A job title is display metadata only. */
class AdministrativeActor(
    val account: CanonicalIdentity,
    val realm: AccessRealm,
    val active: Boolean,
    val person: CanonicalIdentity? = null,
    val primaryHqOwner: Boolean = false,
    val hqAccount: Boolean = false,
    val jobTitle: String? = null,
    grants: Collection<PermissionGrant> = emptyList(),
) {
    val grants = frozen(grants)
}

data class ReauthenticationEvidence(
    val account: CanonicalIdentity,
    val method: ReauthenticationMethod,
    val verifiedAt: Instant,
)

/** Authorship deliberately does not prove execution and never forbids reviewing another person. */
data class ReviewContext(val executorPerson: CanonicalIdentity?, val authorAccount: CanonicalIdentity? = null)
data class AccessRequest(val permission: PermissionKey?, val scope: DataScope, val review: ReviewContext? = null)

enum class DecisionReason {
    ALLOWED, ADMINISTRATION_REQUIRED, INACTIVE_ACCOUNT, INVALID_ACCOUNT_IDENTITY, UNKNOWN_PERMISSION,
    EMPTY_SCOPE, UNKNOWN_FIELD, MISSING_GRANT, HQ_REQUIRED, PASSWORD_REAUTHENTICATION_REQUIRED,
    UNRESOLVED_REVIEW_IDENTITY, SELF_REVIEW, REVIEW_SCOPE_MISMATCH, PREREQUISITE_DENIED,
    INVALID_CATALOG, SELF_PERMISSION_CHANGE, DELEGATION_REQUIRED, INVALID_RECIPIENT, UNRESOLVED_DELEGATION_IDENTITY,
}

data class AccessDecision(val allowed: Boolean, val reason: DecisionReason)

/**
 * Unwired pure core for U2, not a completed RBAC rollout. No Spring bean, current roles, API,
 * database, personal /learn or device authentication use it. No runtime catalog is defined here.
 * Callers must supply server-verified identities, historical row/owner scope, requested projection,
 * a reviewed immutable catalog, trusted current time and password-verification evidence. An allow
 * decision applies only to that exact operation/scope; it is not an unscoped query/filter builder.
 */
class UnifiedAccessPolicy(definitions: Collection<PermissionDefinition>, private val reauthenticationMaxAge: Duration) {
    private val catalog = definitions.associateBy { it.key }

    init {
        require(!reauthenticationMaxAge.isNegative && !reauthenticationMaxAge.isZero) { "Positive trusted reauthentication age required" }
        require(catalog.size == definitions.size) { "Duplicate permission definitions" }
        require(catalog.values.all { definition ->
            (definition.viewPermission == null || definition.viewPermission in catalog) &&
                definition.fieldPermissions.values.all { it in catalog }
        }) { "Unknown permission prerequisite" }
    }

    fun decide(
        actor: AdministrativeActor?, request: AccessRequest, now: Instant,
        reauthentication: ReauthenticationEvidence? = null,
    ): AccessDecision = decision(boundary(actor) ?: if (catalog[request.permission]?.kind == PermissionKind.ACCESS_ADMINISTRATION) {
        DecisionReason.DELEGATION_REQUIRED // A generic request has no recipient/self-change context.
    } else evaluate(actor!!, request, now, reauthentication, emptySet()))

    /** Granting access requires a separate control permission and never exceeds the actor's own grant. */
    fun delegate(
        actor: AdministrativeActor?, recipient: AdministrativeActor, grant: PermissionGrant,
        controlPermission: PermissionKey?, now: Instant, reauthentication: ReauthenticationEvidence? = null,
    ): AccessDecision {
        boundary(actor)?.let { return decision(it) }
        actor!!
        if (recipient.realm != AccessRealm.ADMINISTRATION || recipient.account.entityType != CanonicalEntityType.ADMIN_ACCOUNT) {
            return decision(DecisionReason.INVALID_RECIPIENT)
        }
        if (actor.person?.entityType != CanonicalEntityType.PERSON || recipient.person?.entityType != CanonicalEntityType.PERSON) {
            return decision(DecisionReason.UNRESOLVED_DELEGATION_IDENTITY)
        }
        if (recipient.account == actor.account || actor.person != null &&
            actor.person.entityType == CanonicalEntityType.PERSON && actor.person == recipient.person) {
            return decision(DecisionReason.SELF_PERMISSION_CHANGE)
        }
        if (catalog[controlPermission]?.kind != PermissionKind.ACCESS_ADMINISTRATION) return decision(DecisionReason.DELEGATION_REQUIRED)
        val control = evaluate(actor, AccessRequest(controlPermission, grant.scope.withFields(emptySet())), now, reauthentication, emptySet())
        if (control != DecisionReason.ALLOWED) return decision(control)
        if (catalog[grant.permission]?.kind == PermissionKind.CREDENTIAL_EXPORT && !recipient.hqAccount && !recipient.primaryHqOwner) {
            return decision(DecisionReason.HQ_REQUIRED)
        }
        // Checking authority to delegate a review grant does not perform/approve a specific report.
        return decision(evaluate(actor, AccessRequest(grant.permission, grant.scope), now, reauthentication, emptySet(), reviewOperation = false))
    }

    private fun boundary(actor: AdministrativeActor?): DecisionReason? = when {
        actor == null || actor.realm != AccessRealm.ADMINISTRATION -> DecisionReason.ADMINISTRATION_REQUIRED
        !actor.active -> DecisionReason.INACTIVE_ACCOUNT
        actor.account.entityType != CanonicalEntityType.ADMIN_ACCOUNT -> DecisionReason.INVALID_ACCOUNT_IDENTITY
        else -> null
    }

    private fun evaluate(
        actor: AdministrativeActor, request: AccessRequest, now: Instant, proof: ReauthenticationEvidence?,
        visiting: Set<PermissionKey>, reviewOperation: Boolean = true,
    ): DecisionReason {
        val definition = catalog[request.permission] ?: return DecisionReason.UNKNOWN_PERMISSION
        if (definition.key in visiting) return DecisionReason.INVALID_CATALOG
        if (!request.scope.valid()) return DecisionReason.EMPTY_SCOPE
        if (!definition.fields.containsAll(request.scope.fields)) return DecisionReason.UNKNOWN_FIELD
        if (definition.independentReview && reviewOperation) {
            val executor = request.review?.executorPerson
            if (actor.person?.entityType != CanonicalEntityType.PERSON || executor?.entityType != CanonicalEntityType.PERSON) {
                return DecisionReason.UNRESOLVED_REVIEW_IDENTITY
            }
            if (actor.person == executor) return DecisionReason.SELF_REVIEW
            val owners = request.scope.owners
            if (owners is OwnerScope.Persons && !owners.ids.contains(executor)) return DecisionReason.REVIEW_SCOPE_MISMATCH
        }
        if (definition.kind == PermissionKind.CREDENTIAL_EXPORT && !actor.hqAccount && !actor.primaryHqOwner) return DecisionReason.HQ_REQUIRED
        if (definition.requiresPasswordReauthentication || definition.kind == PermissionKind.CREDENTIAL_EXPORT) {
            if (proof == null || proof.account != actor.account || proof.method != ReauthenticationMethod.PASSWORD ||
                proof.verifiedAt.isAfter(now) || Duration.between(proof.verifiedAt, now) > reauthenticationMaxAge) {
                return DecisionReason.PASSWORD_REAUTHENTICATION_REQUIRED
            }
        }
        if (!(actor.primaryHqOwner && definition.kind == PermissionKind.ORDINARY) && actor.grants.none { grant ->
                grant.permission == definition.key && definition.fields.containsAll(grant.scope.fields) && grant.scope.covers(request.scope)
            }) return DecisionReason.MISSING_GRANT
        val visited = visiting + definition.key
        definition.viewPermission?.let { view ->
            // D9 is a separate credential report; its row-view prerequisite never adds passwords to ordinary views.
            val fields = if (definition.kind == PermissionKind.CREDENTIAL_EXPORT) emptySet() else request.scope.fields
            if (evaluate(actor, AccessRequest(view, request.scope.withFields(fields)), now, proof, visited, reviewOperation = false) != DecisionReason.ALLOWED) {
                return DecisionReason.PREREQUISITE_DENIED
            }
        }
        for (field in request.scope.fields) {
            val permission = definition.fieldPermissions[field] ?: continue
            if (evaluate(actor, AccessRequest(permission, request.scope.withFields(setOf(field))), now, proof, visited, reviewOperation = false) != DecisionReason.ALLOWED) {
                return DecisionReason.PREREQUISITE_DENIED
            }
        }
        return DecisionReason.ALLOWED
    }

    private fun decision(reason: DecisionReason) = AccessDecision(reason == DecisionReason.ALLOWED, reason)
}
