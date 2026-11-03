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
        every { devices.authenticate("device-key") } returns PosmDeviceIdentity("device","pharmacy",false)
        val controller=StaffAuthController(devices,service,false)
        assertEquals(mapOf("enabled" to false,"protocolVersion" to 1),controller.capabilities("device-key").body)
        val error=assertThrows(AppException::class.java){controller.login("device-key",StaffLoginRequest("951212500015","Password123!",StaffContext("u","s","w","p")))}
        assertEquals(503,error.status.value())
        assertEquals(503,assertThrows(AppException::class.java){
            controller.checkSession("device-key","token",StaffContext("u","s","w","p"))
        }.status.value())
        assertEquals(503,assertThrows(AppException::class.java){
            controller.register("device-key",StaffPasswordRequest("challenge","1234","951212500015","+77002223344","Password123!","Test",StaffContext("u","s","w","p")))
        }.status.value())
        verify { service wasNot Called }
    }
}
