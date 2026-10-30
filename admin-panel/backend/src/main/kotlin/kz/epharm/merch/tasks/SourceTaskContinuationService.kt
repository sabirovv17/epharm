package kz.epharm.merch.tasks

import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kz.epharm.auth.access.AdministrativeScope
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class ContinueSourceTask(val expectedItemVersion:Long,val mode:String,val reason:String) {
    @JsonAnySetter fun rejectUnknown(key:String,value:Any?) {throw IllegalArgumentException("Unsupported source continuation field")}
}
data class SourceTaskContinuation(val itemId:UUID,val attemptId:UUID,val attemptNumber:Int,val itemVersion:Long,
    val status:String,val mode:String,val duplicate:Boolean,val executorIdentityStatus:String="source_preserved_unverified")

/** Explicit HQ adoption records unknown source execution without assigning a person or replaying a reward. */
@Service
class SourceTaskContinuationService(private val jdbc:JdbcTemplate,private val access:AdministrativeScope,private val json:ObjectMapper) {
    @Transactional
    fun continueItem(itemId:UUID,request:ContinueSourceTask,actor:UUID):SourceTaskContinuation {
        access.lockCurrentActor()
        val reason=request.reason.trim()
        if(request.expectedItemVersion<0 || request.mode !in setOf("resume","review") || reason.length !in 3..1200) invalid()
        val dispatch=jdbc.query("SELECT dispatch_id FROM merch_task_items WHERE id=?",{rs,_->rs.getObject(1,UUID::class.java)},itemId).singleOrNull()?:notFound()
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,817105))",dispatch.toString())
        val parent=jdbc.queryForList("SELECT source_system,branch_id FROM merch_task_dispatches WHERE id=? FOR UPDATE",dispatch).singleOrNull()?:notFound()
        val item=jdbc.queryForList("SELECT status,current_attempt,version FROM merch_task_items WHERE id=? FOR UPDATE",itemId).singleOrNull()?:notFound()
        val branch=parent["branch_id"] as UUID
        access.requireManualUnverifiedTaskReview(MerchTaskPermissions.review,branch)
        val person=access.currentVerifiedPersonId()
        if(parent["source_system"]!="MERCH_CRM") conflict("Продолжение исходной истории доступно только перенесённым заданиям")
        val previous=jdbc.queryForList("""SELECT details::text FROM merch_task_events WHERE item_id=? AND event_type='source_continuation'
            ORDER BY created_at,id LIMIT 1""",itemId).singleOrNull()
        if(previous!=null) {
            val event=json.readTree(previous["details"] as String)
            if(event["expectedItemVersion"]?.asLong()==request.expectedItemVersion && event["mode"]?.asText()==request.mode &&
                event["reason"]?.asText()==reason && event["actorId"]?.asText()==actor.toString())
                return SourceTaskContinuation(itemId,UUID.fromString(event["attemptId"].asText()),1,(item["version"] as Number).toLong(),
                    item["status"] as String,request.mode,true)
            conflict("Исходная попытка уже сохранена: загрузите актуальную историю")
        }
        val version=(item["version"] as Number).toLong()
        val status=item["status"] as String
        if(version!=request.expectedItemVersion || (item["current_attempt"] as Number).toInt()!=0 || status !in setOf("in_progress","submitted") ||
            request.mode=="review" && status!="submitted") conflict("Исходное состояние изменилось: требуется повторная сверка HQ")
        if(version==Long.MAX_VALUE) conflict("Версия задания вне допустимого диапазона")
        val snapshot=jdbc.queryForObject("""SELECT jsonb_build_object('kind','epharm.source-task-continuation.v1','mode',?::text,
            'item',to_jsonb(i),'legacyEvidence',COALESCE((SELECT jsonb_agg(to_jsonb(e) ORDER BY e.created_at,e.id)
                FROM merch_task_evidence e WHERE e.item_id=i.id AND e.attempt_id IS NULL),'[]'::jsonb),
            'sourceEvents',COALESCE((SELECT jsonb_agg(e.id ORDER BY e.created_at,e.id) FROM merch_task_events e
                WHERE e.item_id=i.id AND e.source_payload<>'{}'::jsonb),'[]'::jsonb))::text
            FROM merch_task_items i WHERE i.id=?""",String::class.java,request.mode,itemId)!!
        val tree=json.readTree(snapshot)
        val original=tree["item"]["source_payload"]
        val submitted=original["submitted_at"]?.takeIf {it.isTextual && it.asText().isNotBlank()}?.asText()?.let { value ->
            runCatching {Instant.parse(value)}.getOrElse { conflict("Исходное время отправки не распознано: требуется сверка HQ") }
        }
        val completed=original["_unification_checklist_source"]?.takeIf {it.isArray}?.filter { it.hasNonNull("completed_at") }
            ?.mapNotNull { it["id"]?.asText() }.orEmpty()
        val comment=original["employee_comment"]?.takeIf {it.isTextual}?.asText().orEmpty()
        if(comment.length>1200) conflict("Исходный комментарий выходит за допустимый диапазон")
        val id=UUID.randomUUID()
        jdbc.update("""INSERT INTO merch_task_attempts(id,item_id,attempt_no,status,completed_checklist_ids,employee_comment,submitted_at,created_at,source_payload)
            VALUES(?,?,1,'source_preserved',?::jsonb,?,?,now(),?::jsonb)""",id,itemId,json.writeValueAsString(completed),comment,submitted?.let(Timestamp::from),snapshot)
        val next=if(request.mode=="resume") "revision_required" else "submitted"
        if(jdbc.update("UPDATE merch_task_items SET current_attempt=1,status=?,version=version+1,updated_at=now() WHERE id=? AND version=? AND current_attempt=0",next,itemId,version)!=1)
            conflict("Исходное состояние изменилось")
        if(request.mode=="resume") jdbc.update("UPDATE merch_task_dispatches SET status='revision_required',version=version+1,updated_at=now() WHERE id=?",dispatch)
        jdbc.update("""INSERT INTO merch_task_events(id,dispatch_id,item_id,actor_account_id,actor_person_id,event_type,details,created_at)
            VALUES(?,?,?,?,?,'source_continuation',?::jsonb,now())""",UUID.randomUUID(),dispatch,itemId,actor,person,json.writeValueAsString(mapOf(
                "attemptId" to id,"actorId" to actor,"mode" to request.mode,"reason" to reason,"expectedItemVersion" to version,
                "snapshotSha256" to TaskEvidenceStorage.hash(snapshot.toByteArray(Charsets.UTF_8)),"executorIdentityStatus" to "source_preserved_unverified")))
        return SourceTaskContinuation(itemId,id,1,version+1,next,request.mode,false)
    }
    private fun invalid():Nothing=throw AppException(ErrorCode.VALIDATION_FAILED,"Укажите режим продолжения, версию пункта и причину от 3 до 1200 символов")
    private fun conflict(message:String):Nothing=throw AppException(ErrorCode.CONFLICT,message,HttpStatus.CONFLICT)
    private fun notFound():Nothing=throw AppException(ErrorCode.NOT_FOUND,"Исходное задание не найдено",HttpStatus.NOT_FOUND)
}
