package kz.epharm.merch.tasks

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.ByteArrayInputStream
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.imageio.ImageIO
import kz.epharm.auth.access.AdministrativeScope
import kz.epharm.auth.access.UnifiedAuthorityLock
import kz.epharm.auth.policy.PermissionKey
import kz.epharm.finance.service.PharmacistWalletService
import kz.epharm.finance.service.WalletCreditSource
import kz.epharm.finance.merch.VerifiedMerchRecipientPolicy
import kz.epharm.merch.delivery.NativeCapabilityAuthorization
import kz.epharm.merch.delivery.AuthorizedTaskCapability
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile

object MerchTaskPermissions {
    val view = key("view"); val create = key("create"); val execute = key("execute")
    val review = key("review"); val financial = key("financial_data")
    val comment = key("comment"); val cancel = key("cancel")
    private fun key(action: String) = requireNotNull(PermissionKey.parse("merchandising", "tasks", action))
}

/** Native writes only. Importers preserve snapshots without calling any method in this service. */
@Service
class MerchTaskService(private val jdbc: JdbcTemplate, private val json: ObjectMapper,
    private val access: AdministrativeScope, private val wallet: PharmacistWalletService,
    private val storage: TaskEvidenceStorage, private val capabilities: NativeCapabilityAuthorization,
    private val recipients: VerifiedMerchRecipientPolicy, private val execution: TaskExecutionEvidence) {

    @Transactional
    fun create(req: CreateMerchTaskDispatch, actor: UUID): MerchTaskDispatchDto {
        access.lockCurrentActor()
        access.requireMerchBranchPermission(MerchTaskPermissions.create, req.branchId)
        val creatorPerson = access.currentVerifiedPersonId()
        validateCreate(req)
        val branch = jdbc.query("SELECT b.active AND o.active FROM merch_branches b JOIN merch_organizations o ON o.id=b.organization_id WHERE b.id=?", { rs, _ -> rs.getBoolean(1) }, req.branchId).singleOrNull()
        if (branch != true) invalid("Нужна активная аптека мерча")
        if (req.rewardAmountKzt > 0) access.requireMerchBranchPermission(MerchTaskPermissions.financial, req.branchId)
        req.assignedPharmacistId?.let {
            if (jdbc.queryForObject("""SELECT count(*) FROM pharmacists p JOIN merch_branches b
                ON b.epharm_pharmacy_id=p.pharmacy_id AND b.pharmacy_binding_verified
                WHERE p.id=? AND p.status='active' AND b.id=?""", Long::class.java, it, req.branchId) != 1L) invalid("Исполнитель не подтверждён в этой аптеке")
        }
        val id=insertTask(req,actor,creatorPerson)
        return dto(dispatch(id), true)
    }

    internal fun insertTask(req: CreateMerchTaskDispatch, actor: UUID?, creatorPerson: UUID): UUID {
        validateCreate(req)
        return insertTaskRows(req.branchId,req.title,req.dueAt,req.items,req.taskType,json.writeValueAsString(req),
            actor,creatorPerson,req.rewardAmountKzt,req.assignedPharmacistId,req.priority,req.availableFrom)
    }

    internal fun insertStaffVisit(branchId:UUID,month:java.time.LocalDate,items:List<CreateMerchTaskItem>):UUID {
        if(items.isEmpty() || items.size>500) invalid("Нужно от 1 до 500 подтверждённых рекламных мест")
        return insertTaskRows(branchId,"Месячный фотоотчёт · $month",null,items,"staff_monthly_visit",
            json.writeValueAsString(mapOf("kind" to "staff_monthly_visit","branchId" to branchId,"month" to month)),null,null,allowArchivedShelves=true)
    }

    private fun insertTaskRows(branchId:UUID,title:String,dueAt:Instant?,items:List<CreateMerchTaskItem>,taskType:String,
        payload:String,actor:UUID?,creatorPerson:UUID?,reward:Long=0,assigned:String?=null,priority:String="normal",availableFrom:Instant?=null,allowArchivedShelves:Boolean=false):UUID {
        val id = UUID.randomUUID(); val now = Instant.now()
        jdbc.update("""INSERT INTO merch_task_dispatches(id,source_system,source_origin,source_id,source_payload,branch_id,title,
            status,due_at,reward_amount_kzt,assigned_pharmacist_id,created_by,created_at,updated_at,priority,available_from,task_type)
            VALUES (?,'EPHARM','https://epharm.inkar.kz',?,?::jsonb,?,?,'ready',?,?,?,?,?,?,?,?,?)""",
            id, id, payload, branchId, title.trim(), dueAt?.let(Timestamp::from), reward,
            assigned, actor, Timestamp.from(now), Timestamp.from(now), priority, availableFrom?.let(Timestamp::from),taskType)
        for (item in items) {
            item.shelfId?.let { shelf -> if(jdbc.queryForObject("SELECT count(*) FROM merch_shelves WHERE id=? AND branch_id=?${if(allowArchivedShelves) "" else " AND active"}",Int::class.java,shelf,branchId)!=1)
                invalid("Полка не принадлежит выбранной аптеке") }
            item.mediaPlanItemId?.let {placement->
                if(item.shelfId==null || jdbc.queryForObject("SELECT count(*) FROM merch_media_plan_items WHERE id=? AND shelf_id=?",Int::class.java,placement,item.shelfId)!=1)
                    invalid("Строка размещения не принадлежит полке пункта")
            }
            val itemId = UUID.randomUUID()
            val checklist = item.checklist.map { TaskChecklistItem(UUID.randomUUID(), it.trim()) }
            jdbc.update("""INSERT INTO merch_task_items(id,dispatch_id,source_id,source_payload,title,instructions,task_type,status,
                required,required_photo_count,checklist,created_at,updated_at,available_from,due_at,shelf_id,stock_products,media_plan_item_id,client_reference_photo_id)
                VALUES (?,?,?,'{}',?,?,?,'assigned',?,?,?::jsonb,?,?,?,?,?,?::jsonb,?,(SELECT id FROM merch_placement_reference_photos WHERE media_plan_item_id=? AND archived_at IS NULL))""", itemId, id, itemId, item.title.trim(),
                item.instructions, taskType, item.required, item.requiredPhotos, json.writeValueAsString(checklist), Timestamp.from(now), Timestamp.from(now),
                availableFrom?.let(Timestamp::from), dueAt?.let(Timestamp::from), item.shelfId,json.writeValueAsString(item.stockProducts),item.mediaPlanItemId,item.mediaPlanItemId)
        }
        event(id, null, actor, creatorPerson, "created", mapOf("itemCount" to items.size))
        return id
    }

    @Transactional
    fun createBulk(req:CreateMerchTaskBulk,actor:UUID):MerchTaskBulkResult {
        access.lockCurrentActor()
        if(req.branchIds.size !in 1..50 || req.branchIds.size.toLong()*req.items.size>500 || req.items.any {it.shelfId!=null})
            invalid("Выберите до 50 аптек и до 500 пунктов; полки конкретной аптеки нельзя тиражировать")
        val branches=req.branchIds.sorted()
        for(branch in branches) {
            access.requireMerchBranchPermission(MerchTaskPermissions.create,branch)
            if(req.rewardAmountKzt>0) access.requireMerchBranchPermission(MerchTaskPermissions.financial,branch)
        }
        val canonical=json.writeValueAsString(req.copy(branchIds=branches.toSet()))
        val existing=jdbc.query("SELECT created_by,request_payload::text,dispatch_ids::text FROM merch_task_bulk_receipts WHERE id=? FOR UPDATE",
            {rs,_->Triple(rs.getObject(1,UUID::class.java),rs.getString(2),rs.getString(3))},req.requestId).singleOrNull()
        if(existing!=null) {
            if(existing.first!=actor || json.readTree(existing.second)!=json.readTree(canonical)) conflict("Номер массового назначения уже использован с другими условиями")
            return MerchTaskBulkResult(json.readValue(existing.third))
        }
        val requests=branches.map {branch->CreateMerchTaskDispatch(branch,req.title,req.dueAt,req.rewardAmountKzt,null,req.items,req.priority,req.availableFrom,req.taskType)}
        requests.forEach(::validateCreate)
        val ids=requests.map {create(it,actor).id}
        jdbc.update("INSERT INTO merch_task_bulk_receipts(id,created_by,request_payload,dispatch_ids,created_at) VALUES(?,?,?::jsonb,?::jsonb,now())",
            req.requestId,actor,canonical,json.writeValueAsString(ids))
        return MerchTaskBulkResult(ids)
    }

    @Transactional(readOnly = true)
    fun list(limit: Int, offset: Int,queue:String="all",query:String?=null): MerchTaskDispatchPage {
        access.requireExplicitPermission(MerchTaskPermissions.view)
        page(limit, offset)
        val scope = access.currentMerchBranchIds()
        val args = mutableListOf<Any>()
        if(queue !in setOf("all","active","review","revision","overdue","completed") || query.orEmpty().length>120) invalid("Некорректный фильтр очереди")
        val conditions=mutableListOf("true")
        if (scope != null) {
            if (scope.isEmpty()) forbidden()
            args.addAll(scope); conditions+="d.branch_id IN (${scope.joinToString(",") { "?" }})"
        }
        when(queue) {
            "active"->conditions+="d.status NOT IN ('completed','cancelled')"
            "review"->conditions+="EXISTS(SELECT 1 FROM merch_task_items i WHERE i.dispatch_id=d.id AND i.status='submitted')"
            "revision"->conditions+="EXISTS(SELECT 1 FROM merch_task_items i WHERE i.dispatch_id=d.id AND i.status='revision_required')"
            "overdue"->conditions+="d.due_at<now() AND d.status NOT IN ('completed','cancelled')"
            "completed"->conditions+="d.status='completed'"
        }
        query?.trim()?.takeIf(String::isNotEmpty)?.let {conditions+="strpos(lower(d.title),lower(?))>0";args+=it}
        val where=" WHERE "+conditions.joinToString(" AND ")
        args += limit + 1; args += offset
        val rows = jdbc.query("SELECT d.id FROM merch_task_dispatches d$where ORDER BY d.created_at DESC,d.id LIMIT ? OFFSET ?",
            { rs, _ -> rs.getObject(1, UUID::class.java) }, *args.toTypedArray())
        return MerchTaskDispatchPage(rows.take(limit).map { id ->
            val d = dispatch(id); dto(d, access.canMerchBranchPermission(MerchTaskPermissions.financial, d.branchId), details = false)
        }, limit, offset, rows.size > limit)
    }

    @Transactional(readOnly = true)
    fun get(id: UUID): MerchTaskDispatchDto {
        val d = dispatch(id)
        access.requireMerchBranchPermission(MerchTaskPermissions.view, d.branchId)
        return dto(d, access.canMerchBranchPermission(MerchTaskPermissions.financial, d.branchId))
    }

    @Transactional(readOnly = true)
    fun getPharmacist(id: UUID, pharmacistId: String): MerchTaskDispatchDto {
        val d = dispatch(id)
        return dto(d, true, mobileExecutor = pharmacistExecutor(d, pharmacistId))
    }

    @Transactional(readOnly = true)
    fun branches(): List<MerchTaskBranchDto> {
        access.requireExplicitPermission(MerchTaskPermissions.view)
        val scope = access.currentMerchBranchIds()
        if (scope?.isEmpty() == true) forbidden()
        val args = scope?.toTypedArray() ?: emptyArray()
        val where = if (scope == null) "" else " WHERE b.id IN (${scope.joinToString(",") { "?" }})"
        val person = currentAdminPerson()
        return jdbc.query("SELECT b.id,b.name,b.city,b.address,(b.active AND o.active) AS active FROM merch_branches b JOIN merch_organizations o ON o.id=b.organization_id$where ORDER BY b.id LIMIT 50001", { rs, _ ->
            val id=rs.uuid("id");val active=rs.getBoolean("active")
            val create=active && person!=null && access.canMerchBranchPermission(MerchTaskPermissions.create,id)
            MerchTaskBranchDto(id,rs.getString("name"),rs.getString("city"),rs.getString("address"),active,
                create,create && access.canMerchBranchPermission(MerchTaskPermissions.financial,id))
        },*args).also { if(it.size>50_000) throw AppException(ErrorCode.VALIDATION_FAILED,
            "Список аптек слишком велик: требуется уточнить область доступа",HttpStatus.PAYLOAD_TOO_LARGE) }
    }

    internal fun staffVisitDispatch(visitId:UUID,editable:Boolean):MerchTaskDispatchDto {
        val d=staffDispatch(visitId)
        val value=dto(d,false)
        val writable=editable && d.branchActive && d.status !in setOf("submitted","completed","cancelled")
        return value.copy(rewardAmountKzt=0,reward=null,canCancel=false,items=value.items.map { item ->
            val attempt=item.currentAttempt
            if(attempt!=null && staffAttemptOwner(attempt.id,d.id)!=visitId) conflict("Попытка не принадлежит общему фотоотчёту")
            val draft=writable && item.status=="in_progress" && attempt?.status=="draft"
            item.copy(currentAttempt=attempt?.let(::publicAttempt),legacyEvidence=emptyList(),
                canStart=writable && item.status in setOf("assigned","accepted","revision_required"),
                canUpload=(draft && attempt!!.evidence.size<12) || writable && item.status in setOf("assigned","accepted","revision_required"),
                canSubmit=draft && attempt!!.evidence.size>=item.requiredPhotos,
                canReview=false,canResumeSource=false,canAdoptSourceReview=false,canBlock=false,canComment=false,canCancel=false)
        })
    }

    internal fun uploadStaffVisit(visitId:UUID,itemId:UUID,name:String,uploadId:UUID,file:MultipartFile):MerchTaskEvidenceDto {
        val (d,initial)=staffLocked(visitId,itemId)
        start(d,initial,TaskExecutor(null,null,null,null,staffVisitId=visitId,staffSubmittedName=name))
        // As in the source camera endpoint, this labels the web capture route; it does not prove a person or location.
        return upload(d,item(itemId,true),null,uploadId,file,
            capture=TaskCapture(captureSource="web_camera"),staffVisitId=visitId)
    }

    internal fun submitStaffVisit(visitId:UUID,version:Long,name:String,notes:String):MerchTaskDispatchDto {
        val d=staffDispatch(visitId,true)
        if(d.version!=version || d.status in setOf("submitted","completed","cancelled")) conflict("Фотоотчёт изменился или уже отправлен")
        val rows=jdbc.query("SELECT id FROM merch_task_items WHERE dispatch_id=? ORDER BY id FOR UPDATE",
            {rs,_->item(rs.uuid("id"))},d.id)
        val required=rows.filter { it.required && it.status !in setOf("completed","cancelled") }
        if(required.none { it.status!="submitted" }) conflict("В отчёте нет новых пунктов для отправки")
        for(i in required) {
            val a=currentAttempt(i);requireExecutor(a.id,null,staffVisitId=visitId)
            if(i.status=="submitted") {
                if(a.status!="submitted" || a.submittedAt==null) conflict("Отправленная попытка требует проверки")
                continue
            }
            if(i.status!="in_progress" || a.status!="draft") conflict("Пункт уже изменён: обновите фотоотчёт")
            if(a.evidence.count { it.capture["captureSource"]=="web_camera" }<i.requiredPhotos)
                invalid("Сделайте новый снимок камерой для каждого рекламного места")
        }
        for(i in required.filter { it.status!="submitted" }) submit(d,i,null,null,SubmitMerchTaskAttempt(i.attempt,i.checklist.map {it.id}.toSet(),notes),
            staffVisitId=visitId,staffSubmittedName=name)
        jdbc.update("UPDATE merch_staff_visits SET employee_name=?,notes=?,submitted_at=now(),updated_at=now() WHERE id=?",name,notes,visitId)
        return staffVisitDispatch(visitId,false)
    }

    internal fun downloadStaffVisit(visitId:UUID,evidenceId:UUID):Pair<MerchTaskEvidenceDto,ByteArray> {
        val d=staffDispatch(visitId)
        val own=jdbc.queryForObject("""SELECT EXISTS(SELECT 1 FROM merch_task_evidence e
            JOIN merch_task_items i ON i.id=e.item_id JOIN merch_task_attempts a ON a.id=e.attempt_id AND a.item_id=i.id
            WHERE e.id=? AND i.dispatch_id=? AND a.staff_visit_id=? AND a.executor_person_id IS NULL
              AND a.capability_link_id IS NULL)""",Boolean::class.java,evidenceId,d.id,visitId)==true
        if(!own) notFound()
        return download(evidenceId)
    }

    internal fun referenceStaffVisit(visitId:UUID,itemId:UUID):Pair<MerchTaskEvidenceDto,ByteArray> {
        val d=staffDispatch(visitId)
        if(item(itemId).dispatchId!=d.id) notFound()
        return execution.reference(itemId)
    }

    private fun staffVisitForDispatch(dispatch:UUID):UUID?=jdbc.query("SELECT id FROM merch_staff_visits WHERE dispatch_id=?",
        {rs,_->rs.uuid("id")},dispatch).singleOrNull()

    private fun staffAttemptOwner(attempt:UUID,dispatch:UUID):UUID?=jdbc.query("""SELECT v.id FROM merch_task_attempts a
        JOIN merch_task_items i ON i.id=a.item_id JOIN merch_staff_visits v ON v.id=a.staff_visit_id AND v.dispatch_id=i.dispatch_id
        JOIN merch_task_dispatches d ON d.id=i.dispatch_id WHERE a.id=? AND d.id=? AND d.reward_amount_kzt=0
          AND d.task_type='staff_monthly_visit' AND d.assigned_pharmacist_id IS NULL AND a.executor_person_id IS NULL
          AND a.executor_account_id IS NULL AND a.pharmacist_id IS NULL AND a.binding_id IS NULL AND a.capability_link_id IS NULL""",
        {rs,_->rs.uuid("id")},attempt,dispatch).singleOrNull()

    private fun staffDispatch(visitId:UUID,lock:Boolean=false):TaskDispatchRow {
        val root=jdbc.query("SELECT dispatch_id,branch_id FROM merch_staff_visits WHERE id=?",
            {rs,_->rs.uuid("dispatch_id") to rs.uuid("branch_id")},visitId).singleOrNull()?:notFound()
        val id=root.first
        if(lock) jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,817105))",id.toString())
        val d=dispatch(id,lock)
        if(d.branchId!=root.second || d.reward!=0L || d.assignedPharmacist!=null || d.taskType!="staff_monthly_visit" || d.source!="EPHARM") forbidden()
        return d
    }

    private fun staffLocked(visitId:UUID,itemId:UUID):Pair<TaskDispatchRow,TaskItemRow> {
        val d=staffDispatch(visitId)
        if(item(itemId).dispatchId!=d.id) notFound()
        return locked(itemId).also {if(it.first.id!=d.id) notFound()}
    }

    @Transactional(readOnly = true)
    fun getCapability(rawToken: String): MerchTaskDispatchDto {
        val cap=freeReadCapability(rawToken)
        return capabilityDto(dispatch(cap.dispatchId),cap)
    }

    @Transactional
    fun startCapability(itemId: UUID, rawToken: String): MerchTaskAttemptDto {
        val cap=capabilities.requireFreeMutation(rawToken)
        val (d,item)=capabilityLocked(itemId,cap)
        return publicAttempt(start(d,item,TaskExecutor(null,null,null,null,cap.linkId)))
    }

    @Transactional
    fun uploadCapability(itemId: UUID, uploadId: UUID, file: MultipartFile, rawToken: String, capture:TaskCapture?=null): MerchTaskEvidenceDto {
        val cap=capabilities.requireFreeMutation(rawToken)
        val (d,item)=capabilityLocked(itemId,cap)
        return upload(d,item,null,uploadId,file,capabilityLinkId=cap.linkId,capture=capture)
    }

    @Transactional
    fun submitCapability(itemId: UUID, request: SubmitMerchTaskAttempt, rawToken: String): MerchTaskAttemptDto {
        val cap=capabilities.requireFreeMutation(rawToken)
        val (d,item)=capabilityLocked(itemId,cap)
        return publicAttempt(submit(d,item,null,null,request,cap.linkId))
    }

    @Transactional(readOnly = true)
    fun downloadCapability(evidenceId: UUID, rawToken: String): Pair<MerchTaskEvidenceDto,ByteArray> {
        val cap=freeReadCapability(rawToken)
        val location=jdbc.query("""SELECT i.dispatch_id,e.storage_backend,a.capability_link_id FROM merch_task_evidence e
            JOIN merch_task_items i ON i.id=e.item_id LEFT JOIN merch_task_attempts a ON a.id=e.attempt_id WHERE e.id=?""",
            {rs,_->Triple(rs.uuid("dispatch_id"),rs.getString("storage_backend"),rs.getObject("capability_link_id",UUID::class.java))},evidenceId).singleOrNull()?:notFound()
        if(location.first!=cap.dispatchId || location.second=="private_host" && location.third!=cap.linkId) notFound()
        return download(evidenceId)
    }

    @Transactional
    fun startAdmin(itemId: UUID, actor: UUID): MerchTaskAttemptDto {
        access.lockCurrentActor()
        val pair = locked(itemId)
        access.requireMerchBranchPermission(MerchTaskPermissions.execute, pair.first.branchId)
        val person = access.currentVerifiedPersonId()
        return start(pair.first, pair.second, TaskExecutor(person, actor, null, null))
    }

    @Transactional
    fun startPharmacist(itemId: UUID, pharmacistId: String): MerchTaskAttemptDto {
        UnifiedAuthorityLock.acquire(jdbc)
        val pair = locked(itemId)
        return start(pair.first, pair.second, pharmacistExecutor(pair.first, pharmacistId,lockPharmacist=true))
    }

    private fun start(d: TaskDispatchRow, item: TaskItemRow, executor: TaskExecutor): MerchTaskAttemptDto {
        writable(d)
        available(item)
        requireMonthlyPerson(d.id,executor.personId)
        val staffVisit=staffVisitForDispatch(d.id)
        if(staffVisit!=executor.staffVisitId) forbidden()
        if(item.status=="blocked") {
            val existing=currentAttempt(item);requireExecutor(existing.id,executor.personId,executor.capabilityLinkId,executor.staffVisitId)
            if(existing.status!="draft") conflict("Заблокированная попытка требует проверки")
            jdbc.update("UPDATE merch_task_items SET status='in_progress',blocked_reason=NULL,version=version+1,updated_at=now() WHERE id=?",item.id)
            event(d.id,item.id,executor.accountId,executor.personId,"unblocked",mapOf("attemptId" to existing.id))
            return existing
        }
        if (item.status == "in_progress") {
            val existing = currentAttempt(item)
            requireExecutor(existing.id, executor.personId, executor.capabilityLinkId, executor.staffVisitId)
            return existing
        }
        if (item.status !in setOf("assigned", "accepted", "revision_required")) conflict("Пункт недоступен для новой попытки")
        val id = UUID.randomUUID(); val now = Instant.now()
        jdbc.update("""INSERT INTO merch_task_attempts(id,item_id,attempt_no,executor_person_id,executor_account_id,pharmacist_id,
            binding_id,capability_link_id,staff_visit_id,staff_submitted_name,status,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,'draft',?)""", id, item.id, item.attempt + 1, executor.personId,
            executor.accountId, executor.pharmacistId, executor.bindingId, executor.capabilityLinkId,
            executor.staffVisitId,executor.staffSubmittedName?.trim(),Timestamp.from(now))
        jdbc.update("UPDATE merch_task_items SET current_attempt=current_attempt+1,status='in_progress',version=version+1,updated_at=now() WHERE id=?", item.id)
        jdbc.update("UPDATE merch_task_dispatches SET status='in_progress',version=version+1,updated_at=now() WHERE id=?", d.id)
        event(d.id, item.id, executor.accountId, executor.personId, "attempt_started", mapOf("attemptId" to id) +
            if(executor.capabilityLinkId==null) emptyMap() else mapOf("capabilityLinkId" to executor.capabilityLinkId))
        return attempt(id)
    }

    @Transactional
    fun uploadAdmin(itemId: UUID, clientUploadId: UUID, file: MultipartFile, actor: UUID, capture:TaskCapture?=null): MerchTaskEvidenceDto {
        access.lockCurrentActor()
        val pair = locked(itemId)
        access.requireMerchBranchPermission(MerchTaskPermissions.execute, pair.first.branchId)
        return upload(pair.first, pair.second, access.currentVerifiedPersonId(), clientUploadId, file, actor,capture=capture)
    }
    @Transactional
    fun uploadPharmacist(itemId: UUID, clientUploadId: UUID, file: MultipartFile, pharmacistId: String, capture:TaskCapture?=null): MerchTaskEvidenceDto {
        UnifiedAuthorityLock.acquire(jdbc)
        val pair = locked(itemId)
        return upload(pair.first, pair.second, pharmacistExecutor(pair.first, pharmacistId,lockPharmacist=true).personId, clientUploadId, file,capture=capture)
    }
    private fun upload(d: TaskDispatchRow, item: TaskItemRow, person: UUID?, uploadId: UUID, file: MultipartFile, actor: UUID? = null,
        capabilityLinkId: UUID? = null, capture:TaskCapture?=null, staffVisitId:UUID?=null): MerchTaskEvidenceDto {
        return TaskPhotoGate.run { uploadAcquired(d,item,person,uploadId,file,actor,capabilityLinkId,capture,staffVisitId) }
    }
    private fun uploadAcquired(d: TaskDispatchRow, item: TaskItemRow, person: UUID?, uploadId: UUID, file: MultipartFile, actor: UUID?, capabilityLinkId: UUID?,capture:TaskCapture?,staffVisitId:UUID?): MerchTaskEvidenceDto {
        writable(d)
        available(item)
        if (item.status != "in_progress") conflict("Сначала начните попытку")
        val attempt = currentAttempt(item)
        requireExecutor(attempt.id, person, capabilityLinkId,staffVisitId)
        val video=execution.policy(item.id,attempt.id).requiresVideo
        val photo = if(video) null else imageBytes(file)
        val bytes = photo?.second ?: videoBytes(file)
        val hash = TaskEvidenceStorage.hash(bytes)
        val prior = jdbc.query("SELECT id,sha256 FROM merch_task_evidence WHERE attempt_id=? AND client_upload_id=?",
            { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getString(2) }, attempt.id, uploadId).singleOrNull()
        if (prior != null) {
            if (prior.second != hash) conflict("Повторная загрузка имеет другое содержимое")
            return evidence(prior.first)
        }
        if (jdbc.queryForObject("SELECT count(*) FROM merch_task_evidence WHERE attempt_id=?", Int::class.java, attempt.id)!! >= 12) invalid("Не больше 12 фотографий в попытке")
        val id = UUID.randomUUID()
        val mime = photo?.first ?: file.contentType!!
        val extension=when(mime){"image/png"->"png";"image/webp"->"webp";"video/mp4"->"mp4";"video/quicktime"->"mov";"video/webm"->"webm";else->"jpg"}
        val key = "native/${d.id}/${item.id}/${attempt.id}/$id.$extension"
        val captureMetadata=execution.capture(item.id,capture)
        storage.write(key, bytes)
        jdbc.update("""INSERT INTO merch_task_evidence(id,item_id,attempt_id,client_upload_id,storage_backend,object_key,mime_type,
            byte_size,sha256,original_filename,metadata,uploaded_by_person_id,created_at)
            VALUES (?,?,?,?,'private_host',?,?,?,?,?,?::jsonb,?,now())""", id, item.id, attempt.id,
            uploadId, key, mime, bytes.size.toLong(), hash, file.originalFilename, json.writeValueAsString(captureMetadata),person)
        event(d.id, item.id, actor, person, "evidence_uploaded", mapOf("evidenceId" to id, "attemptId" to attempt.id, "sha256" to hash) +
            if(capabilityLinkId==null) emptyMap() else mapOf("capabilityLinkId" to capabilityLinkId))
        return evidence(id)
    }

    @Transactional
    fun saveStockAdmin(itemId:UUID,request:SaveTaskStock,actor:UUID):TaskStockDto {
        access.lockCurrentActor();val(d,item)=locked(itemId)
        access.requireMerchBranchPermission(MerchTaskPermissions.execute,d.branchId)
        return saveStock(d,item,request,access.currentVerifiedPersonId(),null,actor)
    }
    @Transactional
    fun saveStockPharmacist(itemId:UUID,request:SaveTaskStock,pharmacist:String):TaskStockDto {
        UnifiedAuthorityLock.acquire(jdbc);val(d,item)=locked(itemId)
        return saveStock(d,item,request,pharmacistExecutor(d,pharmacist,true).personId,null)
    }
    @Transactional
    fun saveStockCapability(itemId:UUID,request:SaveTaskStock,raw:String):TaskStockDto {
        val cap=capabilities.requireFreeMutation(raw);val(d,item)=capabilityLocked(itemId,cap)
        return saveStock(d,item,request,null,cap.linkId)
    }
    private fun saveStock(d:TaskDispatchRow,item:TaskItemRow,request:SaveTaskStock,person:UUID?,link:UUID?,account:UUID?=null):TaskStockDto {
        writable(d);available(item);val attempt=currentAttempt(item)
        requireExecutor(attempt.id,person,link)
        if(item.status!="in_progress" || attempt.status!="draft" || request.expectedAttempt!=item.attempt) conflict("Попытка уже изменена")
        val saved=execution.save(item.id,attempt.id,request)
        event(d.id,item.id,account,person,"stock_saved",mapOf("attemptId" to attempt.id,"products" to saved.counts.size))
        return saved
    }

    @Transactional(readOnly=true)
    fun instructionAdmin(itemId:UUID):Pair<MerchTaskEvidenceDto,ByteArray> {
        val item=item(itemId);access.requireMerchBranchPermission(MerchTaskPermissions.view,dispatch(item.dispatchId).branchId)
        return execution.instruction(itemId)
    }
    @Transactional(readOnly=true)
    fun instructionPharmacist(itemId:UUID,pharmacist:String):Pair<MerchTaskEvidenceDto,ByteArray> {
        val item=item(itemId);pharmacistExecutor(dispatch(item.dispatchId),pharmacist)
        return execution.instruction(itemId)
    }
    @Transactional(readOnly=true)
    fun instructionCapability(itemId:UUID,token:String):Pair<MerchTaskEvidenceDto,ByteArray> {
        val cap=freeReadCapability(token);if(item(itemId).dispatchId!=cap.dispatchId) notFound()
        return execution.instruction(itemId)
    }

    @Transactional(readOnly=true)
    fun referenceAdmin(itemId:UUID):Pair<MerchTaskEvidenceDto,ByteArray> {
        val i=item(itemId);access.requireMerchBranchPermission(MerchTaskPermissions.view,dispatch(i.dispatchId).branchId)
        return execution.reference(itemId)
    }
    @Transactional(readOnly=true)
    fun referencePharmacist(itemId:UUID,pharmacist:String):Pair<MerchTaskEvidenceDto,ByteArray> {
        val i=item(itemId);pharmacistExecutor(dispatch(i.dispatchId),pharmacist)
        return execution.reference(itemId)
    }
    @Transactional(readOnly=true)
    fun referenceCapability(itemId:UUID,token:String):Pair<MerchTaskEvidenceDto,ByteArray> {
        val cap=freeReadCapability(token);if(item(itemId).dispatchId!=cap.dispatchId) notFound()
        return execution.reference(itemId)
    }

    @Transactional
    fun submitAdmin(itemId: UUID, req: SubmitMerchTaskAttempt, actor: UUID): MerchTaskAttemptDto {
        access.lockCurrentActor()
        val pair = locked(itemId)
        access.requireMerchBranchPermission(MerchTaskPermissions.execute, pair.first.branchId)
        return submit(pair.first, pair.second, access.currentVerifiedPersonId(), actor, req)
    }
    @Transactional
    fun submitPharmacist(itemId: UUID, req: SubmitMerchTaskAttempt, pharmacistId: String): MerchTaskAttemptDto {
        UnifiedAuthorityLock.acquire(jdbc)
        val pair = locked(itemId)
        return submit(pair.first, pair.second, pharmacistExecutor(pair.first, pharmacistId,lockPharmacist=true).personId, null, req)
    }
    private fun submit(d: TaskDispatchRow, item: TaskItemRow, person: UUID?, actor: UUID?, req: SubmitMerchTaskAttempt, capabilityLinkId: UUID? = null,
        staffVisitId:UUID?=null,staffSubmittedName:String?=null): MerchTaskAttemptDto {
        writable(d)
        available(item)
        if (req.comment.length > 1200 || req.expectedAttempt != item.attempt) conflict("Попытка изменилась: обновите пункт")
        val attempt = currentAttempt(item)
        requireExecutor(attempt.id, person, capabilityLinkId,staffVisitId)
        if (attempt.status == "submitted") return attempt
        execution.requireSubmission(item.id,attempt.id)
        if (item.status != "in_progress" || attempt.status != "draft") conflict("Пункт нельзя отправить")
        if (req.completedChecklistIds.any { id -> item.checklist.none { it.id == id } } ||
            item.checklist.any { it.required && it.id !in req.completedChecklistIds }) invalid("Выполните обязательный чек-лист")
        if (attempt.evidence.size < item.requiredPhotos) invalid("Недостаточно фотографий в текущей попытке")
        jdbc.update("UPDATE merch_task_attempts SET status='submitted',submitted_at=now(),completed_checklist_ids=?::jsonb,employee_comment=?,staff_submitted_name=CASE WHEN staff_visit_id IS NULL THEN staff_submitted_name ELSE ? END WHERE id=?",
            json.writeValueAsString(req.completedChecklistIds), req.comment,staffSubmittedName?.trim(),attempt.id)
        jdbc.update("UPDATE merch_task_items SET status='submitted',version=version+1,updated_at=now() WHERE id=?", item.id)
        refresh(d.id)
        event(d.id, item.id, actor, person, "submitted", mapOf("attemptId" to attempt.id) +
            if(capabilityLinkId==null) emptyMap() else mapOf("capabilityLinkId" to capabilityLinkId))
        return attempt(attempt.id)
    }

    @Transactional
    fun review(itemId: UUID, req: ReviewMerchTaskItem, actor: UUID): MerchTaskDispatchDto {
        access.lockCurrentActor()
        val (d, item) = locked(itemId)
        val attempt = attempt(req.attemptId)
        if (attempt.id != currentAttempt(item).id) conflict("Проверяется другая попытка")
        val reviewer = access.currentVerifiedPersonId()
        val executor = attempt.executorPersonId
        val sourceManual=executor==null && d.source=="MERCH_CRM" && capabilityOwner(attempt.id)==null && req.manualSourcePreserved
        if(sourceManual && !preservedSourceReview(attempt.id)) conflict("Сохранённая исходная попытка не подтверждена неизменяемым снимком")
        val manual = executor==null && d.reward==0L && (capabilityOwner(attempt.id)!=null || staffAttemptOwner(attempt.id,d.id)!=null) && req.manualUnverifiedExecutor || sourceManual
        if(manual) access.requireManualUnverifiedTaskReview(MerchTaskPermissions.review,d.branchId)
        else {
            if(executor==null) forbidden()
            access.requireTaskReviewPermission(MerchTaskPermissions.review, d.branchId, executor)
            if (reviewer == executor) forbidden() // A manual flag never overrides verified self-review, including aliases.
        }
        if (req.decision !in setOf("approve", "revision") || req.reason.trim().length !in 3..1200) invalid("Нужны решение и причина от 3 до 1200 символов")
        val old = attempt.review
        if (old != null) {
            if (old.reviewerAccountId == actor && old.decision == req.decision && old.reason == req.reason.trim())
                return dto(d, access.canMerchBranchPermission(MerchTaskPermissions.financial, d.branchId))
            conflict("Попытка уже проверена")
        }
        if (item.version != req.expectedItemVersion || item.status != "submitted" ||
            attempt.status != "submitted" && !(sourceManual && attempt.status=="source_preserved")) conflict("Пункт уже изменён")
        if(req.decision=="approve") execution.requireReview(itemId,attempt.id,req.reason)
        jdbc.update("""INSERT INTO merch_task_reviews(id,item_id,attempt_id,reviewer_account_id,reviewer_person_id,decision,reason,created_at)
            VALUES (?,?,?,?,?,?,?,now())""", UUID.randomUUID(), itemId, attempt.id, actor, reviewer, req.decision, req.reason.trim())
        val approved = req.decision == "approve"
        jdbc.update("UPDATE merch_task_attempts SET status=? WHERE id=?", if (approved) "approved" else "revision_required", attempt.id)
        jdbc.update("UPDATE merch_task_items SET status=?,version=version+1,updated_at=now() WHERE id=?", if (approved) "completed" else "revision_required", itemId)
        refresh(d.id)
        event(d.id, itemId, actor, reviewer, req.decision, mapOf("attemptId" to attempt.id, "reason" to req.reason.trim(),
            "reviewMode" to when {sourceManual->"manual_source_preserved";manual->"manual_unverified_executor";else->"independent_verified_person"},
            "executorIdentityStatus" to attempt.executorIdentityStatus))
        if(sourceManual && approved && d.reward>0 && jdbc.queryForObject("SELECT count(*) FROM merch_task_rewards WHERE dispatch_id=?",Int::class.java,d.id)==0)
            rewardState(d,"held","source_preserved_approval_requires_HQ_reconciliation")
        creditIfComplete(dispatch(d.id))
        return dto(dispatch(d.id), access.canMerchBranchPermission(MerchTaskPermissions.financial, d.branchId))
    }

    private fun creditIfComplete(d: TaskDispatchRow) {
        if (jdbc.queryForObject("SELECT count(*) FROM merch_task_items WHERE dispatch_id=? AND required AND status<>'completed'", Int::class.java, d.id)!! > 0 ||
            jdbc.queryForObject("SELECT count(*) FROM merch_task_items WHERE dispatch_id=? AND required", Int::class.java, d.id) == 0 ||
            jdbc.queryForObject("SELECT count(*) FROM merch_task_rewards WHERE dispatch_id=?", Int::class.java, d.id)!! > 0) return
        if (d.historicalReward) { rewardState(d, "held", "historical_approval_or_outbox_requires_HQ"); return }
        if (d.reward == 0L) { rewardState(d, "not_monetary", "zero_reward"); return }
        val due = d.dueAt ?: run { rewardState(d, "held", "historical_due_at_not_recorded"); return }
        val attempts = jdbc.query("""SELECT a.* FROM merch_task_attempts a JOIN merch_task_items i ON i.id=a.item_id
            WHERE i.dispatch_id=? AND i.required AND a.attempt_no=i.current_attempt""", { rs, _ ->
            arrayOf<Any?>(rs.getString("pharmacist_id"), rs.getObject("executor_person_id", UUID::class.java),
                rs.getObject("binding_id", UUID::class.java), rs.getTimestamp("submitted_at")?.toInstant())
        }, d.id)
        val identities = attempts.map { Triple(it[0] as String?, it[1] as UUID?, it[2] as UUID?) }.distinct()
        val identity = identities.singleOrNull()
        if (attempts.isEmpty() || identity?.first == null || identity.second == null || identity.third == null ||
            attempts.any { (it[3] as Instant?)?.isAfter(due) != false }) { rewardState(d, "held", "executor_or_timeliness_requires_HQ"); return }
        val verified = try { pharmacistExecutor(d, identity.first!!, lockPharmacist = true) } catch (_: AppException) { null }
        if (verified == null || verified.personId != identity.second || verified.bindingId != identity.third) {
            rewardState(d, "held", "executor_moved_inactive_or_binding_changed"); return
        }
        try { recipients.requireRecipient(identity.first!!,d.pharmacyId!!,locked=true) }
        catch (_: AppException) { rewardState(d,"held","recipient_HQ_mapping_no_longer_verified");return }
        val existing = jdbc.query("SELECT id,pharmacist_id,amount_kzt FROM pharmacist_wallet_entries WHERE source='merch_task' AND source_id=?",
            { rs, _ -> Triple(rs.uuid("id"),rs.getString("pharmacist_id"),rs.getLong("amount_kzt")) }, d.id.toString()).singleOrNull()
        if (existing != null) {
            if (existing.second != identity.first || existing.third != d.reward) {
                rewardState(d, "held", "existing_ledger_identity_or_amount_conflicts"); return
            }
            jdbc.update("""INSERT INTO merch_task_rewards(dispatch_id,pharmacist_id,executor_person_id,historical_pharmacy_id,
                amount_kzt,state,reason,wallet_entry_id,created_at) VALUES (?,?,?,?,?,'already_credited','existing_wallet_posting_verified',?,now())""",
                d.id,identity.first,identity.second,d.pharmacyId,d.reward,existing.first)
            return
        }
        val credit = wallet.credit(WalletCreditSource.MERCH_TASK, d.id.toString(), identity.first!!, d.reward, "native-merch-dispatch:${d.id}")
        jdbc.update("""INSERT INTO merch_task_rewards(dispatch_id,pharmacist_id,executor_person_id,historical_pharmacy_id,
            amount_kzt,state,reason,wallet_entry_id,created_at) VALUES (?,?,?,?,?,'credited','all_required_items_approved',?,now())""",
            d.id, identity.first, identity.second, d.pharmacyId, d.reward, credit.entryId)
    }

    private fun rewardState(d: TaskDispatchRow, state: String, reason: String) {
        jdbc.update("INSERT INTO merch_task_rewards(dispatch_id,amount_kzt,state,reason,created_at) VALUES (?,?,?,?,now())", d.id, d.reward, state, reason)
    }

    @Transactional(readOnly = true)
    fun history(itemId: UUID, limit: Int, offset: Int): MerchTaskAttemptPage {
        page(limit, offset)
        val item = item(itemId)
        access.requireMerchBranchPermission(MerchTaskPermissions.view, dispatch(item.dispatchId).branchId)
        val ids = jdbc.query("SELECT id FROM merch_task_attempts WHERE item_id=? ORDER BY attempt_no DESC LIMIT ? OFFSET ?",
            { rs, _ -> rs.getObject(1, UUID::class.java) }, itemId, limit + 1, offset)
        return MerchTaskAttemptPage(ids.take(limit).map(::attempt), limit, offset, ids.size > limit)
    }

    @Transactional(readOnly = true)
    fun downloadAdmin(evidenceId: UUID): Pair<MerchTaskEvidenceDto, ByteArray> {
        val itemId = jdbc.query("SELECT item_id FROM merch_task_evidence WHERE id=?", { rs, _ -> rs.getObject(1, UUID::class.java) }, evidenceId).singleOrNull() ?: notFound()
        access.requireMerchBranchPermission(MerchTaskPermissions.view, dispatch(item(itemId).dispatchId).branchId)
        return download(evidenceId)
    }
    @Transactional(readOnly = true)
    fun downloadPharmacist(evidenceId: UUID, pharmacistId: String): Pair<MerchTaskEvidenceDto, ByteArray> {
        val itemId = jdbc.query("SELECT item_id FROM merch_task_evidence WHERE id=?", { rs, _ -> rs.getObject(1, UUID::class.java) }, evidenceId).singleOrNull() ?: notFound()
        pharmacistExecutor(dispatch(item(itemId).dispatchId), pharmacistId)
        return download(evidenceId)
    }
    private fun download(id: UUID): Pair<MerchTaskEvidenceDto, ByteArray> {
        val location = jdbc.query("SELECT storage_backend,object_key,sha256 FROM merch_task_evidence WHERE id=?",
            { rs, _ -> Triple(rs.getString(1), rs.getString(2), rs.getString(3)) }, id).singleOrNull() ?: notFound()
        val metadata = evidence(id)
        val bytes = storage.read(location.first, location.second, location.third)
        if (bytes.size.toLong()!=metadata.byteSize) conflict("Размер доказательства не соответствует исходной записи; файл сохранён")
        return metadata to bytes
    }

    private fun pharmacistExecutor(d: TaskDispatchRow, pharmacistId: String, lockPharmacist: Boolean = false): TaskExecutor {
        if(staffVisitForDispatch(d.id)!=null) forbidden()
        if (!d.pharmacyBindingVerified || d.pharmacyId.isNullOrBlank() || !d.branchActive ||
            d.assignedPharmacist != null && d.assignedPharmacist != pharmacistId) forbidden()
        val person = jdbc.query("""SELECT l.person_id FROM pharmacist_person_links l
            JOIN unified_persons u ON u.id=l.person_id AND length(btrim(u.verification_reference))>0
            JOIN pharmacists p ON p.id=l.pharmacist_id AND p.status='active' AND p.pharmacy_id=?
            WHERE l.pharmacist_id=? AND length(btrim(l.verification_reference))>0 ${if (lockPharmacist) "FOR UPDATE OF p" else ""}""",
            { rs, _ -> rs.getObject(1, UUID::class.java) }, d.pharmacyId, pharmacistId).singleOrNull() ?: forbidden()
        requireMonthlyPerson(d.id,person)
        val binding = if (d.dueAt == null) null else jdbc.query("""SELECT id FROM merch_executor_bindings WHERE dispatch_id=? AND pharmacist_id=?
            AND pharmacy_id=? AND reward_kzt=? AND task_due_at=?""", { rs, _ -> rs.getObject(1, UUID::class.java) },
            d.id, pharmacistId, d.pharmacyId, d.reward, Timestamp.from(d.dueAt)).singleOrNull()
        if (d.reward > 0 && binding == null) throw AppException(ErrorCode.OTP_NOT_VERIFIED,
            "Подтвердите получателя оплачиваемого задания кодом SMS",HttpStatus.FORBIDDEN)
        return TaskExecutor(person, null, pharmacistId, binding)
    }

    private fun locked(itemId: UUID): Pair<TaskDispatchRow, TaskItemRow> {
        val parent = jdbc.query("SELECT dispatch_id FROM merch_task_items WHERE id=?", { rs, _ -> rs.getObject(1, UUID::class.java) }, itemId).singleOrNull() ?: notFound()
        // Same lock as the still-compatible signed legacy reward callback, before task/pharmacist rows.
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 817105))",parent.toString())
        return dispatch(parent, true) to item(itemId, true)
    }
    private fun capabilityLocked(itemId: UUID, cap: AuthorizedTaskCapability): Pair<TaskDispatchRow,TaskItemRow> {
        // Check membership before acquiring a second dispatch lock: a forged neighbouring item cannot reverse lock order.
        if(item(itemId).dispatchId!=cap.dispatchId) notFound()
        return locked(itemId).also { if(it.first.id!=cap.dispatchId || it.first.branchId!=cap.branchId) notFound() }
    }
    private fun freeReadCapability(rawToken: String): AuthorizedTaskCapability = capabilities.authorize(rawToken).also {
        if(it.rewardAmountKzt!=0L) throw AppException(ErrorCode.FORBIDDEN,"Для оплачиваемого задания подтвердите исполнителя через ePharm",HttpStatus.FORBIDDEN)
    }
    private fun capabilityOwner(attemptId:UUID):UUID? = jdbc.query("SELECT capability_link_id FROM merch_task_attempts WHERE id=?",
        {rs,_->rs.getObject(1,UUID::class.java)},attemptId).singleOrNull()
    private fun preservedSourceReview(attemptId:UUID):Boolean {
        val payload=jdbc.query("SELECT source_payload::text FROM merch_task_attempts WHERE id=?",{rs,_->rs.getString(1)},attemptId).singleOrNull()?:return false
        val tree=json.readTree(payload)
        if(tree["kind"]?.asText()!="epharm.source-task-continuation.v1" || tree["mode"]?.asText()!="review") return false
        return jdbc.queryForObject("""SELECT EXISTS(SELECT 1 FROM merch_task_events WHERE event_type='source_continuation'
            AND details->>'attemptId'=? AND details->>'mode'='review' AND details->>'snapshotSha256'=?)""",Boolean::class.java,
            attemptId.toString(),TaskEvidenceStorage.hash(payload.toByteArray(Charsets.UTF_8)))==true
    }
    private fun publicAttempt(value:MerchTaskAttemptDto)=value.copy(executorPersonId=null,pharmacistId=null,
        review=value.review?.copy(reviewerAccountId=null,reviewerPersonId=null))
    private fun capabilityDto(d:TaskDispatchRow,cap:AuthorizedTaskCapability):MerchTaskDispatchDto {
        val result=dto(d,true)
        val editable=!cap.readOnly
        return result.copy(reward=null,canCancel=false,items=result.items.map { item ->
            val own=item.currentAttempt?.let { capabilityOwner(it.id)==cap.linkId }==true
            val available=editable && item.availableFrom?.isAfter(Instant.now())!=true
            val draft=available && own && item.status=="in_progress" && item.currentAttempt?.status=="draft"
            item.copy(currentAttempt=if(own) item.currentAttempt?.let(::publicAttempt) else null,
                canStart=available && item.status in setOf("assigned","accepted","revision_required"),
                canBlock=false,canComment=false,canCancel=false,
                canUpload=draft && item.currentAttempt!!.evidence.size<12,
                canSubmit=draft && item.currentAttempt!!.evidence.size>=item.requiredPhotos,canReview=false,
                execution=item.execution?.copy(stock=if(own) item.execution.stock else null))
        })
    }
    private fun dispatch(id: UUID, lock: Boolean = false): TaskDispatchRow = jdbc.query("""SELECT d.*,b.epharm_pharmacy_id,
        b.pharmacy_binding_verified,(b.active AND o.active) AS branch_active FROM merch_task_dispatches d JOIN merch_branches b ON b.id=d.branch_id JOIN merch_organizations o ON o.id=b.organization_id
        WHERE d.id=? ${if (lock) "FOR UPDATE OF d" else ""}""", { rs, _ ->
        TaskDispatchRow(rs.uuid("id"), rs.uuid("branch_id"), rs.getString("title"), rs.getString("status"), rs.getTimestamp("due_at")?.toInstant(),
            rs.getLong("reward_amount_kzt"), rs.getString("assigned_pharmacist_id"), rs.getString("source_system"),
            rs.getBoolean("historical_reward_preserved"), rs.getLong("version"), rs.getTimestamp("available_from")?.toInstant(),
            rs.getString("epharm_pharmacy_id"), rs.getBoolean("pharmacy_binding_verified"), rs.getBoolean("branch_active"),
            rs.getString("priority"),rs.getString("task_type"))
    }, id).singleOrNull() ?: notFound()

    private fun item(id: UUID, lock: Boolean = false): TaskItemRow = jdbc.query("SELECT * FROM merch_task_items WHERE id=? ${if (lock) "FOR UPDATE" else ""}", { rs, _ ->
        TaskItemRow(rs.uuid("id"), rs.uuid("dispatch_id"), rs.getString("title"), rs.getString("instructions"), rs.getString("task_type"),
            rs.getString("status"), rs.getBoolean("required"), rs.getInt("required_photo_count"), json.readValue(rs.getString("checklist")),
            rs.getInt("current_attempt"), rs.getLong("version"),rs.getTimestamp("available_from")?.toInstant(),rs.getTimestamp("due_at")?.toInstant())
    }, id).singleOrNull() ?: notFound()

    private fun currentAttempt(item: TaskItemRow): MerchTaskAttemptDto {
        val id = jdbc.query("SELECT id FROM merch_task_attempts WHERE item_id=? AND attempt_no=?", { rs, _ -> rs.uuid("id") }, item.id, item.attempt)
            .singleOrNull() ?: conflict("Попытка не начата")
        return attempt(id)
    }
    private fun attempt(id: UUID): MerchTaskAttemptDto = jdbc.query("SELECT * FROM merch_task_attempts WHERE id=?", { rs, _ ->
        val evidence = jdbc.query("SELECT id FROM merch_task_evidence WHERE attempt_id=? ORDER BY created_at,id", { r, _ -> r.uuid("id") }, id).map(::evidence)
        val review = jdbc.query("SELECT * FROM merch_task_reviews WHERE attempt_id=?", { r, _ -> MerchTaskReviewDto(r.uuid("id"), id,
            r.uuid("reviewer_account_id"), r.uuid("reviewer_person_id"), r.getString("decision"), r.getString("reason"), r.instant("created_at")) }, id).singleOrNull()
        MerchTaskAttemptDto(id, rs.getInt("attempt_no"), rs.getString("status"), rs.getObject("executor_person_id", UUID::class.java),
            rs.getString("pharmacist_id"), rs.getTimestamp("submitted_at")?.toInstant(), rs.instant("created_at"), evidence, review,
            rs.getString("employee_comment"),json.readValue(rs.getString("completed_checklist_ids")),
            when {rs.getObject("executor_person_id")!=null->"verified_person";rs.getObject("capability_link_id")!=null->"capability_unverified"
                rs.getObject("staff_visit_id")!=null->"staff_access_unverified"
                json.readTree(rs.getString("source_payload"))["kind"]?.asText()=="epharm.source-task-continuation.v1"->"source_preserved_unverified";else->"source_unresolved"},rs.getObject("staff_visit_id",UUID::class.java),rs.getString("staff_submitted_name"))
    }, id).singleOrNull() ?: notFound()

    private fun evidence(id: UUID): MerchTaskEvidenceDto = jdbc.query("SELECT * FROM merch_task_evidence WHERE id=?", { rs, _ ->
        val raw:Map<String,Any?> = json.readValue(rs.getString("metadata"))
        val capture=raw.filterKeys {it in setOf("verification","location","captureSource","distanceMeters","receivedAt")}
        MerchTaskEvidenceDto(id, rs.getString("mime_type"), rs.getLong("byte_size"), rs.getString("sha256"), rs.getString("original_filename"), rs.instant("created_at"),
            raw["verification"] as? String ?: "manual_review_required",capture)
    }, id).singleOrNull() ?: notFound()

    private fun dto(d: TaskDispatchRow, money: Boolean, details: Boolean = true, mobileExecutor: TaskExecutor? = null): MerchTaskDispatchDto {
        val itemStates = jdbc.query("SELECT id,status FROM merch_task_items WHERE dispatch_id=? ORDER BY created_at,id",
            { rs, _ -> rs.uuid("id") to rs.getString("status") }, d.id)
        val ids = itemStates.map { it.first }
        val pending = itemStates.filter { it.second == "submitted" }.map { it.first }
        val person = if (mobileExecutor==null) currentAdminPerson() else mobileExecutor.personId
        val execute = person!=null && staffVisitForDispatch(d.id)==null && monthlyPersonAllowed(d.id,person) && (mobileExecutor!=null || access.canMerchBranchPermission(MerchTaskPermissions.execute,d.branchId))
        val now=Instant.now()
        val writable=d.branchActive && d.status !in setOf("completed","cancelled") && d.availableFrom?.isAfter(now)!=true
        val commentAllowed=person!=null && (mobileExecutor!=null || access.canMerchBranchPermission(MerchTaskPermissions.comment,d.branchId))
        val cancelAllowed=mobileExecutor==null && person!=null && d.status !in setOf("completed","cancelled") && access.canMerchBranchPermission(MerchTaskPermissions.cancel,d.branchId) && !hasPostedReward(d.id)
        val rows = if (!details) emptyList() else ids.map { id -> item(id).let {
            val attempt = if (it.attempt==0) null else currentAttempt(it)
            val available=writable && it.availableFrom?.isAfter(now)!=true
            val ownDraft=available && execute && it.status=="in_progress" && attempt?.status=="draft" && attempt.executorPersonId==person
            val sourceReview=attempt!=null && d.source=="MERCH_CRM" && attempt.executorPersonId==null && preservedSourceReview(attempt.id)
            val manualAllowed=mobileExecutor==null && person!=null && allowed { access.requireManualUnverifiedTaskReview(MerchTaskPermissions.review,d.branchId) }
            val review=mobileExecutor==null && person!=null && attempt!=null && it.status=="submitted" &&
                (attempt.status=="submitted" || sourceReview && attempt.status=="source_preserved") &&
                attempt.review==null && if(attempt.executorPersonId!=null) attempt.executorPersonId!=person && allowed {
                    access.requireTaskReviewPermission(MerchTaskPermissions.review,d.branchId,attempt.executorPersonId) }
                else manualAllowed && (sourceReview || d.reward==0L && (capabilityOwner(attempt.id)!=null || staffAttemptOwner(attempt.id,d.id)!=null))
            MerchTaskItemDto(it.id, it.title, it.instructions, it.type, it.status, it.required, it.requiredPhotos, it.checklist,
                it.version, attempt, jdbc.query(
                    "SELECT id FROM merch_task_evidence WHERE item_id=? AND attempt_id IS NULL ORDER BY created_at,id",
                    { rs, _ -> rs.uuid("id") }, it.id).map(::evidence),it.availableFrom,it.dueAt,
                canStart=available && execute && (it.status in setOf("assigned","accepted","revision_required") || it.status=="blocked" && attempt?.executorPersonId==person && attempt.status=="draft"),
                canUpload=ownDraft && attempt!!.evidence.size<12,canSubmit=ownDraft && attempt!!.evidence.size>=it.requiredPhotos,canReview=review,
                canResumeSource=manualAllowed && d.source=="MERCH_CRM" && it.attempt==0 && it.status in setOf("in_progress","submitted"),
                canAdoptSourceReview=manualAllowed && d.source=="MERCH_CRM" && it.attempt==0 && it.status=="submitted",
                execution=execution.policy(it.id,attempt?.id),
                canBlock=ownDraft,blockedReason=jdbc.queryForObject("SELECT blocked_reason FROM merch_task_items WHERE id=?",String::class.java,it.id),
                canComment=commentAllowed,canCancel=cancelAllowed && it.status !in setOf("completed","cancelled"))
        } }
        val reward = if (!money) null else jdbc.query("SELECT * FROM merch_task_rewards WHERE dispatch_id=?", { rs, _ ->
            MerchTaskRewardDto(rs.getString("state"), rs.getLong("amount_kzt"), rs.getString("reason"), rs.getObject("wallet_entry_id", UUID::class.java))
        }, d.id).singleOrNull()
        return MerchTaskDispatchDto(d.id, d.branchId, d.title, d.status, d.dueAt, d.source, d.version, if (money) d.reward else null,
            rows, reward, ids.size, details,d.priority,d.taskType,
            canCancel=cancelAllowed,pendingReviewCount=pending.size,nextPendingReviewItemId=pending.firstOrNull())
    }

    @Transactional(readOnly=true)
    fun commentsAdmin(itemId:UUID,limit:Int,offset:Int):TaskCommentPage {
        val i=item(itemId);access.requireMerchBranchPermission(MerchTaskPermissions.view,dispatch(i.dispatchId).branchId)
        return comments(itemId,currentAdminPerson(),limit,offset)
    }
    @Transactional(readOnly=true)
    fun commentsPharmacist(itemId:UUID,pharmacist:String,limit:Int,offset:Int):TaskCommentPage {
        val i=item(itemId);val person=pharmacistExecutor(dispatch(i.dispatchId),pharmacist).personId
        return comments(itemId,person,limit,offset)
    }
    @Transactional(readOnly=true)
    fun commentsCapability(itemId:UUID,token:String,limit:Int,offset:Int):TaskCommentPage {
        val cap=freeReadCapability(token)
        if(item(itemId).dispatchId!=cap.dispatchId) notFound()
        return comments(itemId,null,limit,offset)
    }
    private fun comments(itemId:UUID,person:UUID?,limit:Int,offset:Int):TaskCommentPage {
        page(limit,offset)
        val rows=jdbc.query("SELECT id,body,author_person_id,author_account_id,created_at FROM merch_task_comments WHERE item_id=? ORDER BY created_at DESC,id LIMIT ? OFFSET ?",{rs,_->
            TaskCommentDto(rs.uuid("id"),rs.getString("body"),if(rs.getObject("author_account_id")==null) "Фармацевт" else "Управляющая команда",rs.getObject("author_person_id",UUID::class.java)==person,rs.instant("created_at"))},itemId,limit+1,offset)
        return TaskCommentPage(rows.take(limit),rows.size>limit)
    }
    @Transactional
    fun commentAdmin(itemId:UUID,req:AddTaskComment,actor:UUID):TaskCommentDto {
        access.lockCurrentActor();val (d,i)=locked(itemId)
        access.requireMerchBranchPermission(MerchTaskPermissions.comment,d.branchId)
        access.requireMerchBranchPermission(MerchTaskPermissions.view,d.branchId)
        return comment(d,i,req,access.currentVerifiedPersonId(),actor,null)
    }
    @Transactional
    fun commentPharmacist(itemId:UUID,req:AddTaskComment,pharmacist:String):TaskCommentDto {
        UnifiedAuthorityLock.acquire(jdbc);val (d,i)=locked(itemId);val executor=pharmacistExecutor(d,pharmacist,true)
        return comment(d,i,req,executor.personId!!,null,pharmacist)
    }
    private fun comment(d:TaskDispatchRow,i:TaskItemRow,req:AddTaskComment,person:UUID,actor:UUID?,pharmacist:String?):TaskCommentDto {
        val text=req.body.trim();if(text.length !in 1..1200 || text.any {it.isISOControl() && it!='\n' && it!='\t'}) invalid("Комментарий: от 1 до 1200 символов")
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,817108))",req.requestId.toString())
        val prior=jdbc.query("SELECT item_id,author_person_id,body,created_at,author_account_id,pharmacist_id FROM merch_task_comments WHERE id=?",{rs,_->
            arrayOf<Any?>(rs.uuid("item_id"),rs.uuid("author_person_id"),rs.getString("body"),rs.instant("created_at"),rs.getObject("author_account_id"),rs.getString("pharmacist_id"))},req.requestId).singleOrNull()
        if(prior!=null) {
            if(prior[0]!=i.id || prior[1]!=person || prior[2]!=text || prior[4]!=actor || prior[5]!=pharmacist) conflict("Идентификатор комментария уже использован")
            return TaskCommentDto(req.requestId,text,if(actor==null) "Фармацевт" else "Управляющая команда",true,prior[3] as Instant)
        }
        val now=Instant.now();jdbc.update("INSERT INTO merch_task_comments(id,item_id,author_person_id,author_account_id,pharmacist_id,body,created_at) VALUES(?,?,?,?,?,?,?)",req.requestId,i.id,person,actor,pharmacist,text,Timestamp.from(now))
        event(d.id,i.id,actor,person,"comment_added",mapOf("commentId" to req.requestId))
        return TaskCommentDto(req.requestId,text,if(actor==null) "Фармацевт" else "Управляющая команда",true,now)
    }
    @Transactional
    fun blockAdmin(itemId:UUID,req:BlockTaskItem,actor:UUID):MerchTaskAttemptDto {
        access.lockCurrentActor();val (d,i)=locked(itemId);access.requireMerchBranchPermission(MerchTaskPermissions.execute,d.branchId)
        return block(d,i,req,TaskExecutor(access.currentVerifiedPersonId(),actor,null,null))
    }
    @Transactional
    fun blockPharmacist(itemId:UUID,req:BlockTaskItem,pharmacist:String):MerchTaskAttemptDto {
        UnifiedAuthorityLock.acquire(jdbc);val (d,i)=locked(itemId)
        return block(d,i,req,pharmacistExecutor(d,pharmacist,true))
    }
    private fun block(d:TaskDispatchRow,i:TaskItemRow,req:BlockTaskItem,executor:TaskExecutor):MerchTaskAttemptDto {
        val reason=req.reason.trim();if(reason.length !in 3..1200 || reason.any(Char::isISOControl)) invalid("Укажите причину препятствия от 3 до 1200 символов")
        if(i.version!=req.expectedItemVersion) conflict("Пункт изменился. Обновите задание")
        if(i.status !in setOf("assigned","accepted","in_progress","revision_required")) conflict("Этот пункт нельзя заблокировать")
        val attempt=start(d,i,executor)
        jdbc.update("UPDATE merch_task_items SET status='blocked',blocked_reason=?,version=version+1,updated_at=now() WHERE id=?",reason,i.id)
        event(d.id,i.id,executor.accountId,executor.personId,"blocked",mapOf("reason" to reason,"attemptId" to attempt.id))
        return attempt
    }
    @Transactional
    fun cancel(id:UUID,req:CancelTaskDispatch,actor:UUID):MerchTaskDispatchDto {
        access.lockCurrentActor();val person=access.currentVerifiedPersonId()
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,817105))",id.toString())
        val d=dispatch(id,true);access.requireMerchBranchPermission(MerchTaskPermissions.view,d.branchId);access.requireMerchBranchPermission(MerchTaskPermissions.cancel,d.branchId)
        val reason=req.reason.trim();if(reason.length !in 3..1200 || reason.any(Char::isISOControl)) invalid("Укажите причину отмены от 3 до 1200 символов")
        if(d.version!=req.expectedVersion || d.status in setOf("completed","cancelled") || hasPostedReward(id)) conflict("Задание уже изменено, завершено или начислено; отмена не выполнена")
        jdbc.queryForList("SELECT id FROM merch_task_items WHERE dispatch_id=? ORDER BY id FOR UPDATE",id)
        jdbc.update("UPDATE merch_task_items SET status='cancelled',version=version+1,updated_at=now() WHERE dispatch_id=? AND status<>'completed'",id)
        jdbc.update("UPDATE merch_task_dispatches SET status='cancelled',version=version+1,updated_at=now() WHERE id=?",id)
        event(id,null,actor,person,"cancelled",mapOf("reason" to reason,"previousVersion" to req.expectedVersion))
        return dto(dispatch(id),access.canMerchBranchPermission(MerchTaskPermissions.financial,d.branchId))
    }

    @Transactional
    fun cancelItem(itemId:UUID,req:CancelTaskItem,actor:UUID):MerchTaskDispatchDto {
        access.lockCurrentActor();val person=access.currentVerifiedPersonId();val (d,i)=locked(itemId)
        access.requireMerchBranchPermission(MerchTaskPermissions.view,d.branchId);access.requireMerchBranchPermission(MerchTaskPermissions.cancel,d.branchId)
        val reason=req.reason.trim();if(reason.length !in 3..1200 || reason.any(Char::isISOControl)) invalid("Укажите причину отмены пункта от 3 до 1200 символов")
        if(i.version!=req.expectedItemVersion || i.status in setOf("completed","cancelled") || d.status in setOf("completed","cancelled") || hasPostedReward(d.id)) conflict("Пункт изменён, завершён или относится к начисленному заданию")
        jdbc.update("UPDATE merch_task_items SET status='cancelled',version=version+1,updated_at=now() WHERE id=?",i.id)
        event(d.id,i.id,actor,person,"item_cancelled",mapOf("reason" to reason,"previousVersion" to req.expectedItemVersion))
        if(i.required && d.reward>0 && jdbc.queryForObject("SELECT count(*) FROM merch_task_rewards WHERE dispatch_id=?",Int::class.java,d.id)==0)
            rewardState(d,"held","cancelled_required_item_requires_HQ")
        refresh(d.id)
        return dto(dispatch(d.id),access.canMerchBranchPermission(MerchTaskPermissions.financial,d.branchId))
    }

    private fun monthlyPersonAllowed(dispatch: UUID,person: UUID?)=jdbc.query("SELECT executor_person_id FROM merch_monthly_visits WHERE dispatch_id=?",{rs,_->rs.uuid("executor_person_id")},dispatch).singleOrNull()?.let{it==person}?:true
    private fun requireMonthlyPerson(dispatch: UUID,person: UUID?) {if(!monthlyPersonAllowed(dispatch,person)) forbidden()}
    private fun hasPostedReward(dispatch: UUID)=jdbc.queryForObject("""SELECT EXISTS(SELECT 1 FROM merch_task_rewards WHERE dispatch_id=? AND (wallet_entry_id IS NOT NULL OR state IN ('credited','already_credited')))
        OR EXISTS(SELECT 1 FROM pharmacist_wallet_entries WHERE source='merch_task' AND source_id=?)""",Boolean::class.java,dispatch,dispatch.toString())==true

    private fun currentAdminPerson(): UUID? = try { access.currentVerifiedPersonId() }
        catch (error: AppException) { if(error.code==ErrorCode.FORBIDDEN) null else throw error }
    private fun allowed(action: () -> Unit): Boolean = try { action();true }
        catch (error: AppException) { if(error.code==ErrorCode.FORBIDDEN) false else throw error }

    private fun requireExecutor(attemptId: UUID, person: UUID?, capabilityLinkId: UUID? = null,staffVisitId:UUID?=null) {
        val count=when {
            staffVisitId!=null && person==null && capabilityLinkId==null -> jdbc.queryForObject("SELECT count(*) FROM merch_task_attempts WHERE id=? AND staff_visit_id=? AND executor_person_id IS NULL AND capability_link_id IS NULL",Int::class.java,attemptId,staffVisitId)
            staffVisitId==null && capabilityLinkId!=null && person==null -> jdbc.queryForObject("SELECT count(*) FROM merch_task_attempts WHERE id=? AND capability_link_id=? AND executor_person_id IS NULL",Int::class.java,attemptId,capabilityLinkId)
            staffVisitId==null && capabilityLinkId==null && person!=null -> jdbc.queryForObject("SELECT count(*) FROM merch_task_attempts WHERE id=? AND executor_person_id=? AND capability_link_id IS NULL AND staff_visit_id IS NULL",Int::class.java,attemptId,person)
            else -> forbidden()
        }
        if(count!=1) forbidden()
    }
    private fun writable(d: TaskDispatchRow) {
        if (!d.branchActive || d.status in setOf("completed", "cancelled") || d.availableFrom?.isAfter(Instant.now()) == true) conflict("Задание не доступно для выполнения")
    }
    private fun available(item: TaskItemRow) { if(item.availableFrom?.isAfter(Instant.now())==true) conflict("Период пункта ещё не начался") }
    private fun refresh(id: UUID) {
        val states = jdbc.query("SELECT status FROM merch_task_items WHERE dispatch_id=? AND status<>'cancelled'", { rs, _ -> rs.getString(1) }, id)
        val status = when {
            states.isEmpty() -> "cancelled"
            states.isNotEmpty() && states.all { it == "completed" } -> "completed"
            states.any { it == "revision_required" } -> "revision_required"
            states.all { it in setOf("completed", "submitted") } -> "submitted"
            else -> "in_progress"
        }
        jdbc.update("UPDATE merch_task_dispatches SET status=?,version=version+1,updated_at=now() WHERE id=?", status, id)
    }
    private fun event(dispatch: UUID, item: UUID?, actor: UUID?, person: UUID?, type: String, details: Map<String, Any>) {
        jdbc.update("INSERT INTO merch_task_events(id,dispatch_id,item_id,actor_account_id,actor_person_id,event_type,details,created_at) VALUES (?,?,?,?,?,?,?::jsonb,now())",
            UUID.randomUUID(), dispatch, item, actor, person, type, json.writeValueAsString(details))
    }
    private fun validateCreate(req: CreateMerchTaskDispatch) {
        if (req.title.trim().length !in 2..160 || req.items.size !in 1..500 || req.items.none { it.required } ||
            req.taskType !in setOf("photo_report","placement","maintenance","inventory","general") ||
            req.priority !in setOf("low","normal","high","urgent") || req.availableFrom?.isAfter(req.dueAt)==true ||
            req.rewardAmountKzt !in 0L..1_000_000L || !req.dueAt.isAfter(Instant.now()) ||
            req.dueAt.isAfter(Instant.now().plusSeconds(366L * 86400)) || req.items.any {
                it.title.trim().length !in 2..240 || it.instructions.length > 4000 || it.requiredPhotos !in 0..12 ||
                    it.checklist.size > 20 || it.checklist.any { value -> value.trim().length !in 2..240 } ||
                    it.stockProducts.size>20 || it.stockProducts.any {p->p.key.length !in 1..64 || p.name.trim().length !in 1..240} ||
                    it.stockProducts.map {p->p.key}.distinct().size!=it.stockProducts.size
            }) invalid("Некорректные поля задания или сроки")
    }
    private fun imageBytes(file: MultipartFile): Pair<String,ByteArray> {
        if (file.size > 20 * 1024 * 1024) throw AppException(ErrorCode.VALIDATION_FAILED,
            "Уменьшите фотографию до 20 МБ",HttpStatus.PAYLOAD_TOO_LARGE)
        if (file.isEmpty || file.originalFilename.orEmpty().length > 255 ||
            file.originalFilename.orEmpty().any(Char::isISOControl)) invalid("Нужна фотография JPG/PNG/WebP до 20 МБ")
        val bytes = file.bytes
        return ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { stream ->
            val readers = ImageIO.getImageReaders(stream)
            if (!readers.hasNext()) invalid("Фотография не читается")
            val reader = readers.next()
            try {
                reader.input = stream
                val mime=when(reader.formatName.lowercase(java.util.Locale.ROOT)) {"jpeg","jpg"->"image/jpeg";"png"->"image/png";"webp"->"image/webp";else->invalid("Файл не является JPG/PNG/WebP")}
                val width = reader.getWidth(0); val height = reader.getHeight(0)
                if (width < 1 || height < 1) invalid("Недопустимый размер фотографии")
                if(width.toLong()*height>12_000_000) throw AppException(ErrorCode.VALIDATION_FAILED,
                    "Уменьшите фотографию до 12 мегапикселей",HttpStatus.PAYLOAD_TOO_LARGE)
                // Decode only after the long pixel-count check, within TaskPhotoGate's single slot.
                // Retain the original file bytes; decoding proves the submitted image is readable.
                val outputType = reader.getImageTypes(0).asSequence().firstOrNull { it.colorModel.pixelSize <= 32 }
                    ?: invalid("Используйте фотографию JPG/PNG/WebP с глубиной цвета до 32 бит")
                val parameters = reader.defaultReadParam.apply { destinationType = outputType }
                val decoded = reader.read(0, parameters) ?: invalid("Фотография не читается")
                try {
                    if (decoded.width != width || decoded.height != height) invalid("Недопустимый размер фотографии")
                } finally { decoded.flush() }
                mime to bytes
            } catch (error: AppException) { throw error }
            catch (_: Exception) { invalid("Фотография не читается") } finally { reader.dispose() }
        }
    }
    private fun videoBytes(file:MultipartFile):ByteArray {
        if(file.isEmpty || file.size>30L*1024*1024) invalid("Нужно видео до 30 МБ")
        val bytes=file.bytes
        val valid=when(file.contentType) {
            "video/mp4","video/quicktime"->bytes.size>=12 && bytes.copyOfRange(4,8).contentEquals("ftyp".toByteArray())
            "video/webm"->bytes.size>=4 && bytes.copyOfRange(0,4).contentEquals(byteArrayOf(0x1a,0x45,0xdf.toByte(),0xa3.toByte()))
            else->false
        }
        if(!valid || file.originalFilename.orEmpty().length>255 || file.originalFilename.orEmpty().any(Char::isISOControl)) invalid("Нужно видео MP4, MOV или WebM")
        return bytes
    }
    private fun page(limit: Int, offset: Int) { if (limit !in 1..50 || offset !in 0..1_000_000) invalid("Некорректная страница") }
    private fun ResultSet.uuid(name: String): UUID = getObject(name, UUID::class.java)
    private fun ResultSet.instant(name: String): Instant = getTimestamp(name).toInstant()
    private fun invalid(message: String): Nothing = throw AppException(ErrorCode.VALIDATION_FAILED, message)
    private fun conflict(message: String): Nothing = throw AppException(ErrorCode.CONFLICT, message, HttpStatus.CONFLICT)
    private fun forbidden(): Nothing = throw AppException(ErrorCode.FORBIDDEN, "Нет проверенного полномочия или личности исполнителя", HttpStatus.FORBIDDEN)
    private fun notFound(): Nothing = throw AppException(ErrorCode.NOT_FOUND, "Задание или доказательство не найдено", HttpStatus.NOT_FOUND)
}
