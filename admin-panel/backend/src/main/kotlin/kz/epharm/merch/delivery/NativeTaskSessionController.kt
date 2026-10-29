package kz.epharm.merch.delivery

import java.time.Instant
import java.util.UUID
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class NativeTaskSession(val dispatchId:UUID,val branchId:UUID,val expiresAt:Instant,
    val readOnly:Boolean,val rewardAmountKzt:Long,val identityRequired:Boolean)

@RestController
@RequestMapping("/api/public/merch/tasks")
class NativeTaskSessionController(private val capabilities:NativeCapabilityAuthorization) {
    @GetMapping("/session")
    fun session(@RequestHeader("X-Merch-Task-Token") token:String):ResponseEntity<NativeTaskSession> {
        val cap=capabilities.authorize(token)
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"no-store")
            .header("Referrer-Policy","no-referrer").header("X-Content-Type-Options","nosniff")
            .body(NativeTaskSession(cap.dispatchId,cap.branchId,cap.expiresAt,cap.readOnly,cap.rewardAmountKzt,cap.rewardAmountKzt>0))
    }
}
