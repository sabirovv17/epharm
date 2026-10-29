package kz.epharm.shared.identity

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SourceIdentityTest {
    private val pharmacy = CanonicalIdentity(CanonicalEntityType.PHARMACY, "pharmacy-Synthetic-001")

    private fun branch(id: String) = SourceIdentity(SourceSystem.MERCH_CRM, SourceEntityType.BRANCH, id)

    private fun binding(id: String, target: CanonicalIdentity = pharmacy) =
        VerifiedCrosswalkBinding(branch(id), target, "synthetic-crosswalk-review")

    @Test
    fun `existing epharm identifiers and types are retained exactly`() {
        val resolver = SourceIdentityResolver()
        val ids = mapOf(
            SourceEntityType.PHARMACY to "pharmacy-Synthetic-001",
            SourceEntityType.PHARMACIST to "0007-Synthetic",
            SourceEntityType.ADMIN_ACCOUNT to UUID.fromString("11111111-2222-4333-8444-555555555555").toString(),
            SourceEntityType.CUSTOMER to "customer-Synthetic-001",
        )
        for ((type, id) in ids) {
            assertEquals(
                CanonicalIdentity(CanonicalEntityType.valueOf(type.name), id),
                resolver.resolve(SourceIdentity(SourceSystem.EPHARM, type, id)),
            )
        }
    }

    @Test
    fun `source system entity and case form independent namespaces`() {
        val id = "shared-Synthetic-001"
        val epharm = SourceIdentity(SourceSystem.EPHARM, SourceEntityType.PHARMACY, id)
        val crm = SourceIdentity(SourceSystem.MERCH_CRM, SourceEntityType.PHARMACY, id)
        val pharmacist = SourceIdentity(SourceSystem.EPHARM, SourceEntityType.PHARMACIST, id)
        val customer = SourceIdentity(SourceSystem.EPHARM, SourceEntityType.CUSTOMER, id)
        assertEquals(4, setOf(epharm, crm, pharmacist, customer).size)
        assertNotEquals(SourceIdentityResolver().resolve(pharmacist), SourceIdentityResolver().resolve(customer))
        assertNotEquals(epharm, epharm.copy(originalId = id.lowercase()))
    }

    @Test
    fun `unknown crm identifiers remain unresolved even when their string matches epharm`() {
        val resolver = SourceIdentityResolver()
        assertNull(resolver.resolve(branch(pharmacy.id)))
        assertNull(resolver.resolve(SourceIdentity(SourceSystem.MERCH_CRM, SourceEntityType.PHARMACY, pharmacy.id)))
        assertEquals(pharmacy, resolver.resolve(SourceIdentity(SourceSystem.EPHARM, SourceEntityType.PHARMACY, pharmacy.id)))
    }

    @Test
    fun `only an exact verified branch crosswalk resolves a crm branch`() {
        val resolver = SourceIdentityResolver(listOf(binding("Branch-Synthetic-001")))
        assertEquals(pharmacy, resolver.resolve(branch("Branch-Synthetic-001")))
        assertNull(resolver.resolve(branch("branch-synthetic-001")))
        assertNull(resolver.resolve(branch("Branch-Synthetic-002")))
        assertNull(resolver.resolve(SourceIdentity(SourceSystem.MERCH_CRM, SourceEntityType.ACCOUNT, "Branch-Synthetic-001")))
    }

    @Test
    fun `opaque ids are not trimmed or interpreted as numbers or UUIDs`() {
        val resolver = SourceIdentityResolver(listOf(binding(" 0007-Synthetic ")))
        assertEquals(pharmacy, resolver.resolve(branch(" 0007-Synthetic ")))
        assertNull(resolver.resolve(branch("0007-Synthetic")))
        for (id in listOf("0007", "person_Synthetic", "11111111-2222-4333-8444-555555555555")) {
            for (system in SourceSystem.entries) {
                for (type in listOf(SourceEntityType.ACCOUNT, SourceEntityType.PERSON)) {
                    assertNull(resolver.resolve(SourceIdentity(system, type, id)))
                }
            }
        }
    }

    @Test
    fun `blank ids and missing crosswalk provenance are rejected`() {
        for (id in listOf("", " ", "\t\n")) {
            assertThrows(IllegalArgumentException::class.java) { branch(id) }
            assertThrows(IllegalArgumentException::class.java) { CanonicalIdentity(CanonicalEntityType.PERSON, id) }
            assertThrows(IllegalArgumentException::class.java) { VerifiedCrosswalkBinding(branch("synthetic"), pharmacy, id) }
        }
    }

    @Test
    fun `unsupported entity or source relationship cannot become a binding`() {
        for (type in SourceEntityType.entries.filter { it != SourceEntityType.BRANCH }) {
            assertThrows(IllegalArgumentException::class.java) {
                VerifiedCrosswalkBinding(SourceIdentity(SourceSystem.MERCH_CRM, type, "synthetic"), pharmacy, "review")
            }
        }
        for (type in CanonicalEntityType.entries.filter { it != CanonicalEntityType.PHARMACY }) {
            assertThrows(IllegalArgumentException::class.java) {
                VerifiedCrosswalkBinding(branch("synthetic"), CanonicalIdentity(type, "synthetic"), "review")
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            VerifiedCrosswalkBinding(SourceIdentity(SourceSystem.EPHARM, SourceEntityType.BRANCH, "synthetic"), pharmacy, "review")
        }
    }

    @Test
    fun `same source cannot alias two canonical pharmacies`() {
        assertThrows(IllegalArgumentException::class.java) {
            SourceIdentityResolver(listOf(binding("synthetic"), binding("synthetic", pharmacy.copy(id = "another-Synthetic"))))
        }
    }

    @Test
    fun `two crm branches cannot silently collapse to one canonical pharmacy`() {
        assertThrows(IllegalArgumentException::class.java) {
            SourceIdentityResolver(listOf(binding("synthetic-A"), binding("synthetic-B")))
        }
    }

    @Test
    fun `repeated identical binding is idempotent and resolver snapshots its input`() {
        val bindings = mutableListOf(binding("synthetic"), binding("synthetic"))
        val resolver = SourceIdentityResolver(bindings)
        bindings.clear()
        assertEquals(pharmacy, resolver.resolve(branch("synthetic")))
    }

    @Test
    fun `account person pharmacist and customer canonical identities never imply each other`() {
        val ids = listOf(CanonicalEntityType.ADMIN_ACCOUNT, CanonicalEntityType.ACCOUNT,
            CanonicalEntityType.PERSON, CanonicalEntityType.PHARMACIST, CanonicalEntityType.CUSTOMER)
            .map { CanonicalIdentity(it, "synthetic-same-opaque-id") }
        assertEquals(5, ids.toSet().size)
    }
}
