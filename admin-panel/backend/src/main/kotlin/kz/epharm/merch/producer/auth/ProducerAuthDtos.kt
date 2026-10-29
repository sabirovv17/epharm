package kz.epharm.merch.producer.auth

import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

data class ProducerPrincipal(val customerId: UUID,val sessionId: UUID)
data class ProducerSmsRequest(@field:Pattern(regexp="\\+?[0-9 ()-]{10,32}") val phone: String)
data class ProducerSmsVerify(val challengeId: UUID,@field:Pattern(regexp="[0-9]{4,8}") val code: String)
data class ProducerRefreshRequest(@field:Size(min=32,max=128) @field:Pattern(regexp="[A-Za-z0-9_-]+") val refreshToken: String)
data class ProducerSmsChallenge(val challengeId: UUID,val ttlSeconds: Int=300,val retryAfterSeconds: Int=60,
    val phoneMasked: String,val message: String="Если номер зарегистрирован, код отправлен")
data class ProducerProfile(val id: UUID,val name: String?,val phoneMasked: String,val profileComplete:Boolean=!name.isNullOrBlank())
data class ProducerAuthResponse(val accessToken: String,val refreshToken: String,val tokenType: String="Bearer",
    val expiresIn: Long,val sessionExpiresAt: Instant,val customer: ProducerProfile)
