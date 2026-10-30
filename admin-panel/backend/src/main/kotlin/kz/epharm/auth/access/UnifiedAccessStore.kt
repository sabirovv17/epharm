package kz.epharm.auth.access

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.module.kotlin.readValue
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.domain.AdminUserStatus
import kz.epharm.auth.policy.*
import kz.epharm.auth.security.AdminPrincipal
import kz.epharm.shared.identity.*
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID

data class StoredGrant(
    val module: String, val subsection: String, val action: String,
    val allPharmacies: Boolean = false, val pharmacyIds: Set<String> = emptySet(),
    val allMerchBranches: Boolean = false, val merchBranchIds: Set<String> = emptySet(),
    val allProducers: Boolean = false, val producerIds: Set<String> = emptySet(),
) {
    fun toGrant(): PermissionGrant {
        val key = requireNotNull(PermissionKey.parse(module, subsection, action))
        if (module == AdministrationModule.MERCHANDISING.identifier) {
            require(!allPharmacies && pharmacyIds.isEmpty()) { "Merch branches are not pharmacy identities" }
            require(!allMerchBranches || merchBranchIds.isEmpty()) { "Choose explicit ALL or assigned branches" }
            require(allMerchBranches || merchBranchIds.isNotEmpty()) { "Empty scope is never ALL" }
            require(merchBranchIds.all { UUID.fromString(it).toString() == it }) { "Invalid branch UUID" }
            val ownerScope = if (subsection in setOf("placements", "producers")) {
                require(!allProducers || producerIds.isEmpty()) { "Choose explicit ALL or assigned producers" }
                require(allProducers || producerIds.isNotEmpty()) { "Empty producer scope is never ALL" }
                require(producerIds.all { UUID.fromString(it).toString() == it }) { "Invalid producer UUID" }
                if (allProducers) OwnerScope.AllCustomers else OwnerScope.Customers(producerIds.map {
                    CanonicalIdentity(CanonicalEntityType.CUSTOMER, it) })
            } else {
                require(!allProducers && producerIds.isEmpty()) { "This permission has no producer dimension" }
                OwnerScope.All
            }
            return PermissionGrant(key, DataScope(if (allMerchBranches) PharmacyScope.AllMerchBranches
                else PharmacyScope.MerchBranches(merchBranchIds.map { CanonicalIdentity(CanonicalEntityType.MERCH_BRANCH, it) }), ownerScope))
        }
        require(!allMerchBranches && merchBranchIds.isEmpty() && !allProducers && producerIds.isEmpty()) { "Branch/producer scope cannot authorize pharmacy operations" }
        require(!allPharmacies || pharmacyIds.isEmpty()) { "Choose explicit ALL or assigned pharmacies" }
        require(allPharmacies || pharmacyIds.isNotEmpty()) { "Empty scope is never ALL" }
        require(pharmacyIds.all { it.isNotBlank() && it.length <= 64 })
        return PermissionGrant(key, DataScope(
            if (allPharmacies) PharmacyScope.All else PharmacyScope.Assigned(
                pharmacyIds.map { CanonicalIdentity(CanonicalEntityType.PHARMACY, it) }),
        ))
    }
}

data class AccessProfile(
    val accountId: UUID, val personId: UUID, val jobTitle: String, val version: Long,
    val grants: List<StoredGrant>,
)
data class LoadedAccess(val managed: Boolean, val profile: AccessProfile?, val actor: AdministrativeActor, val role: AdminRole)

@Service
class UnifiedAccessStore(
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
    @Value("\${app.unified-admin.primary-owner-id:}") private val primaryOwnerId: String,
) {
    fun isDesignatedOwner(id: UUID) = primaryOwnerId == id.toString()
    @Transactional(readOnly = true)
    fun load(principal: AdminPrincipal): LoadedAccess? = loadAccount(principal.userId)

    @Transactional(readOnly = true)
    fun loadAccount(accountId: UUID): LoadedAccess? {
        // JDBC deliberately avoids an OSIV/JPA first-level cache containing pre-lock JWT lookup facts.
        val facts = jdbc.query("select status,role,access_mode from admin_users where id=?",
            { rs, _ -> Triple(rs.getString(1), rs.getString(2), rs.getString(3)) }, accountId).singleOrNull() ?: return null
        if (facts.first != AdminUserStatus.ACTIVE.toDbValue()) return null
        val role = AdminRole.entries.singleOrNull { it.name == facts.second } ?: return null
        val profile = try { profile(accountId) } catch (_: JsonProcessingException) { return null }
            catch (_: IllegalArgumentException) { return null }
        val grants = try { profile?.grants?.map { it.toGrant() }.orEmpty() }
            catch (_: IllegalArgumentException) { return null }
        val owner = isDesignatedOwner(accountId) && role in setOf(AdminRole.HQ_HEAD, AdminRole.SYSTEM_ADMIN)
        val actor = AdministrativeActor(
            CanonicalIdentity(CanonicalEntityType.ADMIN_ACCOUNT, accountId.toString()), AccessRealm.ADMINISTRATION, true,
            profile?.let { CanonicalIdentity(CanonicalEntityType.PERSON, it.personId.toString()) },
            primaryHqOwner = owner, hqAccount = role in setOf(AdminRole.HQ_HEAD, AdminRole.SYSTEM_ADMIN),
            jobTitle = profile?.jobTitle, grants = grants,
        )
        // MANAGED with a missing profile remains managed and empty-deny, never legacy fallback.
        return LoadedAccess(facts.third == "MANAGED", profile, actor, role)
    }

    /** All writers acquire account rows, then profiles, in UUID order to serialize revocation/delegation. */
    fun lockAccounts(ids: Set<UUID>) {
        UnifiedAuthorityLock.acquire(jdbc)
        check(TransactionSynchronizationManager.isActualTransactionActive()) { "Account locks require an active write transaction" }
        require(ids.isNotEmpty() && ids.size <= 2)
        val placeholders = ids.joinToString(",") { "?" }
        val args = ids.toTypedArray()
        jdbc.queryForList("select id from admin_users where id in ($placeholders) order by id for no key update", *args)
        jdbc.queryForList("select account_id from unified_admin_profiles where account_id in ($placeholders) order by account_id for update", *args)
    }

    fun profile(id: UUID): AccessProfile? = jdbc.query(
        "select account_id,person_id,job_title,version,grants_json from unified_admin_profiles where account_id = ?",
        { rs, _ -> AccessProfile(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java),
            rs.getString(3), rs.getLong(4), mapper.readValue<List<StoredGrant>>(rs.getString(5))) }, id,
    ).singleOrNull()

    @Transactional
    fun replace(actor: UUID, target: UUID, expectedVersion: Long, grants: List<StoredGrant>): Boolean {
        val updated = jdbc.update("""update unified_admin_profiles set grants_json=?,version=version+1,updated_at=now()
            where account_id=? and version=?""", mapper.writeValueAsString(grants), target, expectedVersion)
        if (updated != 1) return false
        audit(actor, target, "REPLACE_GRANTS", expectedVersion + 1)
        return true
    }

    fun audit(actor: UUID, target: UUID, operation: String, version: Long) {
        jdbc.update("insert into unified_admin_access_audit(id,actor_id,target_id,operation,target_version) values(?,?,?,?,?)",
            UUID.randomUUID(), actor, target, operation, version)
    }
}
