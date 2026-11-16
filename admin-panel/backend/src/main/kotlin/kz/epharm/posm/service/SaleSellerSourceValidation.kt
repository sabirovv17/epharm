package kz.epharm.posm.service

import kz.epharm.posm.dto.PosSaleRequest
import kz.epharm.posm.dto.SaleSellerSource
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

/** Reject a supplied contradictory context instead of silently falling back to name/internal-ID matching. */
internal fun PosSaleRequest.validatedSellerSource(reportedUser: String? = pharmacistId): SaleSellerSource? {
    val source = sellerSource ?: return null // Legacy outbox entries remain readable.
    fun opaque(value: String) = value.isNotBlank() && value.length <= 128 && value.none(Char::isISOControl)
    if (!source.sourceNamespace.matches(Regex("[a-f0-9]{64}")) || !opaque(source.rawUserId) ||
        source.nativeSessionId <= 0 || !opaque(source.workstationId) || source.rawUserId != reportedUser) {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid receipt seller source")
    }
    rewardEvidence?.completion?.identity?.let { reward ->
        if (source.sourceNamespace != reward.sourceNamespace || source.rawUserId != reward.rawUserId ||
            source.nativeSessionId != reward.nativeSessionId || source.workstationId != reward.workstationId) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Receipt seller source conflicts with reward evidence")
        }
    }
    return source
}
