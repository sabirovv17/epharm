package kz.epharm.reporting.producers

import com.fasterxml.jackson.annotation.JsonAnySetter
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kz.epharm.auth.access.AdministrativeScope
import kz.epharm.auth.access.UnifiedAdminCatalog
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.annotation.Isolation

data class BindPromoProducer(val expectedRevision: Long, val producerId: UUID, val wholeHistory: Boolean = false,
    val from: LocalDate? = null, val to: LocalDate? = null, val reference: String) {
    @JsonAnySetter fun rejectUnknown(key: String, value: Any?) { throw IllegalArgumentException("Unsupported binding field") }
}
data class RetractPromoProducer(val expectedRevision: Long, val bindingId: UUID, val reference: String) {
    @JsonAnySetter fun rejectUnknown(key: String, value: Any?) { throw IllegalArgumentException("Unsupported retraction field") }
}
data class ProducerBindingView(val id: UUID, val producerId: UUID, val producerName: String?, val revision: Long,
    val wholeHistory: Boolean, val from: LocalDate?, val to: LocalDate?, val verifiedBy: UUID, val verifiedAt: Instant,
    val retractionRevision: Long?, val retractedBy: UUID?, val retractedAt: Instant?)
data class PromoProducerView(val promoId: String, val revision: Long, val bindings: List<ProducerBindingView>)

/** Provenance is append-only evidence, never a brand/name match or a grant. */
@Service
class ProducerBindingService(private val jdbc: JdbcTemplate, private val access: AdministrativeScope,
    private val catalog: UnifiedAdminCatalog) {
    private val viewKey = UnifiedAdminCatalog.key("motivation", "campaigns", "view")

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun view(promoId: String): PromoProducerView {
        access.requireExplicitPermission(viewKey); access.requireAllPermission(viewKey)
        requirePromo(promoId, false)
        return current(promoId)
    }

    @Transactional
    fun bind(promoId: String, request: BindPromoProducer, actor: UUID): PromoProducerView {
        access.lockCurrentActor()
        access.requireExplicitPermission(catalog.producerBindingManage); access.requireAllPermission(catalog.producerBindingManage)
        val reference = reference(request.reference)
        if (request.expectedRevision < 0 || (request.wholeHistory && (request.from != null || request.to != null)) ||
            (!request.wholeHistory && (request.from == null || request.to == null || request.from > request.to)) ||
            listOfNotNull(request.from, request.to).any { it.year !in 1..9999 }) invalid("Укажите всю историю либо явный период включительно")
        requirePromo(promoId, true)
        val revision = expectRevision(promoId, request.expectedRevision)
        val producer = jdbc.queryForList("SELECT name,active,source_account_role FROM merch_producers WHERE id=? FOR UPDATE", request.producerId)
            .singleOrNull() ?: notFound("Производитель не найден")
        if (producer["active"] != true || producer["source_account_role"] != "advertiser") forbidden()
        if ((producer["name"] as? String).isNullOrBlank()) throw AppException(ErrorCode.COMPLETE_PROFILE,
            "Заполните название компании в профиле производителя", HttpStatus.CONFLICT)
        val overlap = jdbc.queryForObject("""SELECT EXISTS(SELECT 1 FROM promo_producer_bindings b
            WHERE b.promo_id=? AND NOT EXISTS(SELECT 1 FROM promo_producer_binding_retractions r WHERE r.binding_id=b.id)
            AND (? OR b.whole_history OR (b.date_from<=? AND b.date_to>=?)))""", Boolean::class.java,
            promoId, request.wholeHistory, request.to, request.from)!!
        if (overlap) conflict("Период пересекается с действующей подтверждённой связью; сначала отзовите её явно")
        jdbc.update("""INSERT INTO promo_producer_bindings(id,promo_id,producer_id,revision,whole_history,date_from,date_to,verification_reference,verified_by)
            VALUES(?,?,?,?,?,?,?,?,?)""", UUID.randomUUID(), promoId, request.producerId, revision, request.wholeHistory,
            request.from, request.to, reference, actor)
        bump(promoId, request.expectedRevision, revision)
        return current(promoId)
    }

    @Transactional
    fun retract(promoId: String, request: RetractPromoProducer, actor: UUID): PromoProducerView {
        access.lockCurrentActor()
        access.requireExplicitPermission(catalog.producerBindingManage); access.requireAllPermission(catalog.producerBindingManage)
        val reference = reference(request.reference)
        if (request.expectedRevision < 0) invalid("Некорректная версия связи")
        requirePromo(promoId, true)
        val revision = expectRevision(promoId, request.expectedRevision)
        if (jdbc.queryForObject("SELECT count(*) FROM promo_producer_bindings WHERE id=? AND promo_id=?", Long::class.java,
                request.bindingId, promoId) != 1L) notFound("Связь этой кампании не найдена")
        if (jdbc.queryForObject("SELECT count(*) FROM promo_producer_binding_retractions WHERE binding_id=?", Long::class.java,
                request.bindingId) != 0L) conflict("Связь уже отозвана")
        jdbc.update("""INSERT INTO promo_producer_binding_retractions(binding_id,revision,verification_reference,retracted_by)
            VALUES(?,?,?,?)""", request.bindingId, revision, reference, actor)
        bump(promoId, request.expectedRevision, revision)
        return current(promoId)
    }

    private fun current(id: String) = PromoProducerView(id, jdbc.queryForObject(
        "SELECT coalesce((SELECT revision FROM promo_producer_binding_versions WHERE promo_id=?),0)", Long::class.java, id)!!,
        jdbc.query("""SELECT b.id,b.producer_id,p.name,b.revision,b.whole_history,b.date_from,b.date_to,b.verified_by,b.verified_at,
            r.revision AS retraction_revision,r.retracted_by,r.retracted_at FROM promo_producer_bindings b
            JOIN merch_producers p ON p.id=b.producer_id LEFT JOIN promo_producer_binding_retractions r ON r.binding_id=b.id
            WHERE b.promo_id=? ORDER BY b.revision LIMIT 50001""", { rs, _ ->
            ProducerBindingView(rs.getObject("id", UUID::class.java), rs.getObject("producer_id", UUID::class.java), rs.getString("name"),
                rs.getLong("revision"), rs.getBoolean("whole_history"), rs.getDate("date_from")?.toLocalDate(), rs.getDate("date_to")?.toLocalDate(),
                rs.getObject("verified_by", UUID::class.java), rs.getTimestamp("verified_at").toInstant(),
                rs.getObject("retraction_revision", java.lang.Long::class.java)?.toLong(), rs.getObject("retracted_by", UUID::class.java),
                rs.getTimestamp("retracted_at")?.toInstant())
        }, id).also { if (it.size > 50_000) tooLarge() })

    private fun requirePromo(id: String, lock: Boolean) {
        if (id.isBlank() || id.length > 64) invalid("Некорректный ID кампании")
        if (jdbc.queryForList("SELECT id FROM promos WHERE id=?${if (lock) " FOR UPDATE" else ""}", id).isEmpty()) notFound("Кампания не найдена")
    }
    private fun expectRevision(id: String, expected: Long): Long {
        jdbc.update("INSERT INTO promo_producer_binding_versions(promo_id,revision) VALUES(?,0) ON CONFLICT DO NOTHING", id)
        val actual = jdbc.queryForObject("SELECT revision FROM promo_producer_binding_versions WHERE promo_id=?", Long::class.java, id)!!
        if (actual != expected || expected == Long.MAX_VALUE) conflict("Связи изменились: загрузите актуальную версию")
        return expected + 1
    }
    private fun bump(id: String, before: Long, after: Long) {
        if (jdbc.update("UPDATE promo_producer_binding_versions SET revision=? WHERE promo_id=? AND revision=?", after, id, before) != 1)
            conflict("Связи изменились: загрузите актуальную версию")
    }
    private fun reference(raw: String) = raw.trim().also {
        if (it.length !in 1..1000 || it.any(Char::isISOControl)) invalid("Укажите ссылку на проверенное основание (до 1000 символов)")
    }
    private fun invalid(message: String): Nothing = throw AppException(ErrorCode.VALIDATION_FAILED, message)
    private fun conflict(message: String): Nothing = throw AppException(ErrorCode.CONFLICT, message, HttpStatus.CONFLICT)
    private fun notFound(message: String): Nothing = throw AppException(ErrorCode.NOT_FOUND, message, HttpStatus.NOT_FOUND)
    private fun forbidden(): Nothing = throw AppException(ErrorCode.FORBIDDEN, "Производитель недоступен", HttpStatus.FORBIDDEN)
    private fun tooLarge(): Nothing = throw AppException(ErrorCode.VALIDATION_FAILED, "Слишком много связей: требуется сверка HQ; результат не усечён", HttpStatus.PAYLOAD_TOO_LARGE)
}
