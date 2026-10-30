package kz.epharm.training.access

import kz.epharm.auth.access.AdministrativeScope
import kz.epharm.auth.policy.AdministrationModule
import kz.epharm.auth.policy.PermissionKey
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.security.AdminPrincipal
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.util.UUID

/** Only administrative requests use this adapter. Learner and existing LEGACY callers keep their contract. */
@Component
class TrainingAdministrativeAccess(private val access: AdministrativeScope, private val jdbc: NamedParameterJdbcTemplate) {
    val managed get() = access.hasManagedRequest()
    fun lockWriter() { if (managed) access.lockCurrentActor() }
    fun requireAll(subsection: String, action: String) { if (managed) access.requireAllPermission(key(subsection, action)) }
    fun can(subsection: String, action: String): Boolean = !managed || access.canAllPermission(key(subsection, action))
    fun canScoped(subsection: String, action: String): Boolean {
        if (!managed) return true
        val ids = access.currentPharmacyIds() ?: return can(subsection, action)
        return ids.isNotEmpty() && ids.all { access.canPharmacyPermission(key(subsection, action), it) }
    }
    // Person-wide rewards keep their historical pharmacy; current learning scope cannot grant that money.
    fun financial(): Boolean = !managed || access.canAllPermission(
        PermissionKey(AdministrationModule.PHARMACISTS, "registry", "financial_data"))
    fun requireFinancial() { if (!financial()) deny() }
    fun forbidManagedCsv() { if (managed) throw AppException(ErrorCode.CONFLICT, "Скачайте отдельный отчёт Excel из раздела обучения", HttpStatus.CONFLICT) }

    private data class Where(val sql: String, val params: MutableMap<String, Any>)
    private fun where(principal: AdminPrincipal, personAlias: String = "p", assignmentAlias: String? = null): Where {
        check(managed)
        val params = mutableMapOf<String, Any>("actor" to principal.userId)
        val ids = access.currentPharmacyIds()
        val sql = buildString {
            if (ids != null) {
                if (ids.isEmpty()) append(" AND false")
                else { append(" AND $personAlias.pharmacy_id IN (:pharmacies)"); params["pharmacies"] = ids }
            }
            when (principal.role) {
                AdminRole.REGIONAL_MANAGER -> append(" AND EXISTS(SELECT 1 FROM admin_training_regions r WHERE r.admin_user_id=:actor AND r.region=$personAlias.city)")
                AdminRole.TRAINER -> if (assignmentAlias == null) append(" AND false") else
                    append(" AND EXISTS(SELECT 1 FROM offline_events own WHERE own.id=$assignmentAlias.event_id AND own.trainer_id=:actor)")
                AdminRole.SYSTEM_ADMIN, AdminRole.HQ_HEAD, AdminRole.TRAINING_MANAGER -> Unit
                else -> append(" AND false")
            }
        }
        return Where(sql, params)
    }

    fun assignmentIds(principal: AdminPrincipal): List<UUID> {
        val w = where(principal, assignmentAlias = "a")
        return jdbc.query("SELECT a.id FROM training_assignments a JOIN pharmacists p ON p.id=a.pharmacist_id WHERE true ${w.sql} ORDER BY a.created_at DESC,a.id", w.params) { rs, _ -> rs.getObject(1, UUID::class.java) }
    }
    fun requireAssignment(id: UUID, principal: AdminPrincipal) {
        if (!managed) return
        val w = where(principal, assignmentAlias = "a"); w.params["id"] = id
        if (jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM training_assignments a JOIN pharmacists p ON p.id=a.pharmacist_id WHERE a.id=:id ${w.sql})", w.params, Boolean::class.java) != true) deny()
    }
    fun requirePharmacist(id: String, principal: AdminPrincipal) {
        if (!managed) return
        val w = where(principal); w.params["id"] = id
        if (jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pharmacists p WHERE p.id=:id ${w.sql})", w.params, Boolean::class.java) != true) deny()
    }
    fun preferenceIds(principal: AdminPrincipal): List<UUID> {
        val w = where(principal)
        return jdbc.query("SELECT pref.id FROM pharmacist_training_preferences pref JOIN pharmacists p ON p.id=pref.pharmacist_id WHERE pref.valid_to IS NULL ${w.sql} ORDER BY pref.pharmacist_id", w.params) { rs, _ -> rs.getObject(1, UUID::class.java) }
    }
    fun eventIds(principal: AdminPrincipal): List<UUID> {
        val params = mutableMapOf<String, Any>("actor" to principal.userId)
        val ids = access.currentPharmacyIds()
        val sql = buildString {
            when (principal.role) {
                AdminRole.REGIONAL_MANAGER -> append(" AND EXISTS(SELECT 1 FROM admin_training_regions r WHERE r.admin_user_id=:actor AND r.region=e.region)")
                AdminRole.TRAINER -> append(" AND e.trainer_id=:actor")
                AdminRole.SYSTEM_ADMIN, AdminRole.HQ_HEAD, AdminRole.TRAINING_MANAGER -> Unit
                else -> append(" AND false")
            }
            if (ids != null) {
                if (ids.isEmpty()) append(" AND false") else {
                    params["pharmacies"] = ids
                    append(""" AND (EXISTS(SELECT 1 FROM pharmacies ph WHERE ph.id IN (:pharmacies) AND ph.city=e.region)
                        OR EXISTS(SELECT 1 FROM training_assignments a JOIN pharmacists p ON p.id=a.pharmacist_id WHERE a.event_id=e.id AND p.pharmacy_id IN (:pharmacies)))""")
                }
            }
        }
        return jdbc.query("SELECT e.id FROM offline_events e WHERE true $sql ORDER BY e.starts_at DESC,e.id", params) { rs, _ -> rs.getObject(1, UUID::class.java) }
    }
    fun requireEvent(id: UUID, principal: AdminPrincipal) { if (managed && id !in eventIds(principal)) deny() }
    fun participantIds(eventId: UUID, principal: AdminPrincipal): List<UUID> {
        val w = where(principal, assignmentAlias = "a"); w.params["event"] = eventId
        return jdbc.query("""SELECT ep.id FROM event_participants ep JOIN training_assignments a ON a.id=ep.assignment_id
            AND a.pharmacist_id=ep.pharmacist_id AND a.event_id=ep.event_id JOIN pharmacists p ON p.id=a.pharmacist_id
            WHERE ep.event_id=:event ${w.sql} ORDER BY ep.registered_at,ep.id""", w.params) { rs, _ -> rs.getObject(1, UUID::class.java) }
    }
    private fun deny(): Nothing = throw AppException(ErrorCode.FORBIDDEN, "Учебная запись находится вне разрешённой области", HttpStatus.FORBIDDEN)
    companion object {
        fun key(subsection: String, action: String) = PermissionKey(AdministrationModule.TRAINING, subsection, action)
    }
}
