package kz.epharm.pharmacists

import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kz.epharm.auth.access.AdministrativeScope
import kz.epharm.finance.service.PharmacistWalletService
import kz.epharm.pharmacies.dto.CreatePharmacyRequest
import kz.epharm.pharmacies.dto.PharmacyDto
import kz.epharm.pharmacies.dto.UpdatePharmacyRequest
import kz.epharm.pharmacies.entity.ChainEntity
import kz.epharm.pharmacies.entity.PharmacyEntity
import kz.epharm.pharmacies.entity.PharmacyGroup
import kz.epharm.pharmacies.repository.ChainRepository
import kz.epharm.pharmacies.repository.PharmacyRepository
import kz.epharm.pharmacies.service.PharmacyService
import kz.epharm.pharmacists.dto.ActivatePharmacistRequest
import kz.epharm.pharmacists.dto.CreatePharmacistRequest
import kz.epharm.pharmacists.dto.PharmacistDto
import kz.epharm.pharmacists.dto.UpdatePharmacistRequest
import kz.epharm.pharmacists.entity.PharmacistEntity
import kz.epharm.pharmacists.entity.PharmacistStatus
import kz.epharm.pharmacists.repository.PharmacistRepository
import kz.epharm.pharmacists.service.PharmacistService
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.password.PasswordEncoder
import java.util.Optional

class AdministrativeRosterScopeTest {
    private val pharmacists = mockk<PharmacistRepository>()
    private val pharmacies = mockk<PharmacyRepository>()
    private val chains = mockk<ChainRepository>()
    private val encoder = mockk<PasswordEncoder>()
    private val wallet = mockk<PharmacistWalletService>()
    private val scope = mockk<AdministrativeScope>()
    private val staffService = PharmacistService(pharmacists, pharmacies, encoder, wallet, scope)
    private val pharmacyService = PharmacyService(pharmacies, chains, pharmacists, scope)
    private val area = setOf("pharmacy-synthetic-one")

    init {
        every { scope.currentPharmacyIds() } returns area
        every { scope.pharmacist(any()) } answers { firstArg<PharmacistDto>() }
        every { scope.pharmacy(any()) } answers { firstArg<PharmacyDto>() }
        every { scope.requirePharmacistUpdate(any()) } returns Unit
        every { scope.requirePharmacyUpdate(any()) } returns Unit
    }

    @Test
    fun `status and client pharmacy filters remain ANDed with authoritative scope`() {
        every { pharmacists.findForAdministration(area, "active", "foreign-pharmacy") } returns emptyList()
        every { pharmacies.findForAdministration(area, "pilot", "foreign-chain") } returns emptyList()

        assertThat(staffService.list(PharmacistStatus.active, "foreign-pharmacy")).isEmpty()
        assertThat(pharmacyService.list(PharmacyGroup.pilot, "foreign-chain")).isEmpty()
        verify(exactly = 1) { pharmacists.findForAdministration(area, "active", "foreign-pharmacy") }
        verify(exactly = 1) { pharmacies.findForAdministration(area, "pilot", "foreign-chain") }
        verify(exactly = 0) { pharmacists.findAllByOrderByNameAsc() }
        verify(exactly = 0) { pharmacies.findAllByOrderByNameAsc() }
    }

    @Test
    fun `empty scope returns no rows and never falls back to unrestricted repository access`() {
        every { scope.currentPharmacyIds() } returns emptySet()
        assertThat(staffService.list()).isEmpty()
        assertThat(pharmacyService.list()).isEmpty()
        assertThat(assertThrows(AppException::class.java) { staffService.get("unknown") }.status).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(assertThrows(AppException::class.java) { pharmacyService.get("unknown") }.status).isEqualTo(HttpStatus.NOT_FOUND)
        assertThrows(AppException::class.java) { staffService.block("unknown") }
        assertThrows(AppException::class.java) { pharmacyService.delete("unknown") }
        verify { listOf(pharmacists, pharmacies, chains, wallet) wasNot Called }
    }

    @Test
    fun `legacy or internal null scope preserves access and fixes combined filter intersection`() {
        every { scope.currentPharmacyIds() } returns null
        every { pharmacists.findAllByStatusRawAndPharmacyIdOrderByNameAsc("pending", "legacy-pharmacy") } returns emptyList()
        every { pharmacies.findAllByGroupRawAndChainIdOrderByNameAsc("control", "legacy-chain") } returns emptyList()
        val row = person("legacy-person")
        every { pharmacists.findById(row.id) } returns Optional.of(row)

        assertThat(staffService.list(PharmacistStatus.pending, "legacy-pharmacy")).isEmpty()
        assertThat(pharmacyService.list(PharmacyGroup.control, "legacy-chain")).isEmpty()
        assertThat(staffService.get(row.id).id).isEqualTo(row.id)
        verify(exactly = 0) { pharmacists.findForAdministration(any(), any(), any()) }
        verify(exactly = 0) { pharmacies.findForAdministration(any(), any(), any()) }
    }

    @Test
    fun `direct foreign IDs and all status mutations use bounded SQL loads`() {
        every { pharmacists.findInAdministrativeScope("foreign", area) } returns Optional.empty()
        every { pharmacists.findInAdministrativeScopeForUpdate("foreign", area) } returns Optional.empty()
        every { pharmacies.findInAdministrativeScope("foreign", area) } returns Optional.empty()
        every { pharmacies.findInAdministrativeScopeForUpdate("foreign", area) } returns Optional.empty()

        val denied = listOf<() -> Any>(
            { staffService.get("foreign") }, { staffService.update("foreign", UpdatePharmacistRequest(name = "Synthetic")) },
            { staffService.block("foreign") }, { staffService.unblock("foreign") },
            { staffService.activate("foreign", ActivatePharmacistRequest(area.first())) },
            { pharmacyService.get("foreign") }, { pharmacyService.update("foreign", UpdatePharmacyRequest(name = "Synthetic")) },
            { pharmacyService.delete("foreign") },
        )
        denied.forEach { call ->
            assertThat(assertThrows(AppException::class.java) { call() }.status).isEqualTo(HttpStatus.NOT_FOUND)
        }
        verify(exactly = 0) { pharmacists.findById(any()) }
        verify(exactly = 0) { pharmacies.findById(any()) }
        verify(exactly = 0) { pharmacists.save(any()) }
        verify(exactly = 0) { pharmacies.save(any()) }
        verify(exactly = 0) { pharmacists.countByPharmacyId(any()) }
    }

    @Test
    fun `activation requires source and target pharmacy before changing employee binding`() {
        val pending = person("pending-person")
        every { pharmacists.findInAdministrativeScopeForUpdate(pending.id, area) } returns Optional.of(pending)
        every { pharmacies.findInAdministrativeScope("foreign", area) } returns Optional.empty()

        assertThrows(AppException::class.java) { staffService.activate(pending.id, ActivatePharmacistRequest("foreign")) }
        assertThat(pending.pharmacyId).isEqualTo(area.first())
        assertThat(pending.status).isEqualTo(PharmacistStatus.pending)
        verify(exactly = 0) { pharmacists.save(any()) }
        verify(exactly = 0) { pharmacies.findById(any()) }
    }

    @Test
    fun `activation within full authorized area uses locked source and scoped target`() {
        val allowed = area + "pharmacy-synthetic-two"
        val pending = person("pending-person")
        val target = PharmacyEntity(id = "pharmacy-synthetic-two", name = "Synthetic target", city = "Synthetic city")
        every { scope.currentPharmacyIds() } returns allowed
        every { pharmacists.findInAdministrativeScopeForUpdate(pending.id, allowed) } returns Optional.of(pending)
        every { pharmacies.findInAdministrativeScope(target.id, allowed) } returns Optional.of(target)
        every { pharmacists.save(pending) } returns pending

        assertThat(staffService.activate(pending.id, ActivatePharmacistRequest(target.id)).pharmacyId).isEqualTo(target.id)
        assertThat(pending.status).isEqualTo(PharmacistStatus.active)
        verify(exactly = 1) { pharmacists.findInAdministrativeScopeForUpdate(pending.id, allowed) }
    }

    @Test
    fun `new employee target is checked before any global identity lookup or write`() {
        every { pharmacies.findInAdministrativeScope("foreign", area) } returns Optional.empty()
        val request = CreatePharmacistRequest("Synthetic", "000000000001", "+70000000001", "synthetic-only", "foreign")
        assertThrows(AppException::class.java) { staffService.create(request) }
        verify { listOf(pharmacists, encoder, wallet) wasNot Called }
    }

    @Test
    fun `projection applies to list get and mutation responses`() {
        val row = person("synthetic")
        every { scope.pharmacist(any()) } answers { firstArg<PharmacistDto>().copy(iin = null, phone = null, balance = null, earned30d = null) }
        every { pharmacists.findForAdministration(area, null, null) } returns listOf(row)
        every { pharmacists.findInAdministrativeScope(row.id, area) } returns Optional.of(row)
        every { pharmacists.findInAdministrativeScopeForUpdate(row.id, area) } returns Optional.of(row)
        every { pharmacists.save(row) } returns row
        val replies = listOf(staffService.list().single(), staffService.get(row.id), staffService.block(row.id))
        replies.forEach { reply ->
            assertThat(reply.iin).isNull()
            assertThat(reply.phone).isNull()
            assertThat(reply.balance).isNull()
            assertThat(reply.earned30d).isNull()
        }
    }

    @Test
    fun `pharmacy financial projection applies to list get update and create responses`() {
        val row = PharmacyEntity(id = area.first(), name = "Synthetic pharmacy", gmv30d = 50)
        every { scope.pharmacy(any()) } answers { firstArg<PharmacyDto>().copy(gmv30d = null) }
        every { pharmacies.findForAdministration(area, null, null) } returns listOf(row)
        every { pharmacies.findInAdministrativeScope(row.id, area) } returns Optional.of(row)
        every { pharmacies.findInAdministrativeScopeForUpdate(row.id, area) } returns Optional.of(row)
        every { pharmacies.save(any()) } answers { firstArg<PharmacyEntity>() }
        val replies = listOf(pharmacyService.list().single(), pharmacyService.get(row.id),
            pharmacyService.update(row.id, UpdatePharmacyRequest(name = "Synthetic update")))
        replies.forEach { assertThat(it.gmv30d).isNull() }
        every { scope.currentPharmacyIds() } returns null
        every { chains.findById("synthetic-chain") } returns Optional.of(ChainEntity(id = "synthetic-chain"))
        val created = pharmacyService.create(CreatePharmacyRequest(
            name = "Synthetic created", chainId = "synthetic-chain", city = "Synthetic", group = PharmacyGroup.pilot,
        ))
        assertThat(created.gmv30d).isNull()
        verify(exactly = 4) { scope.pharmacy(any()) }
    }

    @Test
    fun `field hooks reject sensitive patches before SQL or ledger changes`() {
        val denied = AppException(ErrorCode.FORBIDDEN, "Denied", HttpStatus.FORBIDDEN)
        every { scope.requirePharmacistUpdate(any()) } throws denied
        every { scope.requirePharmacyUpdate(any()) } throws denied
        assertThrows(AppException::class.java) { staffService.update("synthetic", UpdatePharmacistRequest(balance = 10)) }
        assertThrows(AppException::class.java) { pharmacyService.update(area.first(), UpdatePharmacyRequest(gmv30d = 10)) }
        verify { listOf(pharmacists, pharmacies, wallet) wasNot Called }
    }

    @Test
    fun `creating a new pharmacy cannot silently expand a selected-pharmacy grant`() {
        val req = CreatePharmacyRequest(name = "Synthetic", chainId = "synthetic-chain", city = "Synthetic", district = "Synthetic", addr = "Synthetic, 1", group = PharmacyGroup.pilot)
        assertThat(assertThrows(AppException::class.java) { pharmacyService.create(req) }.status).isEqualTo(HttpStatus.FORBIDDEN)
        verify { listOf(pharmacies, chains, pharmacists) wasNot Called }
    }

    private fun person(id: String) = PharmacistEntity(
        id = id, name = "Synthetic employee", iin = "000000000001", phone = "+70000000001",
        pharmacyId = area.first(), pharmacyName = "Synthetic pharmacy", balance = 50, earned30d = 50,
    )
}
