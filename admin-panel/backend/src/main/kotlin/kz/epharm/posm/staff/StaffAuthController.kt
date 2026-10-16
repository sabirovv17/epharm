package kz.epharm.posm.staff

import jakarta.validation.Valid
import kz.epharm.posm.service.PosmDeviceAuthenticationService
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/posm/staff")
@Validated
class StaffAuthController(
    private val devices: PosmDeviceAuthenticationService,
    private val service: StaffAuthService,
    @Value("\${app.posm.staff-auth-enabled:false}") private val enabled: Boolean,
) {
    @GetMapping("/capabilities")
    fun capabilities(@RequestHeader("X-Posm-Key",required=false) key:String?):ResponseEntity<*> {
        device(key,false)
        return response(mapOf("enabled" to enabled,"protocolVersion" to 1))
    }
    @PostMapping("/login")
    fun login(@RequestHeader("X-Posm-Key",required=false) key:String?,@Valid @RequestBody request:StaffLoginRequest)=response(service.login(device(key),request))
    @PostMapping("/otp/request")
    fun otp(@RequestHeader("X-Posm-Key",required=false) key:String?,@Valid @RequestBody request:StaffOtpRequest)=response(service.requestOtp(device(key),request))
    @PostMapping("/register")
    fun register(@RequestHeader("X-Posm-Key",required=false) key:String?,@Valid @RequestBody request:StaffPasswordRequest):ResponseEntity<*> {
        service.complete(device(key),request,"register")
        return response(mapOf("status" to "pending"))
    }
    @PostMapping("/password/{purpose:reset|activate}")
    fun reset(@RequestHeader("X-Posm-Key",required=false) key:String?,@PathVariable purpose:String,@Valid @RequestBody request:StaffPasswordRequest):ResponseEntity<*> {
        service.complete(device(key),request,purpose)
        return response(mapOf("changed" to true))
    }
    @GetMapping("/session")
    fun session(@RequestHeader("X-Posm-Key",required=false) key:String?,@RequestHeader("X-Staff-Session",required=false) token:String?,@Valid @ModelAttribute context:StaffContext)=response(service.session(device(key),token,context))
    @PostMapping("/logout")
    fun logout(@RequestHeader("X-Posm-Key",required=false) key:String?,@RequestHeader("X-Staff-Session",required=false) token:String?):ResponseEntity<Void> {
        service.logout(device(key),token)
        return ResponseEntity.noContent().header("Cache-Control","no-store").build()
    }
    private fun device(key:String?,requireEnabled:Boolean=true)=devices.authenticate(key).also {
        if(it.legacy || it.deviceId.isNullOrBlank() || it.pharmacyId.isNullOrBlank()) throw AppException(ErrorCode.UNAUTHORIZED,"Требуется индивидуальный ключ кассы",HttpStatus.UNAUTHORIZED)
        if(requireEnabled && !enabled) throw AppException(ErrorCode.UPSTREAM_UNAVAILABLE,"Вход сотрудника пока недоступен",HttpStatus.SERVICE_UNAVAILABLE)
    }
    private fun <T> response(body:T)=ResponseEntity.ok().header("Cache-Control","no-store").body(body)
}
