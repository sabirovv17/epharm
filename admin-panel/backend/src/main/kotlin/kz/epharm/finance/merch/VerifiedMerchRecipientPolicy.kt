package kz.epharm.finance.merch

import kz.epharm.shared.PhoneUtil
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

/** The same current recipient proof applies to signed legacy claims and native task rewards. */
@Service
class VerifiedMerchRecipientPolicy(private val jdbc: JdbcTemplate) {
    fun requireRecipient(id: String, pharmacy: String, locked: Boolean = false): VerifiedMerchRecipient {
        val person = jdbc.query("""SELECT p.phone,p.status,p.pharmacy_id,h.name,h.active AS pharmacy_active
            FROM pharmacists p JOIN pharmacies h ON h.id=p.pharmacy_id WHERE p.id=?${if (locked) " FOR UPDATE OF p" else ""}""",
            { rs, _ -> VerifiedMerchRecipient(PhoneUtil.normalize(rs.getString("phone")), rs.getString("status"), rs.getString("pharmacy_id"),
                rs.getString("name"), rs.getBoolean("pharmacy_active")) }, id).singleOrNull()
            ?: throw AppException(ErrorCode.USER_NOT_ACTIVE, "Нужна активная учётная запись фармацевта", HttpStatus.FORBIDDEN)
        if (person.status != "active" || !person.pharmacyActive) throw AppException(ErrorCode.USER_NOT_ACTIVE, "Учётная запись или аптека не активна", HttpStatus.FORBIDDEN)
        if (person.pharmacy != pharmacy) throw AppException(ErrorCode.FORBIDDEN, "Задание назначено другой аптеке", HttpStatus.FORBIDDEN)
        if (!person.phone.matches(Regex("\\+7[0-9]{10}"))) throw AppException(ErrorCode.VALIDATION_FAILED, "В профиле не указан корректный телефон")
        val proven = jdbc.query("""SELECT m.id FROM posm_pharmacist_mappings m
            JOIN LATERAL (SELECT a.action,a.pharmacy_id,a.pharmacist_id,a.actor_id
                FROM posm_pharmacist_mapping_audit a WHERE a.mapping_id=m.id ORDER BY a.id DESC LIMIT 1) a ON true
            JOIN admin_users hq ON hq.id=a.actor_id AND hq.role IN ('SYSTEM_ADMIN','HQ_HEAD') AND hq.status='active'
            WHERE m.pharmacist_id=? AND m.pharmacy_id=? AND m.active=true AND m.revoked_at IS NULL
                AND trim(m.external_user_id)<>'' AND a.pharmacist_id=m.pharmacist_id AND a.pharmacy_id=m.pharmacy_id
                AND a.action IN ('created','remapped','reactivated')
            ORDER BY m.id LIMIT 1 ${if (locked) "FOR SHARE OF m,hq" else ""}""",{ rs,_ -> rs.getObject(1) },id,pharmacy).isNotEmpty()
        if (!proven) throw AppException(ErrorCode.FORBIDDEN,"HQ должен подтвердить привязку фармацевта к аптеке перед начислением за мерч",HttpStatus.FORBIDDEN)
        return person
    }
}

data class VerifiedMerchRecipient(val phone: String, val status: String, val pharmacy: String,
    val pharmacyName: String, val pharmacyActive: Boolean)
