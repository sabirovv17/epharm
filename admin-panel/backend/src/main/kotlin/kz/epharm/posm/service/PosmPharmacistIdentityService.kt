package kz.epharm.posm.service

import kz.epharm.pharmacists.entity.PharmacistStatus
import kz.epharm.pharmacists.repository.PharmacistRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

enum class PharmacistIdentitySource(val wireValue: String) {
    POSM_INTERNAL("posm_internal"),
    STANDARDN_NAME_MATCH("standardn_name_match"),
    STANDARDN_UNMAPPED("standardn_unmapped"),
    UNRESOLVED("unresolved"),
}

data class PosmPharmacistIdentity(
    /** Trusted internal ePharm id. Empty when Standard-N identity is not mapped yet. */
    val pharmacistId: String,
    val pharmacistName: String?,
    val source: PharmacistIdentitySource,
    val reportedPharmacistId: String?,
    val reportedPharmacistName: String?,
)

/**
 * Resolves the cashier reported by POSM without relying on a mobile shift.
 *
 * Standard-N identifiers are preserved verbatim for diagnostics. They become a trusted bonus
 * identity only when they are already an internal id for this pharmacy, or when an exact unique
 * full-name match exists among active pharmacists assigned to the same pharmacy.
 */
@Service
class PosmPharmacistIdentityService(
    private val pharmacistRepository: PharmacistRepository,
) {
    @Transactional(readOnly = true)
    fun resolve(
        pharmacyId: String,
        reportedPharmacistId: String?,
        reportedPharmacistName: String?,
    ): PosmPharmacistIdentity {
        val reportedId = reportedPharmacistId.clean()
        val reportedName = reportedPharmacistName.clean()

        val internal = reportedId?.let { pharmacistRepository.findById(it).orElse(null) }
        if (internal != null && internal.status == PharmacistStatus.active && internal.pharmacyId == pharmacyId) {
            return PosmPharmacistIdentity(
                pharmacistId = internal.id,
                pharmacistName = internal.name,
                source = PharmacistIdentitySource.POSM_INTERNAL,
                reportedPharmacistId = reportedId,
                reportedPharmacistName = reportedName,
            )
        }

        val nameMatch = reportedName?.let { name ->
            pharmacistRepository.findAllByPharmacyIdAndNameIgnoreCaseAndStatusRaw(
                pharmacyId = pharmacyId,
                name = name,
                statusRaw = PharmacistStatus.active.name,
            )
                .singleOrNull()
        }
        if (nameMatch != null) {
            return PosmPharmacistIdentity(
                pharmacistId = nameMatch.id,
                pharmacistName = nameMatch.name,
                source = PharmacistIdentitySource.STANDARDN_NAME_MATCH,
                reportedPharmacistId = reportedId,
                reportedPharmacistName = reportedName,
            )
        }

        return PosmPharmacistIdentity(
            pharmacistId = "",
            pharmacistName = reportedName ?: reportedId,
            source = if (reportedId != null || reportedName != null) {
                PharmacistIdentitySource.STANDARDN_UNMAPPED
            } else {
                PharmacistIdentitySource.UNRESOLVED
            },
            reportedPharmacistId = reportedId,
            reportedPharmacistName = reportedName,
        )
    }

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }?.take(255)
}
