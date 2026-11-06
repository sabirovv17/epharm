package kz.epharm.posm.staff

import io.mockk.*
import kz.epharm.posm.service.PosmDeviceAuthenticationService
import kz.epharm.posm.service.PosmDeviceIdentity
import kz.epharm.shared.error.AppException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StaffAuthDisabledTest {
    @Test fun `dark deployment exposes capabilities but never calls login service`() {
        val devices=mockk<PosmDeviceAuthenticationService>()
        val service=mockk<StaffAuthService>()
        val limiter=mockk<kz.epharm.mobile.auth.service.PharmacistPasswordLoginLimiter>()
        every { devices.authenticate("device-key") } returns PosmDeviceIdentity("device","pharmacy",false)
        val controller=StaffAuthController(devices,service,false,limiter)
        assertEquals(mapOf("enabled" to false,"protocolVersion" to 2,"nameFallbackSupported" to true,"deviceEmployeeAuthVersion" to 1),controller.capabilities("device-key").body)
        val error=assertThrows(AppException::class.java){controller.login("device-key",StaffLoginRequest("951212500015","Password123!",StaffContext("u","s","w","p")))}
        assertEquals(503,error.status.value())
        assertEquals(503,assertThrows(AppException::class.java){
            controller.checkSession("device-key","token",StaffContext("u","s","w","p"))
        }.status.value())
        assertEquals(503,assertThrows(AppException::class.java){
            controller.register("device-key",StaffPasswordRequest("challenge","1234","951212500015","+77002223344","Password123!","Test",StaffContext("u","s","w","p")))
        }.status.value())
        assertEquals(503,assertThrows(AppException::class.java){
            controller.deviceLogin("device-key",StaffDeviceLoginRequest("951212500015","A1!b",StaffDeviceContext("process")))
        }.status.value())
        assertEquals(503,assertThrows(AppException::class.java){
            controller.checkDeviceSession("device-key","token",StaffDeviceContext("process"))
        }.status.value())
        assertEquals(503,assertThrows(AppException::class.java){
            controller.deviceRegister("device-key",StaffDeviceRegistrationRequest("challenge","1234","951212500015","+77002223344","A1!b","Test Person",StaffDeviceContext("process")))
        }.status.value())
        verify { service wasNot Called }
        verify { limiter wasNot Called }
    }
}
