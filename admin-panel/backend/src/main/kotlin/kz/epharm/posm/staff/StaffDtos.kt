package kz.epharm.posm.staff

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import kz.epharm.shared.validation.Iin
import java.time.Instant

data class StaffContext(
    @field:NotBlank @field:Size(max=128) val standardNUserId: String,
    @field:NotBlank @field:Size(max=128) val standardNSessionId: String,
    @field:NotBlank @field:Size(max=128) val workstationId: String,
    @field:NotBlank @field:Size(max=256) val processInstanceId: String,
)
data class StaffLoginRequest(
    @field:Iin @field:NotBlank @field:Size(min=12,max=12) val iin: String,
    @field:Size(min=6,max=128) val password: String,
    @field:Valid val context: StaffContext,
)
data class StaffOtpRequest(
    @field:Pattern(regexp="register|reset|activate") val purpose: String,
    @field:Iin @field:NotBlank @field:Size(min=12,max=12) val iin: String,
    @field:Size(max=32) @field:Pattern(regexp="[+0-9 ()\\-]{6,32}") val phone: String,
)
data class StaffPasswordRequest(
    @field:Size(max=36) val challengeId: String,
    @field:Pattern(regexp="[0-9]{4,8}") val code: String,
    @field:Iin @field:NotBlank @field:Size(min=12,max=12) val iin: String,
    @field:Size(max=32) @field:Pattern(regexp="[+0-9 ()\\-]{6,32}") val phone: String,
    @field:Size(min=8,max=128) val password: String,
    @field:Size(min=3,max=255) val name: String? = null,
)
data class StaffPerson(val id: String,val name: String)
data class StaffSessionResponse(
    val status: String,
    val sessionToken: String?,
    val expiresAt: Instant?,
    val pharmacist: StaffPerson,
    val context: StaffContext,
)
data class StaffOtpResponse(val challengeId: String,val ttlSeconds: Long=300,val phoneMasked: String,val devCode: String?=null)
