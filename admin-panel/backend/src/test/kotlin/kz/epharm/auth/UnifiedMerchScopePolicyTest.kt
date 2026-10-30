package kz.epharm.auth

import kz.epharm.auth.access.StoredGrant
import kz.epharm.auth.access.UnifiedAdminCatalog
import kz.epharm.auth.policy.*
import kz.epharm.shared.identity.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

class UnifiedMerchScopePolicyTest {
    private val branch = CanonicalIdentity(CanonicalEntityType.MERCH_BRANCH, UUID.randomUUID().toString())
    private val otherBranch = CanonicalIdentity(CanonicalEntityType.MERCH_BRANCH, UUID.randomUUID().toString())
    private val customer = CanonicalIdentity(CanonicalEntityType.CUSTOMER, UUID.randomUUID().toString())
    private val otherCustomer = CanonicalIdentity(CanonicalEntityType.CUSTOMER, UUID.randomUUID().toString())
    private val account = CanonicalIdentity(CanonicalEntityType.ADMIN_ACCOUNT, UUID.randomUUID().toString())
    private val person = CanonicalIdentity(CanonicalEntityType.PERSON, UUID.randomUUID().toString())
    private val key = PermissionKey(AdministrationModule.MERCHANDISING, "placements", "submit")
    private val definition = PermissionDefinition(key, PermissionKind.FINANCIAL)
    private fun actor(scope: DataScope) = AdministrativeActor(account, AccessRealm.ADMINISTRATION, true, person,
        grants = listOf(PermissionGrant(key, scope)))
    private val policy = UnifiedAccessPolicy(listOf(definition), Duration.ofMinutes(5))

    @Test fun `pharmacy ALL cannot read or mutate the same textual ID in merch branch namespace`() {
        val granted = actor(DataScope(PharmacyScope.All))
        val requested = DataScope(PharmacyScope.MerchBranches(listOf(branch)))
        assertThat(policy.decide(granted, AccessRequest(key, requested), Instant.now()).allowed).isFalse()
    }

    @Test fun `branch and producer intersections both constrain the operation`() {
        val granted = actor(DataScope(PharmacyScope.MerchBranches(listOf(branch)), OwnerScope.Customers(listOf(customer))))
        fun allowed(b: CanonicalIdentity, c: CanonicalIdentity) = policy.decide(granted,
            AccessRequest(key, DataScope(PharmacyScope.MerchBranches(listOf(b)), OwnerScope.Customers(listOf(c)))), Instant.now()).allowed
        assertThat(allowed(branch, customer)).isTrue()
        assertThat(allowed(otherBranch, customer)).isFalse()
        assertThat(allowed(branch, otherCustomer)).isFalse()
        assertThat(allowed(otherBranch, otherCustomer)).isFalse()
    }

    @Test fun `empty branch and producer scopes never mean ALL`() {
        val granted = actor(DataScope(PharmacyScope.AllMerchBranches, OwnerScope.AllCustomers))
        assertThat(policy.decide(granted, AccessRequest(key,
            DataScope(PharmacyScope.MerchBranches(emptyList()), OwnerScope.AllCustomers)), Instant.now()).allowed).isFalse()
        assertThat(policy.decide(granted, AccessRequest(key,
            DataScope(PharmacyScope.AllMerchBranches, OwnerScope.Customers(emptyList()))), Instant.now()).allowed).isFalse()
    }

    @Test fun `persisted grants reject mixed namespaces or missing producer scope`() {
        assertThatThrownBy { StoredGrant("merchandising", "placements", "submit", allPharmacies = true).toGrant() }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { StoredGrant("merchandising", "placements", "submit", allMerchBranches = true).toGrant() }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { StoredGrant("pharmacists", "registry", "view", allPharmacies = true, allMerchBranches = true).toGrant() }
            .isInstanceOf(IllegalArgumentException::class.java)
        val grant = StoredGrant("merchandising", "placements", "submit", allMerchBranches = true,
            allProducers = true).toGrant()
        assertThat(grant.scope.pharmacies).isEqualTo(PharmacyScope.AllMerchBranches)
        assertThat(grant.scope.owners).isEqualTo(OwnerScope.AllCustomers)
    }

    @Test fun `route authorization never substitutes for independent review proof`() {
        val review = PermissionKey(AdministrationModule.MERCHANDISING, "tasks", "review")
        val engine = UnifiedAccessPolicy(listOf(PermissionDefinition(review, independentReview = true)), Duration.ofMinutes(5))
        val scope = DataScope(PharmacyScope.AllMerchBranches)
        val reviewer = AdministrativeActor(account, AccessRealm.ADMINISTRATION, true, person,
            grants = listOf(PermissionGrant(review, scope)))
        assertThat(engine.authorizeScope(reviewer, AccessRequest(review, scope), Instant.now()).allowed).isTrue()
        assertThat(engine.decide(reviewer, AccessRequest(review, scope), Instant.now()).allowed).isFalse()
        assertThat(engine.decide(reviewer, AccessRequest(review, scope, ReviewContext(person)), Instant.now()).reason)
            .isEqualTo(DecisionReason.SELF_REVIEW)
    }

    @Test fun `global explicit HQ control delegates only owned branch grants across typed namespaces`() {
        val control = PermissionKey(AdministrationModule.MANAGEMENT, "accounts", "manage")
        val engine = UnifiedAccessPolicy(listOf(definition, PermissionDefinition(control, PermissionKind.ACCESS_ADMINISTRATION,
            requiresPasswordReauthentication = true)), Duration.ofMinutes(5))
        val scope = DataScope(PharmacyScope.MerchBranches(listOf(branch)), OwnerScope.Customers(listOf(customer)))
        val issuer = AdministrativeActor(account, AccessRealm.ADMINISTRATION, true, person, hqAccount = true,
            grants = listOf(PermissionGrant(control, DataScope(PharmacyScope.All)), PermissionGrant(key, scope)))
        val recipient = AdministrativeActor(CanonicalIdentity(CanonicalEntityType.ADMIN_ACCOUNT, UUID.randomUUID().toString()),
            AccessRealm.ADMINISTRATION, true, CanonicalIdentity(CanonicalEntityType.PERSON, UUID.randomUUID().toString()))
        val proof = ReauthenticationEvidence(account, ReauthenticationMethod.PASSWORD, Instant.now())
        assertThat(engine.delegate(issuer, recipient, PermissionGrant(key, scope), control, Instant.now(), proof).allowed).isTrue()
        assertThat(engine.delegate(issuer, recipient, PermissionGrant(key,
            DataScope(PharmacyScope.AllMerchBranches, OwnerScope.AllCustomers)), control, Instant.now(), proof).allowed).isFalse()
    }
    @Test fun `ALL_ONLY uses the full typed branch and customer domain`() {
        val catalog = UnifiedAdminCatalog(com.fasterxml.jackson.module.kotlin.jacksonObjectMapper())
        val global = catalog.placementBindProducer
        assertThat(catalog.isAllScope(global, DataScope(PharmacyScope.All))).isFalse()
        assertThat(catalog.isAllScope(global, DataScope(PharmacyScope.AllMerchBranches))).isFalse()
        assertThat(catalog.isAllScope(global, DataScope(PharmacyScope.AllMerchBranches, OwnerScope.Customers(listOf(customer))))).isFalse()
        assertThat(catalog.isAllScope(global, DataScope(PharmacyScope.MerchBranches(listOf(branch)), OwnerScope.AllCustomers))).isFalse()
        assertThat(catalog.isAllScope(global, DataScope(PharmacyScope.AllMerchBranches, OwnerScope.AllCustomers))).isTrue()
    }

}
