package kz.epharm.merch.delivery

import java.security.SecureRandom
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Base64
import java.util.UUID
import kz.epharm.merchtasks.dto.MerchTaskDeliveryResult
import kz.epharm.merch.automation.AutomationOwnerEligibility
import kz.epharm.merchtasks.dto.MerchTaskDto
import kz.epharm.merchtasks.dto.MerchTaskEnvelope
import kz.epharm.merchtasks.dto.MerchTaskShownRequest
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Only issue new native links when enabled; an issued link remains valid across a flag rollback. */
@Service
class NativeMerchTaskDeliveryService(
    private val jdbc: JdbcTemplate,
    private val capabilities: NativeCapabilityAuthorization,
    @Value("\${app.merch.native-delivery-enabled:false}") private val enabled: Boolean,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional
    fun activeTask(pharmacyId: String): MerchTaskEnvelope {
        if (!pharmacyId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) throw AppException(
            ErrorCode.VALIDATION_FAILED,"Некорректный идентификатор аптеки",HttpStatus.BAD_REQUEST)
        if (!enabled) return MerchTaskEnvelope()
        var now=clock.instant()
        val branch=jdbc.query("""SELECT id FROM merch_branches WHERE active AND pharmacy_binding_verified
            AND epharm_pharmacy_id=? ORDER BY id LIMIT 1""",{rs,_->rs.getObject(1,UUID::class.java)},pharmacyId).singleOrNull()
            ?:return MerchTaskEnvelope()
        val id=jdbc.query("""SELECT d.id FROM merch_task_dispatches d WHERE d.branch_id=?
            AND ${NativeDeliveryEligibility.sql("d")}
            ORDER BY CASE WHEN d.status='revision_required' THEN 0 ELSE 1 END,
              CASE d.priority WHEN 'urgent' THEN 0 WHEN 'high' THEN 1 WHEN 'normal' THEN 2 ELSE 3 END,
              d.due_at NULLS LAST,d.created_at DESC,d.id LIMIT 1""",{rs,_->rs.getObject(1,UUID::class.java)},branch,Timestamp.from(now),Timestamp.from(now),Timestamp.from(now))
            .singleOrNull()?:return MerchTaskEnvelope()
        // The same dispatch lock order is shared with task reviews and the signed legacy reward callback.
        capabilities.lockDispatch(id)
        now=clock.instant()
        val row=capabilities.dispatch(id,now)?:return MerchTaskEnvelope()
        if(row.branchId!=branch || !row.branchActive || !row.bindingVerified || row.pharmacyId!=pharmacyId ||
            !row.editable || !row.deliveryEligible) return MerchTaskEnvelope()
        val dueAt=if(row.automationKind=="weekly_placement_photo_report" && row.dueAt==null) {
            val monthEnd=row.placementMonth?.plusMonths(1)?.atStartOfDay(ZoneId.of("Asia/Almaty"))?.toInstant()
            minOf(now.plusSeconds(7*86400L),monthEnd?:now.plusSeconds(7*86400L))
        } else row.dueAt
        if(dueAt!=null && !dueAt.isAfter(now)) return MerchTaskEnvelope()
        jdbc.update("""UPDATE merch_task_links SET revoked_at=? WHERE dispatch_id=? AND native_owned
            AND revoked_at IS NULL AND submitted_at IS NULL AND expires_at<=?""",Timestamp.from(now),id,Timestamp.from(now))
        var link=jdbc.query("""SELECT id,integration_access_token,expires_at,token_hash FROM merch_task_links
            WHERE dispatch_id=? AND native_owned AND issued_channel='pharmapay_posm'
              AND integration_access_token IS NOT NULL AND revoked_at IS NULL AND submitted_at IS NULL AND expires_at>?
            ORDER BY created_at DESC,id DESC LIMIT 1 FOR UPDATE""",{rs,_->DeliveryLink(rs.getObject(1,UUID::class.java),rs.getString(2),rs.getTimestamp(3).toInstant(),rs.getString(4))},id,Timestamp.from(now)).singleOrNull()
        if(link!=null && (!NativeCapabilityAuthorization.validToken(link.token) || link.hash!=NativeCapabilityAuthorization.tokenHash(link.token)))
            return MerchTaskEnvelope(available=false)
        if(link==null) {
            val token=Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(36).also(random::nextBytes))
            val expires=maxOf(now.plusSeconds(7*86400L),dueAt?.plusSeconds(48*3600L)?:now)
            link=DeliveryLink(UUID.randomUUID(),token,expires,NativeCapabilityAuthorization.tokenHash(token))
            jdbc.update("""INSERT INTO merch_task_links(id,dispatch_id,token_hash,integration_access_token,
                issued_channel,expires_at,native_owned,created_at,source_payload) VALUES(?,?,?,?,'pharmapay_posm',?,true,?,'{}')""",
                link.id,id,link.hash,token,Timestamp.from(expires),Timestamp.from(now))
            jdbc.update("""INSERT INTO merch_task_events(id,dispatch_id,event_type,details,created_at)
                VALUES(?,?,'dispatch_link_issued',CAST(? AS jsonb),?)""",UUID.randomUUID(),id,
                """{"linkId":"${link.id}","channel":"pharmapay_posm"}""",Timestamp.from(now))
        }
        jdbc.update("""UPDATE merch_task_dispatches SET status=CASE WHEN status='ready' THEN 'sent' ELSE status END,
            sent_at=COALESCE(sent_at,?),assignment_ready_at=COALESCE(assignment_ready_at,?),
            sla_started_at=COALESCE(sla_started_at,?),due_at=?,updated_at=? WHERE id=?""",
            Timestamp.from(now),Timestamp.from(now),Timestamp.from(now),dueAt?.let(Timestamp::from),Timestamp.from(now),id)
        if(dueAt!=row.dueAt) jdbc.update("UPDATE merch_task_items SET due_at=? WHERE dispatch_id=? AND status NOT IN ('completed','cancelled')",dueAt?.let(Timestamp::from),id)
        return MerchTaskEnvelope(MerchTaskDto(id.toString(),row.title,row.priority,dueAt,link.expires,
            "https://epharm.inkar.kz/tasks/staff?task=1#task=${link.token}",link.token))
    }

    /** Existence, not validity: a revoked native link must never fall through to another runtime. */
    @Transactional(readOnly=true)
    fun ownsNativeDeliveryToken(rawToken:String):Boolean=capabilities.ownsNativeDeliveryToken(rawToken)

    @Transactional
    fun markShown(request:MerchTaskShownRequest):MerchTaskDeliveryResult {
        if(!request.pharmacyId.matches(Regex("[A-Za-z0-9_-]{1,128}")) || request.deviceId.isBlank() ||
            request.deviceId.length>128 || request.deviceId.any {it.code<32}) return MerchTaskDeliveryResult()
        val dispatch=runCatching {UUID.fromString(request.dispatchId)}.getOrNull()?:return MerchTaskDeliveryResult()
        val cap=capabilities.forAcknowledgement(request.deliveryToken,dispatch,request.pharmacyId)?:return MerchTaskDeliveryResult()
        jdbc.update("""INSERT INTO merch_task_delivery_receipts(link_id,dispatch_id,device_id,shown_at)
            VALUES(?,?,?,?) ON CONFLICT(link_id,device_id) DO NOTHING""",cap.linkId,cap.dispatchId,request.deviceId,Timestamp.from(clock.instant()))
        return MerchTaskDeliveryResult(accepted=true)
    }
    private data class DeliveryLink(val id:UUID,val token:String,val expires:Instant,val hash:String)
    companion object { private val random=SecureRandom() }
}

internal object NativeDeliveryEligibility {
    /** Parameters in order: now, now, now. A missing verified source projection fails closed. */
    fun sql(alias:String)="""$alias.status IN ('ready','sent','in_progress','revision_required')
        AND ($alias.due_at IS NULL OR $alias.due_at>?)
        AND ($alias.available_from IS NULL OR $alias.available_from<=?)
        AND NOT EXISTS(SELECT 1 FROM merch_task_items future WHERE future.dispatch_id=$alias.id
          AND future.status NOT IN ('completed','cancelled') AND future.available_from>?)
        AND (($alias.native_automation_generated AND ${AutomationOwnerEligibility.sql("$alias.branch_id")}) OR
          (NOT $alias.native_automation_generated AND ($alias.source_mass_owner_id IS NOT NULL OR ($alias.source_system='EPHARM' AND $alias.created_by IS NOT NULL)
          OR EXISTS(SELECT 1 FROM merch_task_items training WHERE training.dispatch_id=$alias.id AND training.training_material_id IS NOT NULL))))
        AND ($alias.source_system<>'MERCH_CRM' OR NULLIF($alias.source_payload->>'media_plan_id','') IS NULL OR $alias.source_media_plan_id IS NOT NULL)
        AND ($alias.source_media_plan_id IS NULL OR $alias.media_plan_id=$alias.source_media_plan_id)
        AND ($alias.media_plan_id IS NULL OR EXISTS(SELECT 1 FROM merch_media_plans plan WHERE plan.id=$alias.media_plan_id AND plan.status='approved'))
        AND EXISTS(SELECT 1 FROM merch_task_items task WHERE task.dispatch_id=$alias.id AND task.status NOT IN ('completed','cancelled'))"""
}
