package kz.epharm.posm

import io.mockk.*
import kz.epharm.pharmacists.repository.PharmacistRepository
import kz.epharm.posm.controller.PosmController
import kz.epharm.posm.dto.*
import kz.epharm.posm.repository.PosmPharmacistMappingRepository
import kz.epharm.posm.service.*
import kz.epharm.posm.staff.SourceStaffBindingService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

class PosSaleSellerSourceTest {
    private val people = mockk<PharmacistRepository>()
    private val mappings = mockk<PosmPharmacistMappingRepository>()
    private val bindings = mockk<SourceStaffBindingService>()
    private val identity = PosmPharmacistIdentityService(people, mappings, bindings)
    private val sales = mockk<PosSaleService>()
    private val devices = mockk<PosmDeviceAuthenticationService>()
    private val controller = PosmController(mockk(), sales, mockk(), mockk(), mockk(), mockk(), identity, devices, mockk(), mockk())
    private val source = SaleSellerSource("a".repeat(64), "17", 41, "ws")
    private val at = Instant.parse("2026-11-16T08:00:00Z")
    private val request = PosSaleRequest(saleId="synthetic-sale", pharmacyId="ph", pharmacistId="17", printedAt=at, sellerSource=source)

    init {
        every { devices.authenticate("key", "ph", null, false) } returns PosmDeviceIdentity("device", "ph", false)
    }

    @Test fun `ordinary sale resolves source bound person at receipt time without reward evidence`() {
        every { mappings.findByPharmacyIdAndExternalUserIdAndActiveTrue("ph", "17") } returns null
        every { bindings.resolve("ph", "device", source.sourceNamespace, "17", at, "41", "ws") } returns
            SourceStaffBindingService.Resolution(true, SourceStaffBindingService.Recipient("person", "Synthetic Person"))
        val saved = slot<PosSaleRequest>()
        val resolved = slot<PosmPharmacistIdentity>()
        every { sales.record(capture(saved), capture(resolved), "device") } returns true

        assertTrue(controller.sale("key", request).accepted)
        assertEquals("person", saved.captured.pharmacistId)
        assertEquals(source, saved.captured.sellerSource)
        assertNull(saved.captured.rewardEvidence)
        assertEquals(PharmacistIdentitySource.STANDARDN_RPS_BINDING, resolved.captured.source)
        assertEquals("17", resolved.captured.reportedPharmacistId)
        verify { people wasNot Called }
    }

    @Test fun `invalid supplied source cannot downgrade to names or internal identifiers`() {
        val invalid = listOf(source.copy(sourceNamespace=""), source.copy(sourceNamespace="A".repeat(64)),
            source.copy(rawUserId="18"), source.copy(rawUserId="17\n"), source.copy(nativeSessionId=0),
            source.copy(nativeSessionId=-1), source.copy(workstationId=" "), source.copy(workstationId="x".repeat(129)),
            source.copy(workstationId="ws\u0000"))
        for (bad in invalid) assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException::class.java) { controller.sale("key", request.copy(sellerSource=bad)) }.statusCode)
        verify { listOf(people, mappings, bindings, sales) wasNot Called }
    }

    @Test fun `all four supplied fields must agree with independent reward completion`() {
        val good = RewardSourceIdentity(source.sourceNamespace, source.rawUserId, source.nativeSessionId, source.workstationId)
        for (bad in listOf(good.copy(sourceNamespace="b".repeat(64)), good.copy(rawUserId="18"),
            good.copy(nativeSessionId=42), good.copy(workstationId="other"))) {
            val req = request.copy(rewardEvidence=RewardSaleEvidence(completion=RewardCompletionEvidence(identity=bad)))
            assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException::class.java) { controller.sale("key", req) }.statusCode)
        }
        verify { listOf(people, mappings, bindings, sales) wasNot Called }
    }

    @Test fun `absent source preserves legacy payload and reward only contracts`() {
        assertNull(request.copy(sellerSource=null).validatedSellerSource())
        assertNull(request.copy(sellerSource=null, rewardEvidence=RewardSaleEvidence()).validatedSellerSource())
        val proof = RewardSourceIdentity(source.sourceNamespace, source.rawUserId, source.nativeSessionId, source.workstationId)
        assertEquals(source, request.copy(rewardEvidence=RewardSaleEvidence(completion=RewardCompletionEvidence(identity=proof))).validatedSellerSource())
    }
}
