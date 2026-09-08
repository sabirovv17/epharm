package kz.epharm.posm.service

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
        SELECT s.pharmacy_id,
               COALESCE(p.name, s.pharmacy_id) AS pharmacy_name,
               s.reported_pharmacist_id,
               MAX(NULLIF(s.reported_pharmacist_name, '')) AS reported_name,
               COUNT(*) AS sales_count,
               MAX(s.printed_at) AS last_seen_at
        FROM pos_sales s
        LEFT JOIN pharmacies p ON p.id = s.pharmacy_id
        WHERE s.pharmacist_source = 'standardn_unmapped'
          AND NULLIF(s.reported_pharmacist_id, '') IS NOT NULL
          AND NOT EXISTS (
              SELECT 1 FROM posm_pharmacist_mappings m
              WHERE m.pharmacy_id = s.pharmacy_id
                AND m.external_user_id = s.reported_pharmacist_id
                AND m.active = true
          )
        GROUP BY s.pharmacy_id, p.name, s.reported_pharmacist_id
        ORDER BY MAX(s.printed_at) DESC
        """.trimIndent(),
    ) { rs, _ ->
        UnmappedPosmSellerDto(
            pharmacyId = rs.getString("pharmacy_id"),
            pharmacyName = rs.getString("pharmacy_name"),
            externalUserId = rs.getString("reported_pharmacist_id"),
            externalUserName = rs.getString("reported_name"),
            salesCount = rs.getLong("sales_count"),
            lastSeenAt = rs.getTimestamp("last_seen_at").toInstant(),
        )
    }

    @Transactional
    fun upsert(req: UpsertPosmPharmacistMappingRequest, actor: AdminPrincipal): PosmPharmacistMappingDto {
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

        val now = Instant.now()
        val existing = mappings.findByPharmacyIdAndExternalUserId(pharmacyId, externalUserId)
        val auditAction = when {
            existing == null -> "created"
            !existing.active -> "reactivated"
            existing.pharmacistId != pharmacist.id -> "remapped"
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
        val saved = mappings.save(entity)
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
        val mapping = mappings.findById(id).orElseThrow {
            AppException(ErrorCode.NOT_FOUND, "Сопоставление не найдено", HttpStatus.NOT_FOUND)
        }
        if (!mapping.active) return
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
