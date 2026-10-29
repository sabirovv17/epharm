package kz.epharm.merch.automation

import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kz.epharm.auth.access.UnifiedAuthorityLock
import kz.epharm.merch.tasks.TaskChecklistItem
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

data class AutomationDiagnostic(val branchId:UUID?,val reason:String)
data class AutomationRun(val planId:UUID,val dispatchesCreated:Int=0,val itemsCreated:Int=0,val diagnostics:List<AutomationDiagnostic> = emptyList())

/** A system derivation of approved placements, not a substitute for the administrative task-creation API. */
@Service
class WeeklyPlacementAutomation(private val jdbc:JdbcTemplate,private val named:NamedParameterJdbcTemplate,
    private val json:ObjectMapper,manager:PlatformTransactionManager,
    @Value("\${app.merch.automation.enabled:false}") private val enabled:Boolean,
    private val clock:Clock=Clock.systemUTC()) {
    private val transaction=TransactionTemplate(manager).apply {propagationBehavior=TransactionDefinition.PROPAGATION_REQUIRES_NEW}

    /** Approval callers must invoke this AFTER COMMIT, never while holding their own domain/authority locks. */
    fun generateForPlan(planId:UUID):AutomationRun {
        if(!enabled) return AutomationRun(planId,diagnostics=listOf(AutomationDiagnostic(null,"disabled")))
        return transaction.execute {
            if(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(718076,1)",Boolean::class.java)!=true)
                return@execute AutomationRun(planId,diagnostics=listOf(AutomationDiagnostic(null,"writer_busy")))
            UnifiedAuthorityLock.acquire(jdbc)
            val before=plan(planId)?:return@execute AutomationRun(planId,diagnostics=listOf(AutomationDiagnostic(null,"plan_not_found")))
            before.customer?.let {jdbc.queryForList("SELECT id FROM merch_producers WHERE id=? FOR UPDATE",it)}
            val beforeItems=items(planId)
            val branches=beforeItems.map {it.branch}.toSet()
            if(branches.isNotEmpty()) named.queryForList("SELECT id FROM merch_branches WHERE id IN (:ids) ORDER BY id FOR UPDATE",mapOf("ids" to branches))
            if(beforeItems.isNotEmpty()) named.queryForList("SELECT id FROM merch_shelves WHERE id IN (:ids) ORDER BY id FOR UPDATE",mapOf("ids" to beforeItems.map {it.shelf}.toSet()))
            val current=plan(planId,true)?:return@execute AutomationRun(planId,diagnostics=listOf(AutomationDiagnostic(null,"plan_not_found")))
            val now=clock.instant()
            if(current.status!="approved") return@execute AutomationRun(planId,diagnostics=listOf(AutomationDiagnostic(null,"plan_not_approved")))
            if(!now.isBefore(WeeklyCycleCalendar.end(current.month))) return@execute AutomationRun(planId,diagnostics=listOf(AutomationDiagnostic(null,"placement_period_ended")))
            if(current.approvalProjectionMissing) return@execute AutomationRun(planId,diagnostics=listOf(AutomationDiagnostic(null,"source_approval_projection_required")))
            val approved=items(planId)
            if(approved.map {it.id}.toSet()!=beforeItems.map {it.id}.toSet())
                return@execute AutomationRun(planId,diagnostics=listOf(AutomationDiagnostic(null,"placement_items_changed")))
            val diagnostics=mutableListOf<AutomationDiagnostic>();var created=0;var taskCount=0
            for((branch,rows) in approved.groupBy {it.branch}.toSortedMap()) {
                if(rows.any {!it.branchActive || !it.shelfActive}) {diagnostics+=AutomationDiagnostic(branch,"inactive_branch_or_shelf");continue}
                if(rows.any {!it.verifiedPharmacy}) {diagnostics+=AutomationDiagnostic(branch,"verified_pharmacy_required");continue}
                if(rows.size>500) {diagnostics+=AutomationDiagnostic(branch,"packet_limit_500");continue}
                val prior=cycles(planId,branch)
                if(prior.any {it.number==null || it.starts==null || it.projectedPlan!=planId}) {
                    diagnostics+=AutomationDiagnostic(branch,"source_cycle_projection_required");continue
                }
                val last=prior.maxByOrNull {it.number!!}
                if(last?.status=="cancelled") {diagnostics+=AutomationDiagnostic(branch,"last_cycle_cancelled");continue}
                val cycle=if(last==null) WeeklyCycleCalendar.first(current.month,current.approvedAt,now) else {
                    if(last.next==null) continue
                    if(last.due==null) {diagnostics+=AutomationDiagnostic(branch,"source_cycle_projection_required");continue}
                    WeeklyCycleCalendar.next(current.month,WeeklyCycle(last.number!!,last.starts!!,last.due,last.next,false),now)
                }?:continue
                val owner=owner(branch)
                if(owner==null) diagnostics+=AutomationDiagnostic(branch,"verified_automation_owner_required")
                create(current,branch,rows,cycle,owner,now)
                created++;taskCount+=rows.size
            }
            AutomationRun(planId,created,taskCount,diagnostics)
        }!!
    }

    /** One new cycle per branch per pass, including when the previous report has not been reviewed. */
    fun tick(limit:Int=100):List<AutomationRun> {
        require(limit in 1..100)
        if(!enabled) return emptyList()
        val now=clock.instant()
        val ids=jdbc.query("""SELECT p.id FROM merch_media_plans p WHERE p.status='approved'
            AND ?<((p.placement_month+interval '1 month')::timestamp AT TIME ZONE 'Asia/Almaty')
            AND NOT(p.source_system='MERCH_CRM' AND p.reviewed_at IS NULL AND NULLIF(p.source_payload->>'reviewed_at','') IS NOT NULL)
            AND EXISTS(SELECT 1 FROM merch_media_plan_items i JOIN merch_shelves s ON s.id=i.shelf_id JOIN merch_branches b ON b.id=s.branch_id
                WHERE i.media_plan_id=p.id AND i.status='approved' AND s.active AND b.active AND b.pharmacy_binding_verified
                  AND (SELECT count(*) FROM merch_media_plan_items size_check JOIN merch_shelves ss ON ss.id=size_check.shelf_id
                    WHERE size_check.media_plan_id=p.id AND size_check.status='approved' AND ss.branch_id=b.id)<=500
                  AND (NOT EXISTS(SELECT 1 FROM merch_task_dispatches d WHERE d.branch_id=b.id AND
                        (d.media_plan_id=p.id OR d.source_media_plan_id=p.id OR(d.source_system='MERCH_CRM' AND d.source_payload->>'media_plan_id'=p.id::text))
                        AND(d.automation_kind='weekly_placement_photo_report' OR d.source_payload->>'automation_kind'='weekly_placement_photo_report'))
                    OR EXISTS(SELECT 1 FROM LATERAL(SELECT d.status,d.next_cycle_at,d.recurring_cycle_number FROM merch_task_dispatches d
                        WHERE d.branch_id=b.id AND(d.media_plan_id=p.id OR d.source_media_plan_id=p.id OR(d.source_system='MERCH_CRM' AND d.source_payload->>'media_plan_id'=p.id::text))
                          AND(d.automation_kind='weekly_placement_photo_report' OR d.source_payload->>'automation_kind'='weekly_placement_photo_report')
                        ORDER BY d.recurring_cycle_number DESC NULLS FIRST,d.id LIMIT 1) latest
                        WHERE latest.status<>'cancelled' AND latest.recurring_cycle_number IS NOT NULL AND latest.next_cycle_at<=?)))
            ORDER BY p.updated_at,p.id LIMIT ?""",{rs,_->rs.getObject(1,UUID::class.java)},Timestamp.from(now),Timestamp.from(now),limit)
        return ids.map {id->
            try {generateForPlan(id)} catch(error:Exception) {
                // Each plan has its own transaction: one invalid source packet cannot starve unrelated pharmacies.
                AutomationRun(id,diagnostics=listOf(AutomationDiagnostic(null,"generation_failed_${error.javaClass.simpleName}")))
            }
        }
    }

    private fun plan(id:UUID,lock:Boolean=false):Plan?=jdbc.query("""SELECT p.id,p.status,p.producer_id,p.placement_month,p.reviewed_at,p.advertiser_name,
        (p.source_system='MERCH_CRM' AND p.reviewed_at IS NULL AND NULLIF(p.source_payload->>'reviewed_at','') IS NOT NULL) AS approval_missing
        FROM merch_media_plans p WHERE id=? ${if(lock) "FOR UPDATE" else ""}""",{rs,_->Plan(rs.getObject("id",UUID::class.java),rs.getString("status"),rs.getObject("producer_id",UUID::class.java),
        rs.getObject("placement_month",LocalDate::class.java),rs.getTimestamp("reviewed_at")?.toInstant(),rs.getString("advertiser_name"),rs.getBoolean("approval_missing"))},id).singleOrNull()
    private fun items(id:UUID):List<Item> = jdbc.query("""SELECT i.id,i.shelf_id,i.sale_portion,i.client_photo_ref,s.branch_id,s.code,s.active AS shelf_active,
        s.first_photo_ref,s.source_payload->>'template_image_url' AS template_ref,b.active AS branch_active,b.pharmacy_binding_verified,b.epharm_pharmacy_id
        FROM merch_media_plan_items i JOIN merch_shelves s ON s.id=i.shelf_id JOIN merch_branches b ON b.id=s.branch_id
        WHERE i.media_plan_id=? AND i.status='approved' ORDER BY s.branch_id,s.id,i.id""",{rs,_->Item(rs.getObject("id",UUID::class.java),rs.getObject("shelf_id",UUID::class.java),
        rs.getObject("branch_id",UUID::class.java),rs.getString("code"),rs.getString("sale_portion")?:"whole",rs.getBoolean("shelf_active"),rs.getBoolean("branch_active"),
        rs.getBoolean("pharmacy_binding_verified") && !rs.getString("epharm_pharmacy_id").isNullOrBlank(),rs.getString("client_photo_ref"),rs.getString("first_photo_ref"),rs.getString("template_ref"))},id)
    private fun cycles(plan:UUID,branch:UUID):List<ExistingCycle> = jdbc.query("""SELECT d.recurring_cycle_number,d.cycle_starts_at,d.due_at,d.next_cycle_at,d.status,d.media_plan_id
        FROM merch_task_dispatches d WHERE d.branch_id=? AND
          (d.media_plan_id=? OR d.source_media_plan_id=? OR(d.source_system='MERCH_CRM' AND d.source_payload->>'media_plan_id'=?))
          AND(d.automation_kind='weekly_placement_photo_report' OR d.source_payload->>'automation_kind'='weekly_placement_photo_report')
        ORDER BY d.recurring_cycle_number NULLS LAST,d.id FOR UPDATE""",{rs,_->ExistingCycle(rs.getObject("recurring_cycle_number",Int::class.javaObjectType),
        rs.getTimestamp("cycle_starts_at")?.toInstant(),rs.getTimestamp("due_at")?.toInstant(),rs.getTimestamp("next_cycle_at")?.toInstant(),rs.getString("status"),rs.getObject("media_plan_id",UUID::class.java))},
        branch,plan,plan,plan.toString())
    private fun owner(branch:UUID):Owner?=jdbc.query("""SELECT registry.source_owner_id,registry.admin_account_id FROM merch_automation_branch_owners registry
        WHERE registry.branch_id=? AND ${AutomationOwnerEligibility.sql("registry.branch_id")}""",{rs,_->Owner(rs.getObject(1,UUID::class.java),rs.getObject(2,UUID::class.java))},branch).singleOrNull()
    private fun create(plan:Plan,branch:UUID,items:List<Item>,cycle:WeeklyCycle,owner:Owner?,now:Instant) {
        val dispatch=UUID.randomUUID()
        val label=if(cycle.preparation) "Подготовка размещения · " else "Еженедельный фотоотчёт · "
        val title=(label+(plan.advertiser?:"Размещение")+" · неделя ${cycle.number}").take(160)
        jdbc.update("""INSERT INTO merch_task_dispatches(id,source_system,source_origin,source_id,source_payload,branch_id,title,status,priority,task_type,
            available_from,due_at,reward_amount_kzt,source_mass_owner_id,media_plan_id,source_media_plan_id,automation_kind,recurring_cycle_number,cycle_starts_at,next_cycle_at,
            native_automation_generated,automation_owner_admin_id,automation_blocked_reason,assignment_ready_at,sla_started_at,created_at,updated_at)
            VALUES(?,'EPHARM','https://epharm.inkar.kz',?,CAST(? AS jsonb),?,?,'ready','normal','photo_report',?,?,0,?,?,?,'weekly_placement_photo_report',?,?,?,true,?,?,?,?,?,?)""",
            dispatch,dispatch,json.writeValueAsString(mapOf("automation" to "weekly_placement_photo_report","mediaPlanId" to plan.id,"cycleNumber" to cycle.number)),branch,title,
            Timestamp.from(cycle.startsAt),Timestamp.from(cycle.dueAt),owner?.sourceOwner,plan.id,plan.id,cycle.number,Timestamp.from(cycle.startsAt),cycle.nextAt?.let(Timestamp::from),
            owner?.admin,if(owner==null) "Не назначен подтверждённый ответственный МАС" else null,if(owner!=null) Timestamp.from(now) else null,Timestamp.from(cycle.startsAt),Timestamp.from(now),Timestamp.from(now))
        for(item in items) {
            val id=UUID.randomUUID()
            val checklist=listOf("Сверить код шкафа и полки с заданием","Проверить целостность и соответствие выкладки","Убедиться, что товар и ценники хорошо видны")
                .map {TaskChecklistItem(UUID.randomUUID(),it)}
            val portion=when(item.portion){"left"->"левая половина";"right"->"правая половина";else->"целая полка"}
            val reference=referenceImage(item)
            val privateReference=jdbc.query("SELECT id FROM merch_placement_reference_photos WHERE media_plan_item_id=? AND archived_at IS NULL",
                {rs,_->rs.getObject(1,UUID::class.java)},item.id).singleOrNull()
            jdbc.update("""INSERT INTO merch_task_items(id,dispatch_id,source_id,source_payload,title,instructions,task_type,status,required,required_photo_count,checklist,
                available_from,due_at,media_plan_item_id,shelf_id,recurring_cycle_number,automation_kind,reference_image_ref,created_at,updated_at,client_reference_photo_id)
                VALUES(?,?,?,CAST(? AS jsonb),?,?,'photo_report','assigned',true,1,CAST(? AS jsonb),?,?,?,?,?,'weekly_placement_photo_report',?,?,?,?)""",
                id,dispatch,id,json.writeValueAsString(mapOf("mediaPlanItemId" to item.id,"shelfId" to item.shelf,"cycleNumber" to cycle.number)),
                "Фотоотчёт: полка ${item.code}".take(240),"Кампания «${plan.advertiser?:"Размещение"}». Сверьте код шкафа и полки, проверьте выкладку и прикрепите актуальную фотографию всей полки. Полка ${item.code} ($portion).",
                json.writeValueAsString(checklist),Timestamp.from(cycle.startsAt),Timestamp.from(cycle.dueAt),item.id,item.shelf,cycle.number,reference,Timestamp.from(now),Timestamp.from(now),privateReference)
            jdbc.update("""INSERT INTO merch_task_events(id,dispatch_id,item_id,event_type,details,created_at)
                VALUES(?,?,?,'weekly_photo_report_generated',CAST(? AS jsonb),?)""",UUID.randomUUID(),dispatch,id,
                json.writeValueAsString(mapOf("mediaPlanId" to plan.id,"mediaPlanItemId" to item.id,"cycleNumber" to cycle.number)),Timestamp.from(now))
        }
    }
    private fun referenceImage(item:Item):String {
        // The materializer projects the exact first source photo; this worker never reads a staging schema or fetches a URL.
        return listOfNotNull(item.clientPhoto,item.firstShelfPhoto,item.template,"/pharmacy-shelf.png").first(::trustedReference)
    }
    internal fun trustedReference(value:String):Boolean = runCatching {
        if(value.length !in 1..2048 || value.any {it.code<32} || value.contains('\\')) return@runCatching false
        val uri=URI.create(value)
        if(uri.rawQuery!=null || uri.rawFragment!=null || uri.userInfo!=null || uri.port !in setOf(-1,443)) return@runCatching false
        if(uri.rawPath.split('/').any {it==".." || it=="."} || uri.rawPath.contains('%')) return@runCatching false
        val path=uri.rawPath
        val allowed=path=="/pharmacy-shelf.png" || path.matches(Regex("/api/(request-item-files|placement-files)/[A-Za-z0-9_./-]+"))
        allowed && (if(uri.isAbsolute) uri.scheme=="https" && uri.host in setOf("crm.inkar.kz","epharm.inkar.kz") else value.startsWith('/') && !value.startsWith("//"))
    }.getOrDefault(false)
    private data class Plan(val id:UUID,val status:String,val customer:UUID?,val month:LocalDate,val approvedAt:Instant?,val advertiser:String?,val approvalProjectionMissing:Boolean)
    private data class Item(val id:UUID,val shelf:UUID,val branch:UUID,val code:String,val portion:String,val shelfActive:Boolean,val branchActive:Boolean,val verifiedPharmacy:Boolean,
        val clientPhoto:String?,val firstShelfPhoto:String?,val template:String?)
    private data class ExistingCycle(val number:Int?,val starts:Instant?,val due:Instant?,val next:Instant?,val status:String,val projectedPlan:UUID?)
    private data class Owner(val sourceOwner:UUID?,val admin:UUID?)
}
