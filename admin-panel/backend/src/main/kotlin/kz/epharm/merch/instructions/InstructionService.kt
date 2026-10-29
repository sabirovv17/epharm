package kz.epharm.merch.instructions

import com.fasterxml.jackson.databind.ObjectMapper
import kz.epharm.auth.access.AdministrativeScope
import kz.epharm.merch.delivery.NativeDeliveryEligibility
import kz.epharm.merch.placements.dto.PlacementActor
import kz.epharm.merch.placements.dto.PlacementStatus
import kz.epharm.merch.placements.repository.PlacementRepository
import kz.epharm.merch.placements.service.PlacementPermissions
import kz.epharm.merch.producer.auth.ProducerAuthService
import kz.epharm.merch.producer.auth.ProducerPrincipal
import kz.epharm.merch.tasks.TaskEvidenceStorage
import kz.epharm.merch.tasks.TaskPhotoGate
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.apache.pdfbox.Loader
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Service
class InstructionService(private val jdbc: JdbcTemplate,private val named: NamedParameterJdbcTemplate,
    private val access: AdministrativeScope,private val placements: PlacementRepository,private val sessions: ProducerAuthService,
    private val storage: TaskEvidenceStorage,private val json: ObjectMapper) {
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    fun list(actor: PlacementActor,producer: UUID?,plan: UUID?,status: String?,limit: Int,offset: Int): InstructionPage {
        page(limit,offset);readActor(actor)
        if(status!=null&&status !in setOf("pending","approved","rejected")) invalid("Неизвестный статус материала")
        val where=mutableListOf<String>();val args=mutableMapOf<String,Any?>()
        if(producer!=null){where+="m.producer_id=:producer";args["producer"]=producer}
        if(actor is PlacementActor.Producer){where+="m.producer_id=:own";args["own"]=actor.customerId}
        if(plan!=null){where+="EXISTS(SELECT 1 FROM merch_instruction_plans f WHERE f.material_id=m.id AND f.plan_id=:plan)";args["plan"]=plan}
        if(status!=null){where+="m.status=:status";args["status"]=status}
        val rows=contexts(if(where.isEmpty())""else" WHERE "+where.joinToString(" AND "),args)
            .filter{canContext(actor,"view",it)}
        return InstructionPage(rows.drop(offset).take(limit).map{dto(it,actor)},rows.size.toLong(),limit,offset)
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    fun targets(actor: PlacementActor,producer: UUID?,limit: Int,offset: Int): InstructionTargetPage {
        page(limit,offset);readActor(actor)
        val args=mutableMapOf<String,Any?>();val where=mutableListOf("p.producer_id IS NOT NULL")
        if(producer!=null){where+="p.producer_id=:producer";args["producer"]=producer}
        if(actor is PlacementActor.Producer){where+="p.producer_id=:own";args["own"]=actor.customerId}
        val rows=named.query("""SELECT p.id,p.producer_id,p.placement_month,c.name,array_remove(array_agg(DISTINCT s.branch_id),NULL) AS branches
            FROM merch_media_plans p JOIN merch_producers c ON c.id=p.producer_id LEFT JOIN merch_media_plan_items i ON i.media_plan_id=p.id
            LEFT JOIN merch_shelves s ON s.id=i.shelf_id WHERE ${where.joinToString(" AND ")} AND c.active
            GROUP BY p.id,c.name ORDER BY p.created_at DESC,p.id""",args) {rs,_->
            Target(rs.getObject("id",UUID::class.java),rs.getObject("producer_id",UUID::class.java),rs.getString("name"),rs.getObject("placement_month",java.time.LocalDate::class.java),uuids(rs,"branches").toSet())
        }.filter{can(actor,"view",it.branches,it.producer)&&can(actor,"edit",it.branches,it.producer)}
        return InstructionTargetPage(rows.drop(offset).take(limit).map{InstructionTarget(it.id,it.producer,it.name,it.month,"${it.name?:"Компания не указана"} · ${it.month} · №${it.id.toString().take(8)}",true)},rows.size.toLong(),limit,offset)
    }
    @Transactional
    fun create(actor: PlacementActor,request: CreateInstruction,file: MultipartFile): InstructionDto = TaskPhotoGate.run {
        lockActor(actor)
        val title=text(request.title,2,160);val instructions=text(request.instructions,3,4000,true)
        if(!request.dueAt.isAfter(Instant.now())) invalid("Укажите будущий срок выполнения")
        if(request.planIds.isEmpty()||request.planIds.size>50||request.planIds.distinct().size!=request.planIds.size) invalid("Выберите от 1 до 50 медиапланов без повторов")
        val beforePlans=request.planIds.sorted().map{placements.plan(it)?:missing()}
        val producers=beforePlans.map{it.producerId}.toSet()
        val producer=when(actor){is PlacementActor.Producer->{if(request.producerId!=null) invalid("Производитель определяется текущей сессией");actor.customerId}
            is PlacementActor.Internal->request.producerId?:invalid("Выберите производителя");else->forbidden()}
        if(producers!=setOf(producer)) forbidden()
        val customer=placements.producer(producer,true)?:forbidden();if(!customer.active) forbidden()
        val beforeItems=request.planIds.flatMap{placements.items(it)}
        val branches=beforeItems.map{it.branchId}.toSet()
        require(actor,"view",branches,producer);require(actor,"edit",branches,producer)
        placements.lock(branches,beforeItems.map{it.shelfId}.toSet())
        val plans=request.planIds.sorted().map{placements.plan(it,true)?:missing()}
        val items=request.planIds.sorted().flatMap{placements.items(it,true)}
        if(plans.any{it.producerId!=producer}||items.map{it.branchId}.toSet()!=branches||items.map{it.shelfId}.toSet()!=beforeItems.map{it.shelfId}.toSet()) conflict("Состав медиапланов изменился. Обновите данные")
        require(actor,"view",branches,producer);require(actor,"edit",branches,producer)
        lockMaterial(request.id)
        val bytes=pdf(file);val sha=TaskEvidenceStorage.hash(bytes)
        val intent=TaskEvidenceStorage.hash(json.writeValueAsBytes(linkedMapOf("producerId" to producer,"plans" to request.planIds.sorted(),"title" to title,"instructions" to instructions,"dueAt" to request.dueAt,"sha256" to sha,"actor" to actorKey(actor))))
        context(request.id)?.let{existing->
            if(!canContext(actor,"view",existing))forbidden()
            if(existing.proof!=intent) conflict("Этот идентификатор уже относится к другому материалу")
            return@run dto(existing,actor)
        }
        val key="instructions/${request.id}/$sha.pdf";storage.write(key,bytes)
        val now=Instant.now()
        val payload=json.writeValueAsString(mapOf("creationIntentSha256" to intent))
        jdbc.update("""INSERT INTO merch_instruction_materials(id,source_system,source_id,source_payload,producer_id,title,instructions,due_at,status,
            storage_backend,object_key,sha256,byte_size,original_filename,created_by_admin,created_at,updated_at)
            VALUES(?,'EPHARM',?,?::jsonb,?,?,?,?,'pending','private_host',?,?,?,?,?,?,?)""",request.id,request.id,payload,producer,title,instructions,
            Timestamp.from(request.dueAt),key,sha,bytes.size.toLong(),file.originalFilename?.take(200),(actor as? PlacementActor.Internal)?.accountId,Timestamp.from(now),Timestamp.from(now))
        request.planIds.sorted().forEach{jdbc.update("INSERT INTO merch_instruction_plans(material_id,plan_id) VALUES(?,?)",request.id,it)}
        event(request.id,actor,"uploaded",mapOf("sha256" to sha,"planIds" to request.planIds.sorted()),now)
        dto(context(request.id)?:missing(),actor)
    }
    @Transactional
    fun review(actor: PlacementActor.Internal,id: UUID,request: ReviewInstruction): InstructionDto {
        lockActor(actor)
        if(request.decision !in setOf("approved","rejected")) invalid("Выберите решение по материалу")
        val reason=text(request.reason,3,1200)
        val before=context(id)?:missing();val producer=before.producer?:forbidden()
        if(placements.producer(producer,true)?.active!=true) forbidden()
        val beforeItems=before.plans.flatMap{placements.items(it)}
        placements.lock(before.branches,beforeItems.map{it.shelfId}.toSet())
        val plans=before.plans.sorted().map{placements.plan(it,true)?:missing()}
        val items=before.plans.sorted().flatMap{placements.items(it,true)}
        lockMaterial(id)
        jdbc.queryForList("SELECT id FROM merch_instruction_materials WHERE id=? FOR UPDATE",id)
        val current=context(id)?:missing()
        if(current.ownerMismatch)forbidden()
        if(plans.any{it.producerId!=producer}||current.producer!=producer||current.branches!=before.branches||current.plans!=before.plans||items.map{it.branchId}.toSet()!=current.branches) conflict("Связи материала изменились. Обновите данные")
        require(actor,"view",current.branches,producer);require(actor,"approve",current.branches,producer)
        if(current.status!="pending"||current.revision!=request.expectedRevision) conflict("Материал уже проверен или изменился")
        val now=Instant.now()
        val approved=items.filter{it.status==PlacementStatus.approved}.map{it.branchId}.toSet()
        if(request.decision=="approved") {
            if(!current.due.isAfter(now)||approved.isEmpty()) conflict("Нужны подтверждённые размещения и будущий срок инструктажа")
            for(branch in approved) {
                val parent=plans.firstOrNull{plan->plan.status==PlacementStatus.approved&&placements.items(plan.id).any{it.branchId==branch&&it.status==PlacementStatus.approved}}
                    ?:conflict("Родительский медиаплан ещё не подтверждён целиком")
                val ready=jdbc.queryForObject("""SELECT b.active AND o.active AND b.pharmacy_binding_verified AND b.epharm_pharmacy_id IS NOT NULL
                    FROM merch_branches b JOIN merch_organizations o ON o.id=b.organization_id WHERE b.id=?""",Boolean::class.java,branch)
                if(ready!=true) conflict("Для одной из аптек не подтверждена доставка. Проверьте аптеку и привязку ePharm")
                createPacket(current,branch,parent.id,actor.accountId,now)
            }
            val valid=jdbc.queryForObject("SELECT bool_and(${NativeDeliveryEligibility.sql("d")}) FROM merch_task_dispatches d WHERE d.source_system='EPHARM' AND d.source_payload->>'instructionMaterialId'=?",Boolean::class.java,
                Timestamp.from(now),Timestamp.from(now),Timestamp.from(now),id.toString())
            if(valid!=true) conflict("Не удалось подготовить доставляемые задания. Решение не сохранено")
        }
        jdbc.update("UPDATE merch_instruction_materials SET status=?,revision=revision+1,reviewed_by_admin=?,review_note=?,reviewed_at=?,updated_at=? WHERE id=?",request.decision,actor.accountId,reason,Timestamp.from(now),Timestamp.from(now),id)
        event(id,actor,"reviewed",mapOf("decision" to request.decision,"reason" to reason,"previousRevision" to request.expectedRevision,"packetBranches" to if(request.decision=="approved")approved else emptySet<UUID>()),now)
        return dto(context(id)?:missing(),actor)
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    fun pdf(actor: PlacementActor,id: UUID): InstructionFile {
        val row=visible(actor,id)
        val file=jdbc.queryForMap("SELECT storage_backend,object_key,sha256,byte_size FROM merch_instruction_materials WHERE id=?",id)
        val bytes=storage.read(file["storage_backend"].toString(),file["object_key"].toString(),file["sha256"] as String?)
        if(bytes.size<5||!bytes.copyOfRange(0,5).contentEquals("%PDF-".toByteArray())||file["byte_size"]?.let{(it as Number).toLong()!=bytes.size.toLong()}==true) conflict("Контроль PDF не пройден")
        return InstructionFile("application/pdf",bytes,TaskEvidenceStorage.hash(bytes))
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    fun results(actor: PlacementActor,id: UUID,limit: Int,offset: Int): InstructionResultPage {
        page(limit,offset);val material=visible(actor,id)
        if(material.status!="approved") return InstructionResultPage(emptyList(),0,limit,offset)
        val total=jdbc.queryForObject("SELECT count(*) $resultsFrom",Long::class.java,id)!!
        val rows=jdbc.query("""SELECT e.id,d.branch_id,b.name AS branch_name,i.title,e.mime_type,e.byte_size,r.created_at AS approved_at
            $resultsFrom ORDER BY r.created_at DESC,e.id LIMIT ? OFFSET ?""",{rs,_->InstructionResult(rs.getObject("id",UUID::class.java),rs.getObject("branch_id",UUID::class.java),rs.getString("branch_name"),rs.getString("title"),rs.getString("mime_type"),rs.getLong("byte_size"),rs.getTimestamp("approved_at").toInstant())},id,limit,offset)
        return InstructionResultPage(rows,total,limit,offset)
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    fun resultFile(actor: PlacementActor,id: UUID,evidence: UUID): InstructionFile {
        val material=visible(actor,id);if(material.status!="approved") missing()
        val row=jdbc.queryForList("SELECT e.storage_backend,e.object_key,e.sha256,e.byte_size,e.mime_type $resultsFrom AND e.id=?",id,evidence).singleOrNull()?:missing()
        val bytes=storage.read(row["storage_backend"].toString(),row["object_key"].toString(),row["sha256"] as String?)
        if((row["byte_size"] as Number).toLong()!=bytes.size.toLong()) conflict("Контроль размера видео не пройден")
        return InstructionFile(row["mime_type"].toString(),bytes,TaskEvidenceStorage.hash(bytes))
    }
    private fun visible(actor: PlacementActor,id: UUID): Context { readActor(actor);val row=context(id)?:missing();if(!canContext(actor,"view",row))forbidden();return row }
    private fun createPacket(material: Context,branch: UUID,parent: UUID,actor: UUID,now: Instant) {
        val dispatch=UUID.randomUUID();val item=UUID.randomUUID();val title="Инструктаж: ${material.title}".take(160)
        jdbc.update("""INSERT INTO merch_task_dispatches(id,source_system,source_origin,source_id,source_payload,branch_id,title,status,task_type,available_from,due_at,
            reward_amount_kzt,created_by,created_at,updated_at,media_plan_id,assignment_ready_at)
            VALUES(?,'EPHARM','https://epharm.inkar.kz',?,?::jsonb,?,?,'ready','other',?,?,0,?,?,?,?,?)""",dispatch,dispatch,json.writeValueAsString(mapOf("instructionMaterialId" to material.id)),branch,title,Timestamp.from(now),Timestamp.from(material.due),actor,Timestamp.from(now),Timestamp.from(now),parent,Timestamp.from(now))
        jdbc.update("""INSERT INTO merch_task_items(id,dispatch_id,source_id,source_payload,title,instructions,task_type,status,required,required_photo_count,
            available_from,due_at,training_material_id,created_at,updated_at) VALUES(?,?,?,'{}'::jsonb,?,?,'other','assigned',true,0,?,?,?,?,?)""",item,dispatch,item,title,
            material.instructions,Timestamp.from(now),Timestamp.from(material.due),material.id,Timestamp.from(now),Timestamp.from(now))
    }
    private fun lockActor(actor: PlacementActor) { when(actor){is PlacementActor.Internal->{access.lockCurrentActor();access.currentVerifiedPersonId()};is PlacementActor.Producer->sessions.lockForWrite(ProducerPrincipal(actor.customerId,actor.sessionId));else->forbidden()} }
    private fun readActor(actor: PlacementActor) { when(actor){is PlacementActor.Internal->access.requireExplicitPermission(PlacementPermissions.key("view"));is PlacementActor.Producer->if(placements.producer(actor.customerId)?.active!=true)forbidden();else->forbidden()} }
    private fun can(actor: PlacementActor,action: String,branches: Set<UUID>,producer: UUID?): Boolean=when(actor){
        is PlacementActor.Internal->if(producer==null) action=="view"&&access.canAllMerchPlacementPermission(PlacementPermissions.key(action)) else if(branches.isEmpty())access.canAllMerchPlacementForProducerPermission(PlacementPermissions.key(action),producer) else access.canMerchPlacementPermission(PlacementPermissions.key(action),branches,producer)
        is PlacementActor.Producer->producer==actor.customerId&&action in setOf("view","edit")
        else->false }
    private fun require(actor: PlacementActor,action: String,branches: Set<UUID>,producer: UUID?) {if(!can(actor,action,branches,producer))forbidden()}
    private fun canContext(actor: PlacementActor,action: String,row: Context)=if(row.ownerMismatch)
        actor is PlacementActor.Internal&&action=="view"&&access.canAllMerchPlacementPermission(PlacementPermissions.key("view")) else can(actor,action,row.branches,row.producer)
    private fun actorKey(actor: PlacementActor)=when(actor){is PlacementActor.Internal->"admin:${actor.accountId}";is PlacementActor.Producer->"producer:${actor.customerId}";else->forbidden()}
    private fun lockMaterial(id: UUID){jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,718098))",id.toString())}
    private fun event(id: UUID,actor: PlacementActor,operation: String,details: Map<String,Any?>,now: Instant) {jdbc.update("INSERT INTO merch_instruction_events(id,material_id,actor_admin_id,actor_producer_id,operation,details,created_at) VALUES(?,?,?,?,?,?::jsonb,?)",UUID.randomUUID(),id,(actor as? PlacementActor.Internal)?.accountId,(actor as? PlacementActor.Producer)?.customerId,operation,json.writeValueAsString(details),Timestamp.from(now))}
    private fun dto(row: Context,actor: PlacementActor): InstructionDto {
        val total=jdbc.queryForObject("SELECT count(*) FROM merch_task_items WHERE training_material_id=?",Long::class.java,row.id)!!
        val completed=jdbc.queryForObject("SELECT count(*) FROM merch_task_items WHERE training_material_id=? AND status='completed'",Long::class.java,row.id)!!
        return InstructionDto(row.id,row.producer,row.title,row.instructions,row.due,row.status,row.revision,row.plans.sorted(),row.reviewNote,row.created,
            row.status=="pending"&&canContext(actor,"approve",row),completed,total)
    }
    private fun context(id: UUID)=contexts(" WHERE m.id=:id",mapOf("id" to id)).singleOrNull()
    private fun contexts(where: String,args: Map<String,Any?>): List<Context> = named.query("""SELECT m.*,array_remove(array_agg(DISTINCT l.plan_id),NULL) AS plans,
        array_remove(array_agg(DISTINCT s.branch_id)||array_agg(DISTINCT d.branch_id),NULL) AS branches,
        EXISTS(SELECT 1 FROM merch_instruction_plans owner_link JOIN merch_media_plans owner_plan ON owner_plan.id=owner_link.plan_id
            WHERE owner_link.material_id=m.id AND owner_plan.producer_id IS DISTINCT FROM m.producer_id) AS owner_mismatch
        FROM merch_instruction_materials m
        LEFT JOIN merch_instruction_plans l ON l.material_id=m.id LEFT JOIN merch_media_plan_items i ON i.media_plan_id=l.plan_id
        LEFT JOIN merch_shelves s ON s.id=i.shelf_id LEFT JOIN merch_task_items task ON task.training_material_id=m.id
        LEFT JOIN merch_task_dispatches d ON d.id=task.dispatch_id $where GROUP BY m.id ORDER BY m.created_at DESC,m.id""",args) {rs,_->Context(rs.getObject("id",UUID::class.java),rs.getObject("producer_id",UUID::class.java),
            rs.getString("title"),rs.getString("instructions"),rs.getTimestamp("due_at").toInstant(),rs.getString("status"),rs.getLong("revision"),uuids(rs,"plans").toSet(),uuids(rs,"branches").toSet(),rs.getString("review_note"),rs.getTimestamp("created_at").toInstant(),json.readTree(rs.getString("source_payload")).path("creationIntentSha256").asText(),rs.getBoolean("owner_mismatch")) }
    private fun uuids(rs: ResultSet,column: String)=(rs.getArray(column).array as Array<*>).map {if(it is UUID)it else UUID.fromString(it.toString())}
    private data class Context(val id: UUID,val producer: UUID?,val title: String,val instructions: String,val due: Instant,val status: String,val revision: Long,val plans: Set<UUID>,val branches: Set<UUID>,val reviewNote: String?,val created: Instant,val proof: String,val ownerMismatch: Boolean)
    private data class Target(val id: UUID,val producer: UUID,val name: String?,val month: java.time.LocalDate,val branches: Set<UUID>)
    private fun page(limit: Int,offset: Int){if(limit !in 1..50||offset !in 0..1_000_000)invalid("Некорректная страница")}
    private fun text(value: String,min: Int,max: Int,multiline: Boolean=false)=value.trim().also{if(it.length !in min..max||it.any{c->c.isISOControl()&&!(multiline&&c in setOf('\n','\r','\t'))})invalid("Проверьте название, инструкцию и основание решения")}
    private fun pdf(file: MultipartFile): ByteArray {
        if(file.isEmpty||file.size>20L*1024*1024)throw AppException(ErrorCode.VALIDATION_FAILED,"Нужен PDF до 20 МБ",HttpStatus.PAYLOAD_TOO_LARGE)
        if(file.originalFilename.orEmpty().length>200||file.originalFilename.orEmpty().any(Char::isISOControl))invalid("Проверьте имя файла")
        val bytes=file.bytes
        if(bytes.size<5||!bytes.copyOfRange(0,5).contentEquals("%PDF-".toByteArray()))invalid("Файл не является PDF")
        try{Loader.loadPDF(bytes).use{if(it.isEncrypted||it.numberOfPages<1)invalid("Нужен читаемый PDF без пароля")}}catch(e: AppException){throw e}catch(_:Exception){invalid("PDF не читается")}
        return bytes
    }
    private fun missing(): Nothing=throw AppException(ErrorCode.NOT_FOUND,"Материал или подтверждённый результат не найден",HttpStatus.NOT_FOUND)
    private fun forbidden(): Nothing=throw AppException(ErrorCode.FORBIDDEN,"Нет доступа к материалу",HttpStatus.FORBIDDEN)
    private fun invalid(message: String): Nothing=throw AppException(ErrorCode.VALIDATION_FAILED,message)
    private fun conflict(message: String): Nothing=throw AppException(ErrorCode.CONFLICT,message,HttpStatus.CONFLICT)
    private val resultsFrom="""FROM merch_task_evidence e JOIN merch_task_attempts a ON a.id=e.attempt_id AND e.item_id=a.item_id
        JOIN merch_task_items i ON i.id=a.item_id JOIN merch_task_dispatches d ON d.id=i.dispatch_id
        JOIN merch_branches b ON b.id=d.branch_id JOIN merch_task_reviews r ON r.attempt_id=a.id AND r.item_id=a.item_id
        WHERE i.training_material_id=? AND i.status='completed' AND a.status='approved' AND r.decision='approve'
        AND e.mime_type IN('video/mp4','video/quicktime','video/webm')"""
}
