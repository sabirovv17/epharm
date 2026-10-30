package kz.epharm.merch.delivery

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/** A capability proves access to one dispatch, never the identity of a pharmacist or an administrator. */
class AuthorizedTaskCapability internal constructor(
    val linkId:UUID,val dispatchId:UUID,val branchId:UUID,val rewardAmountKzt:Long,
    val expiresAt:Instant,val readOnly:Boolean,val identityRequired:Boolean,
)

internal data class DeliveryDispatch(
    val id:UUID,val branchId:UUID,val pharmacyId:String?,val branchActive:Boolean,val bindingVerified:Boolean,
    val title:String,val priority:String,val status:String,val dueAt:Instant?,val reward:Long,
    val editable:Boolean,val deliveryEligible:Boolean,val parentApproved:Boolean,
    val automationKind:String?,val placementMonth:LocalDate?,val identityRequired:Boolean,
)

@Service
class NativeCapabilityAuthorization(private val jdbc:JdbcTemplate,private val clock:Clock=Clock.systemUTC(),
    @Value("\${app.merch.legacy-portal-cutover-enabled:false}") private val legacyCutover:Boolean=false) {
    @Transactional(readOnly=true)
    fun ownsNativeDeliveryToken(rawToken:String):Boolean {
        if(!validToken(rawToken)) return false
        return jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM merch_task_links WHERE token_hash=? AND native_owned AND issued_channel='pharmapay_posm')",
            Boolean::class.java,tokenHash(rawToken))==true
    }

    @Transactional(readOnly=true)
    fun authorize(rawToken:String,requireEditable:Boolean=false):AuthorizedTaskCapability =
        load(rawToken,false)?.also {if(requireEditable && it.readOnly) conflict() }?:unauthorized()

    /** Must be called first inside the task mutation transaction; do not carry this value to another transaction. */
    @Transactional(propagation=Propagation.MANDATORY)
    fun requireFreeMutation(rawToken:String):AuthorizedTaskCapability {
        val cap=load(rawToken,true)?:unauthorized()
        if(cap.readOnly) conflict()
        if(cap.identityRequired) throw AppException(ErrorCode.FORBIDDEN,
            "Для этого задания подтвердите исполнителя через ePharm",HttpStatus.FORBIDDEN)
        return cap
    }

    /** Paid access still needs a separately verified executor; this only locks and validates the task capability. */
    @Transactional(propagation=Propagation.MANDATORY)
    fun requirePaidMutation(rawToken:String):AuthorizedTaskCapability {
        val cap=load(rawToken,true)?:unauthorized()
        if(cap.readOnly) conflict()
        if(cap.rewardAmountKzt<=0L) throw AppException(ErrorCode.FORBIDDEN,
            "Подтверждение исполнителя доступно только для оплачиваемого задания",HttpStatus.FORBIDDEN)
        return cap
    }

    internal fun lockDispatch(id:UUID) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,817105))",id.toString())
        jdbc.query("SELECT id FROM merch_task_dispatches WHERE id=? FOR UPDATE",{rs,_->rs.getObject(1,UUID::class.java)},id)
    }

    /** Called only by the authenticated POSM gateway; invalid owned links return false, never an upstream fallback. */
    internal fun forAcknowledgement(rawToken:String,dispatchId:UUID,pharmacyId:String):AuthorizedTaskCapability? {
        val cap=load(rawToken,true)?:return null
        if(cap.dispatchId!=dispatchId || cap.readOnly) return null
        val dispatch=dispatch(cap.dispatchId,clock.instant())?:return null
        if(dispatch.pharmacyId!=pharmacyId || !dispatch.deliveryEligible) return null
        return cap
    }

    private fun load(rawToken:String,lock:Boolean):AuthorizedTaskCapability? {
        if(!validToken(rawToken)) return null
        val hash=tokenHash(rawToken)
        val accepted="(l.native_owned AND l.issued_channel='pharmapay_posm') OR (${if(legacyCutover) "true" else "false"} AND ${legacyProofSql()})"
        val id=jdbc.query("""SELECT l.dispatch_id FROM merch_task_links l JOIN merch_task_dispatches d ON d.id=l.dispatch_id
            WHERE l.token_hash=? AND ($accepted)""",
            {rs,_->rs.getObject(1,UUID::class.java)},hash).singleOrNull()?:return null
        if(lock) lockDispatch(id)
        val link=jdbc.query("""SELECT l.id,l.expires_at,l.revoked_at,l.submitted_at,
              (${if(legacyCutover) "true" else "false"} AND ${legacyProofSql()}) AS verified_legacy,
              l.source_payload->>'submitted_at' IS NOT NULL AS source_submitted
            FROM merch_task_links l JOIN merch_task_dispatches d ON d.id=l.dispatch_id
            WHERE l.token_hash=? AND l.dispatch_id=? AND ($accepted) ${if(lock) "FOR UPDATE OF l" else ""}""",
            {rs,_->Link(rs.getObject(1,UUID::class.java),rs.getTimestamp(2).toInstant(),rs.getTimestamp(3)!=null,
                rs.getTimestamp(4)!=null || rs.getBoolean(5) && rs.getBoolean(6),rs.getBoolean(5))},hash,id)
            .singleOrNull()?:return null
        val now=clock.instant()
        if(link.revoked || !link.expires.isAfter(now)) return null
        val dispatch=dispatch(id,now)?:return null
        if(!dispatch.branchActive || !dispatch.bindingVerified || dispatch.pharmacyId.isNullOrBlank() ||
            dispatch.status=="cancelled" || !dispatch.parentApproved) return null
        // The source accepts free late reports until capability expiry; a paid deadline is never extended.
        val lateFree=link.verifiedLegacy && dispatch.reward==0L && !dispatch.identityRequired && legacyEditable(id,now)
        return AuthorizedTaskCapability(link.id,id,dispatch.branchId,dispatch.reward,link.expires,
            link.submitted || !dispatch.editable || !(dispatch.deliveryEligible || lateFree),dispatch.identityRequired)
    }

    private fun legacyProofSql()="""EXISTS(SELECT 1 FROM merch_legacy_capability_aliases a
        WHERE a.link_id=l.id AND a.dispatch_id=l.dispatch_id AND a.branch_id=d.branch_id
          AND a.token_hash=l.token_hash AND a.source_channel IS NOT DISTINCT FROM l.issued_channel
          AND a.source_expires_at=l.expires_at
          AND a.source_payload_sha256=encode(sha256(convert_to(l.source_payload::text,'UTF8')),'hex')
          AND d.source_system='MERCH_CRM' AND d.source_origin='https://crm.inkar.kz'
          AND d.source_id=d.id AND d.source_payload->>'id'=d.id::text
          AND d.source_payload->>'branch_id'=a.branch_id::text AND l.source_payload->>'id'=l.id::text
          AND l.source_payload->>'dispatch_id'=d.id::text
          AND l.source_payload->>'token_hash'=l.token_hash
          AND l.source_payload->>'revoked_at' IS NULL
          AND EXISTS(SELECT 1 FROM unified_import_runs r
            WHERE r.operation_key='owned_merch_legacy_caps:'||substring(a.source_artifact_sha256,1,24)
              AND r.source_sha256=a.source_artifact_sha256 AND r.identity_sha256=a.source_artifact_sha256))"""

    private fun legacyEditable(id:UUID,now:Instant)=jdbc.queryForObject("""SELECT EXISTS(SELECT 1 FROM merch_task_dispatches d
        WHERE d.id=? AND (d.available_from IS NULL OR d.available_from<=?)
          AND NOT EXISTS(SELECT 1 FROM merch_task_items i WHERE i.dispatch_id=d.id
            AND i.status NOT IN ('completed','cancelled') AND i.available_from>?)
          AND EXISTS(SELECT 1 FROM merch_task_items i WHERE i.dispatch_id=d.id AND i.status NOT IN ('completed','cancelled')))""",
        Boolean::class.java,id,Timestamp.from(now),Timestamp.from(now))==true

    internal fun dispatch(id:UUID,now:Instant):DeliveryDispatch?=jdbc.query("""SELECT d.*,b.epharm_pharmacy_id,
        (b.active AND org.active) AS branch_active,b.pharmacy_binding_verified,p.placement_month,
        (d.reward_amount_kzt>0 OR EXISTS(SELECT 1 FROM merch_monthly_visits visit WHERE visit.dispatch_id=d.id)) AS identity_required,
        (${NativeDeliveryEligibility.sql("d")}) AS delivery_eligible,
        ((d.source_system<>'MERCH_CRM' OR NULLIF(d.source_payload->>'media_plan_id','') IS NULL OR d.source_media_plan_id IS NOT NULL)
          AND (d.source_media_plan_id IS NULL OR d.media_plan_id=d.source_media_plan_id)
          AND (d.media_plan_id IS NULL OR p.status='approved')) AS parent_approved
        FROM merch_task_dispatches d JOIN merch_branches b ON b.id=d.branch_id
        JOIN merch_organizations org ON org.id=b.organization_id
        LEFT JOIN merch_media_plans p ON p.id=d.media_plan_id WHERE d.id=?""",{rs,_->
        val status=rs.getString("status")
        DeliveryDispatch(rs.getObject("id",UUID::class.java),rs.getObject("branch_id",UUID::class.java),rs.getString("epharm_pharmacy_id"),
            rs.getBoolean("branch_active"),rs.getBoolean("pharmacy_binding_verified"),rs.getString("title"),rs.getString("priority"),status,
            rs.getTimestamp("due_at")?.toInstant(),rs.getLong("reward_amount_kzt"),status in setOf("ready","sent","in_progress","revision_required"),
            rs.getBoolean("delivery_eligible"),rs.getBoolean("parent_approved"),rs.getString("automation_kind"),rs.getDate("placement_month")?.toLocalDate(),
            rs.getBoolean("identity_required"))
    },Timestamp.from(now),Timestamp.from(now),Timestamp.from(now),id).singleOrNull()

    private data class Link(val id:UUID,val expires:Instant,val revoked:Boolean,val submitted:Boolean,val verifiedLegacy:Boolean)
    private fun unauthorized():Nothing=throw AppException(ErrorCode.UNAUTHORIZED,"Ссылка недействительна или срок её действия истёк",HttpStatus.UNAUTHORIZED)
    private fun conflict():Nothing=throw AppException(ErrorCode.CONFLICT,"Задание сейчас недоступно для изменения",HttpStatus.CONFLICT)
    companion object {
        fun validToken(token:String)=token.length in 32..256 && token.matches(Regex("[A-Za-z0-9_-]+"))
        fun tokenHash(token:String):String=MessageDigest.getInstance("SHA-256")
            .digest("task-dispatch:$token".toByteArray(StandardCharsets.UTF_8)).joinToString(""){"%02x".format(it)}
    }
}
