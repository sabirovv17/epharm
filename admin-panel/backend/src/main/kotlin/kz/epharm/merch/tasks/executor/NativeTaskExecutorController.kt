package kz.epharm.merch.tasks.executor

import java.util.UUID
import kz.epharm.mobile.auth.security.PharmacistPrincipal
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/mobile/merch/tasks/{dispatchId}/executor")
class NativeTaskExecutorController(private val executors:NativeTaskExecutorService) {
    @GetMapping
    fun context(@PathVariable dispatchId:UUID,@RequestHeader("X-Merch-Task-Token") token:String,@AuthenticationPrincipal principal:PharmacistPrincipal)=
        response(executors.context(dispatchId,principal.pharmacistId,token))
    @PostMapping("/sms/request")
    fun request(@PathVariable dispatchId:UUID,@RequestHeader("X-Merch-Task-Token") token:String,@AuthenticationPrincipal principal:PharmacistPrincipal)=
        response(executors.requestSms(dispatchId,principal.pharmacistId,token))
    @PostMapping("/sms/verify")
    fun verify(@PathVariable dispatchId:UUID,@RequestHeader("X-Merch-Task-Token") token:String,@AuthenticationPrincipal principal:PharmacistPrincipal,
        @RequestBody request:VerifyNativeExecutorSms)=response(executors.verifySms(dispatchId,principal.pharmacistId,token,request))
    private fun <T> response(value:T)=ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"no-store").body(value)
}
