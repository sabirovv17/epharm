package kz.epharm.mobile.auth.security

import kz.epharm.pharmacists.entity.PharmacistStatus
import kz.epharm.pharmacists.repository.PharmacistRepository
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component

/** A signed JWT cannot preserve access after the current account is blocked or deleted. */
@Component
class PharmacistSessionLoader(private val pharmacists: PharmacistRepository) {
    fun load(subject: String): PharmacistPrincipal? {
        if (subject.isBlank() || subject.length > 64) return null
        // One primary-key lookup per authenticated request, without a cache of revoked status.
        // Database failures must propagate rather than authorize from stale JWT claims.
        val pharmacist = pharmacists.findById(subject).orElse(null)
            ?: throw AppException(ErrorCode.USER_NOT_FOUND, "Фармацевт не найден", HttpStatus.UNAUTHORIZED)
        // Pending accounts retain their existing onboarding and assignment-waiting flow.
        if (pharmacist.statusRaw !in setOf(PharmacistStatus.active.name, PharmacistStatus.pending.name))
            throw AppException(ErrorCode.USER_NOT_ACTIVE, "Аккаунт не активен", HttpStatus.FORBIDDEN)
        return PharmacistPrincipal(pharmacist.id, pharmacist.name, pharmacist.phone)
    }
}
