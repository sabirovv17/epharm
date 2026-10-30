package kz.epharm.pharmacists

import kz.epharm.pharmacies.entity.PharmacyEntity
import kz.epharm.pharmacies.entity.PharmacyGroup
import kz.epharm.pharmacies.repository.PharmacyRepository
import kz.epharm.pharmacists.entity.PharmacistEntity
import kz.epharm.pharmacists.entity.PharmacistStatus
import kz.epharm.pharmacists.repository.PharmacistRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@DataJpaTest(properties = ["spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop"])
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Testcontainers
class AdministrativeRosterRepositoryTest {
    companion object {
        @Container @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("scope_repository_test").withUsername("epharm").withPassword("epharm_test")
            .apply { start() }

        @JvmStatic @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
        }
    }

    @Autowired private lateinit var pharmacies: PharmacyRepository
    @Autowired private lateinit var pharmacists: PharmacistRepository

    @BeforeEach
    fun seed() {
        pharmacies.saveAllAndFlush(listOf(
            pharmacy("scope-one", "chain-one", PharmacyGroup.pilot),
            pharmacy("foreign", "chain-two", PharmacyGroup.pilot),
            pharmacy("scope-control", "chain-one", PharmacyGroup.control),
            pharmacy("scope-other-chain", "chain-two", PharmacyGroup.pilot),
        ))
        pharmacists.saveAllAndFlush(listOf(
            person("allowed-active", "scope-one", PharmacistStatus.active),
            person("allowed-pending", "scope-one", PharmacistStatus.pending),
            person("foreign-active", "foreign", PharmacistStatus.active),
            person("unassigned-pending", null, PharmacistStatus.pending),
        ))
    }

    @Test
    fun `postgres applies status pharmacy and grant restrictions together including null pharmacy`() {
        val area = setOf("scope-one")
        assertThat(pharmacists.findForAdministration(area, "active", "scope-one").map { it.id }).containsExactly("allowed-active")
        assertThat(pharmacists.findForAdministration(area, "active", "foreign")).isEmpty()
        assertThat(pharmacists.findForAdministration(area, "pending", null).map { it.id }).containsExactly("allowed-pending")
        assertThat(pharmacists.findForAdministration(emptySet(), null, null)).isEmpty()
        assertThat(pharmacists.findAllByStatusRawAndPharmacyIdOrderByNameAsc("active", "scope-one").map { it.id }).containsExactly("allowed-active")
    }

    @Test
    fun `postgres applies group chain and grant restrictions as AND conditions`() {
        val area = setOf("scope-one", "scope-control", "scope-other-chain")
        assertThat(pharmacies.findForAdministration(area, "pilot", "chain-one").map { it.id }).containsExactly("scope-one")
        assertThat(pharmacies.findForAdministration(setOf("scope-one"), null, "chain-two")).isEmpty()
        assertThat(pharmacies.findForAdministration(emptySet(), null, null)).isEmpty()
        assertThat(pharmacies.findAllByGroupRawAndChainIdOrderByNameAsc("pilot", "chain-one").map { it.id }).containsExactly("scope-one")
    }

    @Test
    fun `direct ID and locked mutation queries cannot load foreign or unassigned rows`() {
        val area = setOf("scope-one")
        assertThat(pharmacists.findInAdministrativeScope("foreign-active", area)).isEmpty()
        assertThat(pharmacists.findInAdministrativeScopeForUpdate("foreign-active", area)).isEmpty()
        assertThat(pharmacists.findInAdministrativeScopeForUpdate("unassigned-pending", area)).isEmpty()
        assertThat(pharmacies.findInAdministrativeScope("foreign", area)).isEmpty()
        assertThat(pharmacies.findInAdministrativeScopeForUpdate("foreign", area)).isEmpty()
        assertThat(pharmacists.findInAdministrativeScopeForUpdate("allowed-active", area)).isPresent()
        assertThat(pharmacies.findInAdministrativeScopeForUpdate("scope-one", area)).isPresent()
        assertThat(pharmacists.findById("foreign-active").orElseThrow().name).isEqualTo("Synthetic foreign-active")
    }

    private fun pharmacy(id: String, chain: String, group: PharmacyGroup) =
        PharmacyEntity(id = id, name = "Synthetic $id", chainId = chain, chainName = "Synthetic $chain")
            .also { it.group = group }

    private fun person(id: String, pharmacy: String?, status: PharmacistStatus) =
        PharmacistEntity(id = id, name = "Synthetic $id", iin = "000000000001", phone = "+70000000001", pharmacyId = pharmacy)
            .also { it.status = status }
}
