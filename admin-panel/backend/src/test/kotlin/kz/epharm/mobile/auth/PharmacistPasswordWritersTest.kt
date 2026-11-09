package kz.epharm.mobile.auth

import io.mockk.*
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.security.AdminPrincipal
import kz.epharm.mobile.auth.service.MobileAuthService
import kz.epharm.pharmacists.dto.CreatePharmacistRequest
import kz.epharm.pharmacists.dto.UpdatePharmacistRequest
import kz.epharm.pharmacists.service.PharmacistService
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.util.UUID

/** Invalid input must fail before optional vault capture, OTP proof consumption or account writes. */
class PharmacistPasswordWritersTest {
    @Test fun `learner initial password refuses truncating inputs before contacting any dependency`() {
        val service = MobileAuthService(mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk())
        for (value in listOf("x".repeat(73), "я".repeat(37), " ".repeat(8))) {
            val failure = assertThrows(AppException::class.java) {
                service.setInitialPassword("000101500011", "+77000000012", value)
            }
            assertEquals(ErrorCode.VALIDATION_FAILED, failure.code)
        }
    }

    @Test fun `administrative create refuses truncating input before account lookup or encoding`() {
        val service = PharmacistService(mockk(), mockk(), mockk(), mockk(), mockk(), mockk())
        assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows(AppException::class.java) {
            service.create(CreatePharmacistRequest("Synthetic", "000101500011", "+77000000012", "я".repeat(37), "synthetic"))
        }.code)
    }

    @Test fun `administrative password update rejects blank and oversized values before mutating an account`() {
        val scope = mockk<kz.epharm.auth.access.AdministrativeScope>()
        every { scope.requirePharmacistUpdate(any()) } just Runs
        val service = PharmacistService(mockk(), mockk(), mockk(), mockk(), scope, mockk())
        val principal = AdminPrincipal(UUID.randomUUID(), "synthetic@example.invalid", "Synthetic", AdminRole.HQ_HEAD, "Synthetic")
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(principal, null)
        try {
            for (value in listOf(" ".repeat(8), "x".repeat(73), "я".repeat(37))) {
                assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows(AppException::class.java) {
                    service.update("synthetic", UpdatePharmacistRequest(password = value))
                }.code)
            }
        } finally { SecurityContextHolder.clearContext() }
    }
}
