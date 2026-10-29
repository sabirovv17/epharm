package kz.epharm.auth.policy

import kz.epharm.shared.identity.CanonicalEntityType
import kz.epharm.shared.identity.CanonicalIdentity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant

/** Small PLAN-derived fixture catalog only; it does not grant access to any current API. */
class UnifiedAccessPolicyTest {
    private val now = Instant.parse("2026-10-29T09:00:00Z")
    private val alice = CanonicalIdentity(CanonicalEntityType.PERSON, "person-alice")
    private val bob = CanonicalIdentity(CanonicalEntityType.PERSON, "person-bob")
    private val pharmacy1 = CanonicalIdentity(CanonicalEntityType.PHARMACY, "pharmacy-1")
    private val pharmacy2 = CanonicalIdentity(CanonicalEntityType.PHARMACY, "pharmacy-2")
    private val profiles = key(AdministrationModule.PHARMACISTS, "profiles", "view")
    private val phone = key(AdministrationModule.PHARMACISTS, "profile_fields", "read_phone")
    private val iin = key(AdministrationModule.PHARMACISTS, "profile_fields", "read_iin")
    private val export = key(AdministrationModule.PHARMACISTS, "profiles", "export")
    private val credentials = key(AdministrationModule.PHARMACISTS, "credentials", "export")
    private val taskView = key(AdministrationModule.MERCHANDISING, "tasks", "view")
    private val review = key(AdministrationModule.MERCHANDISING, "reports", "review")
    private val courseView = key(AdministrationModule.TRAINING, "programs", "view")
    private val edit = key(AdministrationModule.MOTIVATION, "campaigns", "edit_draft")
    private val apply = key(AdministrationModule.MOTIVATION, "campaigns", "apply")
    private val financial = key(AdministrationModule.PHARMACISTS, "finance", "confirm_payment")
    private val technical = key(AdministrationModule.PHARMACIES_PRODUCTS, "posm", "release")
    private val access = key(AdministrationModule.MANAGEMENT, "accounts", "grant")
    private val definitions = listOf(
        PermissionDefinition(profiles, fields = setOf("name", "phone", "iin"), fieldPermissions = mapOf("phone" to phone, "iin" to iin)),
        PermissionDefinition(phone, PermissionKind.SENSITIVE_DATA, fields = setOf("phone")),
        PermissionDefinition(iin, PermissionKind.SENSITIVE_DATA, fields = setOf("iin")),
        PermissionDefinition(export, PermissionKind.EXPORT, fields = setOf("name", "phone", "iin"), viewPermission = profiles),
        PermissionDefinition(credentials, PermissionKind.CREDENTIAL_EXPORT,
            fields = setOf("login", "password", "phone", "iin"), viewPermission = profiles,
            fieldPermissions = mapOf("phone" to phone, "iin" to iin)),
        PermissionDefinition(taskView),
        PermissionDefinition(review, viewPermission = taskView, independentReview = true),
        PermissionDefinition(courseView), PermissionDefinition(edit), PermissionDefinition(apply),
        PermissionDefinition(financial, PermissionKind.FINANCIAL),
        PermissionDefinition(technical, PermissionKind.POSM_TECHNICAL, requiresPasswordReauthentication = true),
        PermissionDefinition(access, PermissionKind.ACCESS_ADMINISTRATION),
    )
    // A test-only trusted value, not a new production timeout.
    private val policy = UnifiedAccessPolicy(definitions, Duration.ofMinutes(5))

    private fun key(module: AdministrationModule, subsection: String, action: String) = PermissionKey(module, subsection, action)
    private fun scope(
        pharmacy: CanonicalIdentity = pharmacy1, fields: Set<String> = emptySet(), owner: OwnerScope = OwnerScope.All,
    ) = DataScope(PharmacyScope.Assigned(setOf(pharmacy)), owner, fields)
    private fun grant(permission: PermissionKey, scope: DataScope = scope()) = PermissionGrant(permission, scope)
    private fun actor(
        grants: Collection<PermissionGrant> = emptyList(), owner: Boolean = false, hq: Boolean = false,
        realm: AccessRealm = AccessRealm.ADMINISTRATION, active: Boolean = true,
        account: String = "account-alice", person: CanonicalIdentity? = alice, title: String? = null,
    ) = AdministrativeActor(CanonicalIdentity(CanonicalEntityType.ADMIN_ACCOUNT, account), realm, active,
        person, owner, hq, title, grants)
    private fun proof(actor: AdministrativeActor, time: Instant = now, method: ReauthenticationMethod = ReauthenticationMethod.PASSWORD) =
        ReauthenticationEvidence(actor.account, method, time)
    private fun decide(actor: AdministrativeActor?, key: PermissionKey?, scope: DataScope = scope(), review: ReviewContext? = null) =
        policy.decide(actor, AccessRequest(key, scope, review), now)

    @Test
    fun `unknown missing wildcard and case-changed permission identifiers fail closed even for owner`() {
        val owner = actor(owner = true)
        assertThat(decide(owner, null).reason).isEqualTo(DecisionReason.UNKNOWN_PERMISSION)
        assertThat(decide(owner, key(AdministrationModule.MOTIVATION, "campaigns", "unknown_action")).allowed).isFalse()
        for (values in listOf(Triple(null, "profiles", "view"), Triple("Pharmacists", "profiles", "view"),
            Triple("pharmacists", "*", "view"), Triple("pharmacists", "profiles", "view_all.*"))) {
            assertThat(PermissionKey.parse(values.first, values.second, values.third)).isNull()
        }
        assertThat(PermissionKey.parse("pharmacists", "profiles", "view")).isEqualTo(profiles)
    }

    @Test
    fun `learner device producer inactive and generic account never become an administrator`() {
        for (realm in AccessRealm.entries.filter { it != AccessRealm.ADMINISTRATION }) {
            assertThat(decide(actor(owner = true, realm = realm, grants = listOf(grant(edit))), edit).reason)
                .isEqualTo(DecisionReason.ADMINISTRATION_REQUIRED)
        }
        assertThat(decide(null, edit).allowed).isFalse()
        assertThat(decide(actor(owner = true, active = false), edit).reason).isEqualTo(DecisionReason.INACTIVE_ACCOUNT)
        val wrong = AdministrativeActor(CanonicalIdentity(CanonicalEntityType.ACCOUNT, "account-alice"),
            AccessRealm.ADMINISTRATION, true, primaryHqOwner = true)
        assertThat(decide(wrong, edit).reason).isEqualTo(DecisionReason.INVALID_ACCOUNT_IDENTITY)
    }

    @Test
    fun `job title never creates permissions and owner bypass is only known ordinary administration`() {
        assertThat(decide(actor(title = "Главный HQ / МАС / обучение"), edit).reason).isEqualTo(DecisionReason.MISSING_GRANT)
        val owner = actor(owner = true)
        assertThat(decide(owner, edit, DataScope(PharmacyScope.All)).allowed).isTrue()
        assertThat(decide(owner, financial).reason).isEqualTo(DecisionReason.MISSING_GRANT)
        assertThat(decide(owner, profiles, scope(fields = setOf("phone"))).allowed).isFalse()
        assertThat(policy.decide(owner, AccessRequest(technical, scope()), now, proof(owner)).allowed).isFalse()
        assertThat(policy.decide(owner, AccessRequest(credentials, scope(fields = setOf("login", "password"))), now, proof(owner)).allowed).isFalse()
        assertThat(decide(owner, access).reason).isEqualTo(DecisionReason.DELEGATION_REQUIRED)
    }

    @Test
    fun `empty assigned scope never means global and global access must be explicit`() {
        val empty = DataScope(PharmacyScope.Assigned(emptySet()), fields = setOf("name"))
        assertThat(decide(actor(grants = listOf(grant(profiles, empty))), profiles, scope(fields = setOf("name"))).allowed).isFalse()
        assertThat(decide(actor(owner = true), profiles, empty).reason).isEqualTo(DecisionReason.EMPTY_SCOPE)
        val assigned = actor(grants = listOf(grant(profiles, scope(fields = setOf("name")))))
        assertThat(decide(assigned, profiles, DataScope(PharmacyScope.All, fields = setOf("name"))).allowed).isFalse()
        val all = actor(grants = listOf(grant(profiles, DataScope(PharmacyScope.All, fields = setOf("name")))))
        assertThat(decide(all, profiles, DataScope(PharmacyScope.All, fields = setOf("name"))).allowed).isTrue()
    }

    @Test
    fun `module scope cannot be stitched from network training and local merchandising`() {
        val manager = actor(grants = listOf(grant(courseView, DataScope(PharmacyScope.All)), grant(taskView)))
        assertThat(decide(manager, courseView, scope(pharmacy2)).allowed).isTrue()
        assertThat(decide(manager, taskView).allowed).isTrue()
        assertThat(decide(manager, taskView, scope(pharmacy2)).allowed).isFalse()
    }

    @Test
    fun `grant fields owners and pharmacies remain one coherent grant`() {
        val ownerA = OwnerScope.Persons(setOf(alice))
        val ownerB = OwnerScope.Persons(setOf(bob))
        val manager = actor(grants = listOf(
            grant(profiles, scope(fields = setOf("name"), owner = ownerA)),
            grant(profiles, scope(pharmacy2, fields = setOf("name", "phone"), owner = ownerB)),
            grant(phone, DataScope(PharmacyScope.All, fields = setOf("phone"))),
        ))
        assertThat(decide(manager, profiles, scope(fields = setOf("name"), owner = ownerA)).allowed).isTrue()
        assertThat(decide(manager, profiles, scope(fields = setOf("phone"), owner = ownerA)).allowed).isFalse()
        assertThat(decide(manager, profiles, scope(fields = setOf("name"), owner = ownerB)).allowed).isFalse()
        assertThat(decide(manager, profiles, DataScope(PharmacyScope.Assigned(setOf(pharmacy1, pharmacy2)), fields = setOf("name"))).allowed).isFalse()
    }

    @Test
    fun `unknown columns and sensitive fields require their own permission in the same area`() {
        val base = grant(profiles, scope(fields = setOf("name", "phone", "iin")))
        val manager = actor(grants = listOf(base, grant(phone, scope(fields = setOf("phone")))))
        assertThat(decide(manager, profiles, scope(fields = setOf("phone"))).allowed).isTrue()
        assertThat(decide(manager, profiles, scope(fields = setOf("iin"))).allowed).isFalse()
        assertThat(decide(manager, profiles, scope(fields = setOf("password"))).reason).isEqualTo(DecisionReason.UNKNOWN_FIELD)
        assertThat(decide(manager, profiles, scope(pharmacy2, fields = setOf("phone"))).allowed).isFalse()
    }

    @Test
    fun `export does not imply view or broaden view rows owners and fields`() {
        val exp = grant(export, scope(fields = setOf("name", "phone")))
        val telephone = grant(phone, scope(fields = setOf("phone")))
        assertThat(decide(actor(grants = listOf(exp)), export, scope(fields = setOf("name"))).reason)
            .isEqualTo(DecisionReason.PREREQUISITE_DENIED)
        val tooNarrowView = grant(profiles, scope(fields = setOf("name")))
        assertThat(decide(actor(grants = listOf(exp, tooNarrowView, telephone)), export, scope(fields = setOf("phone"))).allowed).isFalse()
        val complete = actor(grants = listOf(exp, telephone, grant(profiles, scope(fields = setOf("name", "phone")))))
        assertThat(decide(complete, export, scope(fields = setOf("name", "phone"))).allowed).isTrue()
        assertThat(decide(complete, export, scope(pharmacy2, fields = setOf("name"))).allowed).isFalse()
        assertThat(decide(actor(grants = listOf(grant(profiles, scope(fields = setOf("name"))))), export,
            scope(fields = setOf("name"))).allowed).isFalse()
    }

    @Test
    fun `export cannot stitch phone view from a different pharmacy or owner`() {
        val manager = actor(grants = listOf(
            grant(export, scope(fields = setOf("phone"))), grant(phone, scope(fields = setOf("phone"))),
            grant(profiles, scope(fields = setOf("name"))),
            grant(profiles, scope(pharmacy2, setOf("phone"))),
            grant(profiles, scope(fields = setOf("phone"), owner = OwnerScope.Persons(setOf(bob)))),
        ))
        assertThat(decide(manager, export, scope(fields = setOf("phone"), owner = OwnerScope.Persons(setOf(alice)))).allowed).isFalse()
    }

    @Test
    fun `canonical person blocks self review across accounts including primary owner`() {
        val request = AccessRequest(review, scope(), ReviewContext(alice))
        val reviewer = actor(account = "different-account-same-person", owner = true)
        assertThat(policy.decide(reviewer, request, now).reason).isEqualTo(DecisionReason.SELF_REVIEW)
        assertThat(decide(actor(owner = true, person = null), review, review = ReviewContext(bob)).reason)
            .isEqualTo(DecisionReason.UNRESOLVED_REVIEW_IDENTITY)
        assertThat(decide(actor(owner = true), review).reason).isEqualTo(DecisionReason.UNRESOLVED_REVIEW_IDENTITY)
    }

    @Test
    fun `task author may review another executor but not a mismatched owner scope`() {
        val reviewer = actor(grants = listOf(grant(taskView), grant(review)))
        assertThat(decide(reviewer, review, review = ReviewContext(bob, reviewer.account)).allowed).isTrue()
        assertThat(decide(reviewer, review, scope(owner = OwnerScope.Persons(setOf(alice))), ReviewContext(bob)).reason)
            .isEqualTo(DecisionReason.REVIEW_SCOPE_MISMATCH)
        assertThat(decide(reviewer, review, review = ReviewContext(CanonicalIdentity(CanonicalEntityType.PHARMACIST, alice.id))).allowed).isFalse()
    }

    @Test
    fun `current pharmacy grant does not relocate old event visibility`() {
        val transferred = actor(grants = listOf(grant(profiles, scope(pharmacy2, setOf("name")))))
        assertThat(decide(transferred, profiles, scope(pharmacy2, setOf("name"))).allowed).isTrue()
        assertThat(decide(transferred, profiles, scope(pharmacy1, setOf("name"))).allowed).isFalse()
    }

    @Test
    fun `credentials need explicit HQ grant fresh password proof and scoped ordinary row view`() {
        val rows = grant(profiles, scope(fields = setOf("name")))
        val credentialGrant = grant(credentials, scope(fields = setOf("login", "password", "phone")))
        val hq = actor(hq = true, grants = listOf(rows, credentialGrant))
        val req = AccessRequest(credentials, scope(fields = setOf("login", "password")))
        assertThat(policy.decide(hq, req, now).reason).isEqualTo(DecisionReason.PASSWORD_REAUTHENTICATION_REQUIRED)
        assertThat(policy.decide(hq, req, now, proof(hq)).allowed).isTrue()
        assertThat(policy.decide(hq, req.copy(scope = scope(pharmacy2, setOf("login", "password"))), now, proof(hq)).allowed).isFalse()
        assertThat(policy.decide(hq, req.copy(scope = scope(fields = setOf("phone"))), now, proof(hq)).allowed).isFalse()
        val notHq = actor(title = "HQ", grants = listOf(rows, credentialGrant))
        assertThat(policy.decide(notHq, req, now, proof(notHq)).reason).isEqualTo(DecisionReason.HQ_REQUIRED)
        val noGrant = actor(owner = true)
        assertThat(policy.decide(noGrant, req, now, proof(noGrant)).reason).isEqualTo(DecisionReason.MISSING_GRANT)
    }

    @Test
    fun `reauthentication belongs to same account uses password and cannot be stale or in the future`() {
        val hq = actor(hq = true, grants = listOf(grant(credentials, scope(fields = setOf("login"))), grant(profiles)))
        val req = AccessRequest(credentials, scope(fields = setOf("login")))
        val invalid = listOf(
            proof(hq, now.plusNanos(1)), proof(hq, now.minusSeconds(301)),
            proof(hq, method = ReauthenticationMethod.SMS), proof(hq, method = ReauthenticationMethod.REFRESH),
            proof(actor(account = "other-account")),
        )
        invalid.forEach { assertThat(policy.decide(hq, req, now, it).allowed).isFalse() }
        assertThat(policy.decide(hq, req, now, proof(hq, now.minusSeconds(300))).allowed).isTrue()
        assertThrows<IllegalArgumentException> { UnifiedAccessPolicy(definitions, Duration.ZERO) }
        assertThrows<IllegalArgumentException> { UnifiedAccessPolicy(definitions, Duration.ofSeconds(-1)) }
    }

    @Test
    fun `fresh proof cannot replace a missing technical grant or its scope`() {
        val owner = actor(owner = true)
        assertThat(policy.decide(owner, AccessRequest(technical, scope()), now, proof(owner)).allowed).isFalse()
        val operator = actor(grants = listOf(grant(technical)))
        assertThat(decide(operator, technical).allowed).isFalse()
        assertThat(policy.decide(operator, AccessRequest(technical, scope()), now, proof(operator)).allowed).isTrue()
        assertThat(policy.decide(operator, AccessRequest(technical, scope(pharmacy2)), now, proof(operator)).allowed).isFalse()
    }

    @Test
    fun `delegation needs separate authority cannot expand permission scope fields or ownership`() {
        val recipient = actor(account = "account-bob", person = bob)
        val allowedView = grant(profiles, scope(fields = setOf("name"), owner = OwnerScope.Persons(setOf(alice))))
        val manager = actor(grants = listOf(grant(access), allowedView))
        assertThat(policy.delegate(manager, recipient, allowedView, access, now).allowed).isTrue()
        val wider = listOf(
            grant(profiles, scope(pharmacy2, setOf("name"))),
            grant(profiles, scope(fields = setOf("phone"))),
            grant(profiles, scope(fields = setOf("name"))), grant(financial),
        )
        wider.forEach { assertThat(policy.delegate(manager, recipient, it, access, now).allowed).isFalse() }
        assertThat(policy.delegate(actor(grants = listOf(allowedView)), recipient, allowedView, access, now).allowed).isFalse()
        assertThat(decide(manager, access).reason).isEqualTo(DecisionReason.DELEGATION_REQUIRED)
    }

    @Test
    fun `self permission changes different account alias unresolved person and nonadmin recipient are denied`() {
        val manager = actor(grants = listOf(grant(access), grant(taskView)))
        assertThat(policy.delegate(manager, manager, grant(taskView), access, now).reason).isEqualTo(DecisionReason.SELF_PERMISSION_CHANGE)
        assertThat(policy.delegate(manager, actor(account = "alias-alice"), grant(taskView), access, now).reason)
            .isEqualTo(DecisionReason.SELF_PERMISSION_CHANGE)
        assertThat(policy.delegate(manager, actor(account = "unresolved", person = null), grant(taskView), access, now).allowed).isFalse()
        assertThat(policy.delegate(manager, actor(account = "producer", person = bob, realm = AccessRealm.PRODUCER),
            grant(taskView), access, now).reason).isEqualTo(DecisionReason.INVALID_RECIPIENT)
    }

    @Test
    fun `delegation refuses unresolved canonical people on either side even for different accounts`() {
        val control = listOf(grant(access), grant(taskView))
        for ((actorPerson, recipientPerson) in listOf(null to null, null to bob, alice to null)) {
            val manager = actor(grants = control, person = actorPerson)
            val recipient = actor(account = "different-account", person = recipientPerson)
            assertThat(policy.delegate(manager, recipient, grant(taskView), access, now).reason)
                .isEqualTo(DecisionReason.UNRESOLVED_DELEGATION_IDENTITY)
        }
    }

    @Test
    fun `delegating review capability does not perform review and credentials cannot be delegated to nonHQ`() {
        val manager = actor(grants = listOf(grant(access), grant(taskView), grant(review)))
        assertThat(policy.delegate(manager, actor(account = "reviewer-bob", person = bob), grant(review), access, now).allowed).isTrue()
        val hq = actor(owner = true, grants = listOf(grant(access), grant(credentials, scope(fields = setOf("login")))))
        assertThat(policy.delegate(hq, actor(account = "not-hq", person = bob), grant(credentials, scope(fields = setOf("login"))),
            access, now, proof(hq)).reason).isEqualTo(DecisionReason.HQ_REQUIRED)
    }

    @Test
    fun `draft edit never implies apply and denied financial operation does not block ordinary reads`() {
        val editor = actor(grants = listOf(grant(edit), grant(profiles, scope(fields = setOf("name")))))
        assertThat(decide(editor, edit).allowed).isTrue()
        assertThat(decide(editor, apply).allowed).isFalse()
        assertThat(decide(editor, financial).allowed).isFalse()
        assertThat(decide(editor, profiles, scope(fields = setOf("name"))).allowed).isTrue()
    }

    @Test
    fun `catalog and grant scope snapshot mutable inputs and cyclic prerequisites fail closed`() {
        val ids = mutableSetOf(pharmacy1)
        val fields = mutableSetOf("name")
        val scope = DataScope(PharmacyScope.Assigned(ids), fields = fields)
        val grants = mutableListOf(grant(profiles, scope))
        val actor = actor(grants = grants)
        val list = definitions.toMutableList()
        val frozenPolicy = UnifiedAccessPolicy(list, Duration.ofMinutes(1))
        ids.add(pharmacy2)
        fields.add("phone")
        grants.add(grant(phone, scope))
        list.clear()
        assertThat(frozenPolicy.decide(actor, AccessRequest(profiles, this.scope(fields = setOf("name"))), now).allowed).isTrue()
        assertThat(frozenPolicy.decide(actor, AccessRequest(profiles, this.scope(pharmacy2, setOf("name"))), now).allowed).isFalse()
        assertThat(frozenPolicy.decide(actor, AccessRequest(profiles, this.scope(fields = setOf("phone"))), now).allowed).isFalse()
        assertThrows<IllegalArgumentException> { UnifiedAccessPolicy(definitions + definitions.first(), Duration.ofMinutes(1)) }
        val first = key(AdministrationModule.ANALYTICS, "cycle", "first")
        val second = key(AdministrationModule.ANALYTICS, "cycle", "second")
        val cyclic = UnifiedAccessPolicy(listOf(PermissionDefinition(first, viewPermission = second),
            PermissionDefinition(second, viewPermission = first)), Duration.ofMinutes(1))
        assertThat(cyclic.decide(actor(owner = true), AccessRequest(first, this.scope()), now).allowed).isFalse()
    }
}
