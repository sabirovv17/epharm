package kz.epharm.merch.tasks

import kz.epharm.merch.placements.repository.PlacementRepository
import kz.epharm.merch.staffaccess.StaffAccessContext
import kz.epharm.merch.staffaccess.StaffAccessService
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** A shared code proves a branch/month scope, never a person's identity or a reward recipient. */
@Service
class StaffVisitService(private val jdbc:JdbcTemplate,private val sessions:StaffAccessService,
    private val planning:TaskPlanningService,private val tasks:MerchTaskService,private val placements:PlacementRepository) {

    @Transactional(readOnly=true)
    fun workspace(rawSession:String,month:LocalDate?=null,limit:Int=50,offset:Int=0):StaffWorkspaceDto {
        val ctx=sessions.requireSession(rawSession)
        requireMonth(ctx,month?:ctx.placementMonth)
        if(limit !in 1..50 || offset !in 0..1_000_000) invalid("Некорректная страница аптек")
        if(ctx.branchIds.isEmpty()) forbidden()
        val organization=jdbc.query("SELECT id,name FROM merch_organizations WHERE id=? AND active",
            {rs,_->StaffWorkspaceOrganization(rs.uuid("id"),rs.getString("name"))},ctx.organizationId).singleOrNull()?:forbidden()
        val args=mutableListOf<Any>(ctx.placementMonth,ctx.placementMonth,ctx.organizationId)
        args.addAll(ctx.branchIds.sorted());args+=limit+1;args+=offset
        val branches=jdbc.query("""SELECT b.id,b.code,b.name,b.address,b.city,v.id AS visit_id,d.status,v.submitted_at,
            (SELECT count(*) FROM merch_media_plan_items pi JOIN merch_media_plans p ON p.id=pi.media_plan_id
              JOIN merch_shelves s ON s.id=pi.shelf_id WHERE s.branch_id=b.id AND pi.placement_month=?
                AND pi.status='approved' AND p.status='approved') AS required_count,
            (SELECT count(DISTINCT i.id) FROM merch_task_items i JOIN merch_task_attempts a ON a.item_id=i.id AND a.attempt_no=i.current_attempt
              JOIN merch_task_evidence e ON e.attempt_id=a.id AND e.item_id=i.id
              WHERE i.dispatch_id=v.dispatch_id AND a.staff_visit_id=v.id AND e.metadata->>'captureSource'='web_camera') AS photo_count,
            (SELECT r.reason FROM merch_task_reviews r JOIN merch_task_items i ON i.id=r.item_id
              WHERE i.dispatch_id=v.dispatch_id AND i.status='revision_required' AND r.decision='revision'
              ORDER BY r.created_at DESC,r.id DESC LIMIT 1) AS reason
            FROM merch_branches b LEFT JOIN merch_staff_visits v ON v.branch_id=b.id AND v.placement_month=?
            LEFT JOIN merch_task_dispatches d ON d.id=v.dispatch_id
            WHERE b.organization_id=? AND b.active AND b.id IN (${ctx.branchIds.joinToString(","){"?"}})
            ORDER BY b.city,b.code,b.id LIMIT ? OFFSET ?""",{rs,_->
            val count=rs.getInt("required_count");val status=rs.getString("status")
            StaffWorkspaceBranch(rs.uuid("id"),rs.getString("code"),rs.getString("name"),rs.getString("address"),rs.getString("city"),
                count,rs.getObject("visit_id",UUID::class.java),status,rs.getInt("photo_count"),rs.getTimestamp("submitted_at")?.toInstant(),
                rs.getString("reason"),!ctx.readOnly && count>0 && status !in setOf("submitted","completed","cancelled"))
        },*args.toTypedArray())
        return StaffWorkspaceDto(ctx.placementMonth,organization,ctx.deadlineAt,ctx.readOnly,branches.take(limit),limit,offset,branches.size>limit)
    }

    @Transactional
    fun start(rawSession:String,request:StartStaffVisit):StaffVisitDto {
        val ctx=sessions.requireSession(rawSession,mutation=true)
        requireMonth(ctx,request.month);val name=name(request.employeeName)
        branch(ctx,request.branchId,true)
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,817111))","${request.branchId}:${request.month}")
        val prior=jdbc.query("SELECT id FROM merch_staff_visits WHERE branch_id=? AND placement_month=?",
            {rs,_->rs.uuid("id")},request.branchId,request.month).singleOrNull()
        if(prior!=null) {
            val visit=visit(ctx,prior,true);currentTargets(visit)
            val d=tasks.staffVisitDispatch(prior,true)
            if(d.status in setOf("submitted","completed","cancelled")) conflict("Фотоотчёт уже отправлен или закрыт")
            jdbc.update("UPDATE merch_staff_visits SET employee_name=?,updated_at=now() WHERE id=?",name,prior)
            audit(ctx,visit,"staff_visit_resumed")
            return dto(ctx,visit.copy(employeeName=name))
        }
        val targets=planning.sharedVisitTargets(request.branchId,request.month)
        val dispatch=planning.createSharedVisit(request.branchId,request.month,targets)
        val id=UUID.randomUUID()
        jdbc.update("""INSERT INTO merch_staff_visits(id,branch_id,placement_month,dispatch_id,employee_name,created_at,updated_at)
            VALUES(?,?,?,?,?,now(),now())""",id,request.branchId,request.month,dispatch,name)
        val visit=visit(ctx,id)
        audit(ctx,visit,"staff_visit_opened")
        return dto(ctx,visit)
    }

    @Transactional(readOnly=true)
    fun get(rawSession:String,id:UUID):StaffVisitDto {
        val ctx=sessions.requireSession(rawSession)
        return dto(ctx,visit(ctx,id))
    }

    @Transactional
    fun upload(rawSession:String,id:UUID,itemId:UUID,uploadId:UUID,file:MultipartFile):MerchTaskEvidenceDto {
        val ctx=sessions.requireSession(rawSession,mutation=true)
        val visit=visit(ctx,id,true);currentTargets(visit)
        val evidence=tasks.uploadStaffVisit(id,itemId,visit.employeeName,uploadId,file)
        audit(ctx,visit,"staff_camera_uploaded",itemId)
        return evidence
    }

    @Transactional
    fun submit(rawSession:String,id:UUID,request:SubmitStaffVisit):StaffVisitDto {
        val ctx=sessions.requireSession(rawSession,mutation=true)
        val visit=visit(ctx,id,true);currentTargets(visit)
        val name=name(request.employeeName)
        if(request.notes.length>1000) invalid("Комментарий не должен превышать 1000 символов")
        tasks.submitStaffVisit(id,request.expectedVersion,name,request.notes.trim())
        audit(ctx,visit,"staff_visit_submitted")
        return dto(ctx,visit(ctx,id))
    }

    @Transactional(readOnly=true)
    fun evidence(rawSession:String,id:UUID,evidenceId:UUID):Pair<MerchTaskEvidenceDto,ByteArray> {
        val ctx=sessions.requireSession(rawSession);visit(ctx,id)
        return tasks.downloadStaffVisit(id,evidenceId)
    }

    @Transactional(readOnly=true)
    fun reference(rawSession:String,id:UUID,itemId:UUID):Pair<MerchTaskEvidenceDto,ByteArray> {
        val ctx=sessions.requireSession(rawSession);visit(ctx,id)
        return tasks.referenceStaffVisit(id,itemId)
    }

    private fun branch(ctx:StaffAccessContext,id:UUID,lock:Boolean=false) {
        if(id !in ctx.branchIds) forbidden()
        if(lock) placements.lock(setOf(id),emptySet())
        if(jdbc.queryForObject("""SELECT count(*) FROM merch_branches b JOIN merch_organizations o ON o.id=b.organization_id
            WHERE b.id=? AND b.organization_id=? AND b.active AND o.active""",Int::class.java,id,ctx.organizationId)!=1) forbidden()
    }

    private fun visit(ctx:StaffAccessContext,id:UUID,lock:Boolean=false):Visit {
        val row=jdbc.query("SELECT * FROM merch_staff_visits WHERE id=?",{rs,_->Visit(rs.uuid("id"),rs.uuid("branch_id"),
            rs.getDate("placement_month").toLocalDate(),rs.uuid("dispatch_id"),rs.getString("employee_name"),rs.getString("notes"),
            rs.getTimestamp("submitted_at")?.toInstant())},id).singleOrNull()?:notFound()
        requireMonth(ctx,row.month);branch(ctx,row.branchId,lock)
        return row
    }

    private fun currentTargets(visit:Visit) {
        val expected=planning.sharedVisitTargets(visit.branchId,visit.month).map{it.id}.toSet()
        val actual=jdbc.query("SELECT media_plan_item_id FROM merch_task_items WHERE dispatch_id=?",
            {rs,_->rs.getObject(1,UUID::class.java)},visit.dispatchId)
        if(expected.isEmpty() || actual.size!=expected.size || actual.any{it==null} || actual.toSet()!=expected)
            conflict("Состав подтверждённых размещений изменился. Фотографии сохранены; обратитесь к ответственному сотруднику")
    }

    private fun dto(ctx:StaffAccessContext,visit:Visit):StaffVisitDto {
        val dispatch=tasks.staffVisitDispatch(visit.id,!ctx.readOnly)
        if(dispatch.id!=visit.dispatchId || dispatch.branchId!=visit.branchId) notFound()
        val places=jdbc.query("""SELECT i.id,i.media_plan_item_id,s.id AS shelf_id,s.code,s.category,s.width_m,
            coalesce(p.sale_portion,'whole') AS portion,m.advertiser_name,i.client_reference_photo_id
            FROM merch_task_items i JOIN merch_media_plan_items p ON p.id=i.media_plan_item_id AND p.shelf_id=i.shelf_id
            JOIN merch_media_plans m ON m.id=p.media_plan_id JOIN merch_shelves s ON s.id=p.shelf_id
            WHERE i.dispatch_id=? AND s.branch_id=? AND p.placement_month=? ORDER BY s.code,p.sale_portion,i.id""",{rs,_->
            StaffVisitPlaceDto(rs.uuid("id"),rs.uuid("media_plan_item_id"),rs.uuid("shelf_id"),rs.getString("code"),rs.getString("portion"),
                rs.getString("category"),rs.getBigDecimal("width_m")?.toDouble(),rs.getString("advertiser_name"),
                rs.getObject("client_reference_photo_id")!=null)
        },visit.dispatchId,visit.branchId,visit.month)
        if(places.size!=dispatch.items.size) conflict("Связи рекламных мест требуют проверки; история сохранена")
        val reason=jdbc.query("""SELECT r.reason FROM merch_task_reviews r JOIN merch_task_items i ON i.id=r.item_id
            WHERE i.dispatch_id=? AND i.status='revision_required' AND r.decision='revision' ORDER BY r.created_at DESC,r.id DESC LIMIT 1""",
            {rs,_->rs.getString(1)},visit.dispatchId).singleOrNull()
        return StaffVisitDto(visit.id,visit.branchId,visit.month,visit.employeeName,visit.notes,visit.submittedAt,reason,
            canEdit=!ctx.readOnly && dispatch.status !in setOf("submitted","completed","cancelled"),deadlineAt=ctx.deadlineAt,
            version=dispatch.version,dispatch=dispatch,places=places)
    }

    private fun requireMonth(ctx:StaffAccessContext,month:LocalDate) {
        if(month.dayOfMonth!=1 || month!=ctx.placementMonth) forbidden()
    }
    private fun audit(ctx:StaffAccessContext,visit:Visit,type:String,item:UUID?=null) {
        jdbc.update("""INSERT INTO merch_task_events(id,dispatch_id,item_id,event_type,details,created_at)
            VALUES(?,?,?,?,jsonb_build_object('staffSessionId',?::text,'codeId',?::text,'staffVisitId',?::text),now())""",
            UUID.randomUUID(),visit.dispatchId,item,type,ctx.sessionId.toString(),ctx.codeId.toString(),visit.id.toString())
    }
    private fun name(value:String):String=value.trim().also {
        if(it.length !in 2..120 || it.any(Char::isISOControl)) invalid("Укажите имя сотрудника от 2 до 120 символов")
    }
    private data class Visit(val id:UUID,val branchId:UUID,val month:LocalDate,val dispatchId:UUID,
        val employeeName:String,val notes:String,val submittedAt:Instant?)
    private fun ResultSet.uuid(name:String):UUID=getObject(name,UUID::class.java)
    private fun invalid(message:String):Nothing=throw AppException(ErrorCode.VALIDATION_FAILED,message)
    private fun conflict(message:String):Nothing=throw AppException(ErrorCode.CONFLICT,message,HttpStatus.CONFLICT)
    private fun forbidden():Nothing=throw AppException(ErrorCode.FORBIDDEN,"Код доступа не разрешает эту аптеку или месяц",HttpStatus.FORBIDDEN)
    private fun notFound():Nothing=throw AppException(ErrorCode.NOT_FOUND,"Фотоотчёт не найден",HttpStatus.NOT_FOUND)
}
