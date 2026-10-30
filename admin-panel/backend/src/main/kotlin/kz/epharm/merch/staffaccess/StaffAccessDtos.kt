package kz.epharm.merch.staffaccess

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** A scoped shared session is never a pharmacist, person, producer, or administrator. */
data class StaffAccessContext internal constructor(
    val sessionId: UUID,
    val codeId: UUID,
    val organizationId: UUID,
    val placementMonth: LocalDate,
    val branchIds: Set<UUID>,
    val deadlineAt: Instant?,
    val sessionExpiresAt: Instant,
    val readOnly: Boolean,
)

class StaffCodeRequest(val code: String? = null) {
    override fun toString() = "StaffCodeRequest(code=redacted)"
}
class StaffQrRequest(val access: String? = null) {
    override fun toString() = "StaffQrRequest(access=redacted)"
}
class StaffAccessLogin(val token: String, val expiresAt: Instant, val scope: StaffAccessContext) {
    override fun toString() = "StaffAccessLogin(token=redacted, expiresAt=$expiresAt)"
}

data class StaffAccessBranchOption(
    val id: UUID, val code: String, val name: String, val city: String, val address: String,
    val canCreate: Boolean,
)
data class StaffAccessOrganizationOption(val id: UUID, val name: String, val branches: List<StaffAccessBranchOption>)
data class StaffAccessOptions(val organizations: List<StaffAccessOrganizationOption>)
data class CreateStaffAccess(
    val organizationId: UUID,
    val placementMonth: LocalDate,
    val branchIds: Set<UUID>,
    val deadlineAt: Instant? = null,
)
data class StaffAccessCodeView(
    val codeId: UUID, val organizationId: UUID, val placementMonth: LocalDate,
    val branchIds: Set<UUID>, val allOrgBranches: Boolean, val deadlineAt: Instant?,
    val status: String, val maxUses: Int, val useCount: Int, val qrUseCount: Long,
    val createdAt: Instant, val lastUsedAt: Instant?, val hasQrToken: Boolean,
    val manualRevoked: Boolean, val exhausted: Boolean, val expired: Boolean, val canRevoke: Boolean,
    val note: String? = null,
)
data class StaffAccessSettings(
    val organizationId: UUID, val organizationName: String, val placementMonth: LocalDate,
    val branches: List<StaffAccessBranchOption>, val codes: List<StaffAccessCodeView>, val canCreate: Boolean,
)
class StaffAccessCreated(val grant: StaffAccessCodeView, val code: String, val accessToken: String) {
    override fun toString() = "StaffAccessCreated(codeId=${grant.codeId}, credentials=redacted)"
}
