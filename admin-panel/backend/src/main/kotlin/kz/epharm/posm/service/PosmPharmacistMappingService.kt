package kz.epharm.posm.service

import kz.epharm.auth.access.UnifiedAuthorityLock

import kz.epharm.auth.security.AdminPrincipal
import kz.epharm.pharmacies.repository.PharmacyRepository
import kz.epharm.pharmacists.entity.PharmacistStatus
import kz.epharm.pharmacists.repository.PharmacistRepository
import kz.epharm.posm.dto.PosmPharmacistMappingDto
import kz.epharm.posm.dto.UnmappedPosmSellerDto
import kz.epharm.posm.dto.UpsertPosmPharmacistMappingRequest
import kz.epharm.posm.entity.PosmPharmacistMappingEntity
import kz.epharm.posm.repository.PosmPharmacistMappingRepository
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class PosmPharmacistMappingService(
    private val mappings: PosmPharmacistMappingRepository,
    private val pharmacists: PharmacistRepository,
    private val pharmacies: PharmacyRepository,
    private val jdbc: JdbcTemplate,
) {
    @Transactional(readOnly = true)
    fun list(pharmacyId: String?, includeInactive: Boolean): List<PosmPharmacistMappingDto> {
        val normalizedPharmacy = pharmacyId?.trim()?.takeIf { it.isNotEmpty() }
        val rows = when {
            normalizedPharmacy != null && includeInactive ->
                mappings.findAllByPharmacyIdOrderByExternalUserIdAsc(normalizedPharmacy)
            normalizedPharmacy != null ->
                mappings.findAllByPharmacyIdAndActiveTrueOrderByExternalUserIdAsc(normalizedPharmacy)
            includeInactive -> mappings.findAllByOrderByPharmacyIdAscExternalUserIdAsc()
            else -> mappings.findAllByActiveTrueOrderByPharmacyIdAscExternalUserIdAsc()
        }
        val pharmacyNames = pharmacies.findAllById(rows.map { it.pharmacyId }.distinct()).associate { it.id to it.name }
        val pharmacistNames = pharmacists.findAllById(rows.map { it.pharmacistId }.distinct()).associate { it.id to it.name }
        return rows.map { row ->
            PosmPharmacistMappingDto(
                id = row.id.toString(),
                pharmacyId = row.pharmacyId,
                pharmacyName = pharmacyNames[row.pharmacyId] ?: row.pharmacyId,
                externalUserId = row.externalUserId,
                externalUserName = row.externalUserName,
                pharmacistId = row.pharmacistId,
                pharmacistName = pharmacistNames[row.pharmacistId] ?: row.pharmacistId,
                active = row.active,
                createdAt = row.createdAt,
                updatedAt = row.updatedAt,
                revokedAt = row.revokedAt,
            )
        }
    }

    @Transactional(readOnly = true)
    fun unmapped(): List<UnmappedPosmSellerDto> = jdbc.query(
        """
        WITH eligible AS (
          SELECT s.pharmacy_id,
                 COALESCE(NULLIF(BTRIM(s.reported_pharmacist_id), ''), '') AS reported_pharmacist_id,
                 NULLIF(BTRIM(s.reported_pharmacist_name), '') AS reported_name,
                 s.printed_at, s.id
          FROM pos_sales s
          WHERE (s.pharmacist_source IN ('standardn_unmapped', 'unresolved')
          OR (s.pharmacist_source IN ('standardn_name_match', 'posm_internal')
              AND s.capture_source = 'standardn-print-log'
              AND s.source_document_id > 0
              AND NULLIF(BTRIM(s.device_id), '') IS NOT NULL
              AND s.reported_pharmacist_id ~ '^-?[0-9]+$'))
        ), latest AS (
          SELECT DISTINCT ON (pharmacy_id,reported_pharmacist_id)
            pharmacy_id,reported_pharmacist_id,reported_name
          FROM eligible ORDER BY pharmacy_id,reported_pharmacist_id,printed_at DESC,id DESC
        ), totals AS (
          SELECT pharmacy_id,reported_pharmacist_id,COUNT(*) AS sales_count,
            COUNT(DISTINCT LOWER(reported_name)) AS observed_name_count,MAX(printed_at) AS last_seen_at
          FROM eligible GROUP BY pharmacy_id,reported_pharmacist_id
        ), observed AS (
          SELECT t.*,l.reported_name FROM totals t JOIN latest l USING (pharmacy_id,reported_pharmacist_id)
        ), diagnostics AS (
          SELECT s.*, COALESCE(p.name, s.pharmacy_id) AS pharmacy_name,
                 m.id IS NOT NULL AS has_mapping,
                 recipient.status = 'active' AND recipient.pharmacy_id = s.pharmacy_id AS valid_recipient,
                 (SELECT COUNT(*) FROM pharmacists a WHERE a.pharmacy_id=s.pharmacy_id AND a.status='active') AS active_count,
                 EXISTS(SELECT 1 FROM posm_source_staff_bindings b
                   WHERE b.pharmacy_id=s.pharmacy_id AND b.native_user_id=s.reported_pharmacist_id
                     AND b.state='conflict' AND (m.id IS NULL OR m.updated_at<=b.conflict_at)) AS source_conflict,
                 EXISTS(SELECT 1 FROM pharmacists a WHERE a.pharmacy_id=s.pharmacy_id AND a.status='active'
                   AND LOWER(a.name)=LOWER(s.reported_name)) AS name_match
          FROM observed s LEFT JOIN pharmacies p ON p.id=s.pharmacy_id
          LEFT JOIN posm_pharmacist_mappings m ON m.pharmacy_id=s.pharmacy_id
            AND m.external_user_id=s.reported_pharmacist_id AND m.active=true
          LEFT JOIN pharmacists recipient ON recipient.id=m.pharmacist_id
        )
        SELECT *, CASE
          WHEN reported_pharmacist_id='' THEN 'missing_user_id'
          WHEN source_conflict THEN 'source_conflict'
          WHEN has_mapping AND NOT COALESCE(valid_recipient,false) THEN 'inactive_recipient'
          WHEN active_count=0 THEN 'no_active_profiles'
          WHEN reported_name IS NULL THEN 'missing_name'
          WHEN NOT name_match THEN 'name_not_matched'
          ELSE 'confirmation_required' END AS reason
        FROM diagnostics
        WHERE NOT (has_mapping AND COALESCE(valid_recipient,false) AND NOT source_conflict)
        ORDER BY last_seen_at DESC, pharmacy_id, reported_pharmacist_id
        """.trimIndent(),
    ) { rs, _ ->
        UnmappedPosmSellerDto(
            pharmacyId = rs.getString("pharmacy_id"),
            pharmacyName = rs.getString("pharmacy_name"),
            externalUserId = rs.getString("reported_pharmacist_id"),
            externalUserName = rs.getString("reported_name"),
            salesCount = rs.getLong("sales_count"),
            lastSeenAt = rs.getTimestamp("last_seen_at").toInstant(),
            reason = rs.getString("reason"),
            activePharmacistCount = rs.getLong("active_count"),
            observedNameCount = rs.getLong("observed_name_count"),
        )
    }

    @Transactional
    fun upsert(req: UpsertPosmPharmacistMappingRequest, actor: AdminPrincipal): PosmPharmacistMappingDto {
        UnifiedAuthorityLock.acquire(jdbc)
        val pharmacyId = req.pharmacyId.trim()
        val externalUserId = req.externalUserId.trim().also {
            if (it.any(Char::isISOControl)) invalid("Standard-N USER_ID contains control characters")
        }
        val pharmacy = pharmacies.findById(pharmacyId).orElseThrow { invalid("Аптека не найдена") }
        if (!pharmacy.active) invalid("Нельзя сопоставить продавца с отключённой аптекой")
        val pharmacist = pharmacists.findById(req.pharmacistId.trim()).orElseThrow {
            invalid("Фармацевт не найден")
        }
        if (pharmacist.status != PharmacistStatus.active) invalid("Фармацевт должен быть активирован")
        if (pharmacist.pharmacyId != pharmacyId) {
            invalid("Фармацевт должен быть назначен в ту же аптеку, что и Standard-N USER_ID")
        }

        val current = jdbc.queryForList("SELECT status,pharmacy_id FROM pharmacists WHERE id=? FOR UPDATE",pharmacist.id).singleOrNull()
        if (current?.get("status") != "active" || current["pharmacy_id"] != pharmacyId) invalid("Фармацевт должен быть активен в этой аптеке")
        kz.epharm.posm.staff.SourceStaffBindingService.lockAccount(jdbc,pharmacyId,externalUserId)
        val now = Instant.now()
        val existing = mappings.findByPharmacyIdAndExternalUserId(pharmacyId, externalUserId)
        val auditAction = when {
            existing == null -> "created"
            !existing.active -> "reactivated"
            existing.pharmacistId != pharmacist.id -> "remapped"
            jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM posm_source_staff_bindings WHERE pharmacy_id=? AND native_user_id=? AND state='conflict')",
                Boolean::class.java,pharmacyId,externalUserId) == true -> "reviewed"
            else -> null
        }
        val entity = existing
            ?.also {
                it.pharmacistId = pharmacist.id
                it.externalUserName = req.externalUserName.clean()
                it.active = true
                it.updatedBy = actor.userId
                it.updatedAt = now
                it.revokedAt = null
            }
            ?: PosmPharmacistMappingEntity(
                id = UUID.randomUUID(),
                pharmacyId = pharmacyId,
                externalUserId = externalUserId,
                externalUserName = req.externalUserName.clean(),
                pharmacistId = pharmacist.id,
                createdBy = actor.userId,
                updatedBy = actor.userId,
                createdAt = now,
                updatedAt = now,
            )
        val saved = mappings.saveAndFlush(entity)
        auditAction?.let { action -> audit(saved, action, actor.userId) }
        return PosmPharmacistMappingDto(
            id = saved.id.toString(), pharmacyId = pharmacy.id, pharmacyName = pharmacy.name,
            externalUserId = saved.externalUserId, externalUserName = saved.externalUserName,
            pharmacistId = pharmacist.id, pharmacistName = pharmacist.name, active = saved.active,
            createdAt = saved.createdAt, updatedAt = saved.updatedAt, revokedAt = saved.revokedAt,
        )
    }

    @Transactional
    fun revoke(id: UUID, actor: AdminPrincipal) {
        UnifiedAuthorityLock.acquire(jdbc)
        val mapping = mappings.findById(id).orElseThrow {
            AppException(ErrorCode.NOT_FOUND, "Сопоставление не найдено", HttpStatus.NOT_FOUND)
        }
        if (!mapping.active) return
        jdbc.queryForList("SELECT id FROM pharmacists WHERE id=? FOR UPDATE",mapping.pharmacistId)
        kz.epharm.posm.staff.SourceStaffBindingService.lockAccount(jdbc,mapping.pharmacyId,mapping.externalUserId)
        val now = Instant.now()
        mapping.active = false
        mapping.updatedBy = actor.userId
        mapping.updatedAt = now
        mapping.revokedAt = now
        mappings.save(mapping)
        audit(mapping, "revoked", actor.userId)
    }

    private fun audit(mapping: PosmPharmacistMappingEntity, action: String, actorId: UUID) {
        jdbc.update(
            """
            INSERT INTO posm_pharmacist_mapping_audit (
                mapping_id, action, pharmacy_id, external_user_id, pharmacist_id, actor_id
            ) VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            mapping.id,
            action,
            mapping.pharmacyId,
            mapping.externalUserId,
            mapping.pharmacistId,
            actorId,
        )
    }

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
    private fun invalid(message: String): Nothing =
        throw AppException(ErrorCode.VALIDATION_FAILED, message, HttpStatus.BAD_REQUEST)
}
