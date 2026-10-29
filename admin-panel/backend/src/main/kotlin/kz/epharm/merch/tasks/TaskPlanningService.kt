package kz.epharm.merch.tasks

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kz.epharm.auth.access.AdministrativeScope
import kz.epharm.auth.access.UnifiedAuthorityLock
import kz.epharm.merch.placements.repository.PlacementRepository
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@Service
class TaskPlanningService(private val jdbc:JdbcTemplate,private val json:ObjectMapper,
    private val access:AdministrativeScope,private val placements:PlacementRepository,private val tasks:MerchTaskService) {
    val templates=listOf(
        TaskTemplateDto("photo_report","Еженедельный фотоотчёт","Проверьте состояние выкладки и сфотографируйте каждую указанную полку целиком.","all",1,listOf("Сверить код полки","Проверить целостность выкладки","Проверить видимость товара и ценников")),
        TaskTemplateDto("placement","Выкладка рекламного материала","Разместите материал по эталону и приложите фотографию результата.","occupied",1,listOf("Сверить шкаф и полку","Разместить материал по эталону","Проверить видимость товара и ценников")),
        TaskTemplateDto("maintenance","Реструктуризация полки","Сфотографируйте полку до изменений, выполните реструктуризацию и сделайте фото после.","occupied",2,listOf("Сделать фото до изменений","Выполнить реструктуризацию","Сделать фото после изменений")),
        TaskTemplateDto("inventory","Проверка рекламных полок","Проверьте полки по чек-листу и зафиксируйте фактическое состояние.","all",1,listOf("Сверить код полки","Проверить отсутствие посторонних материалов","Зафиксировать результат")),
        TaskTemplateDto("general","Поручение по аптеке","Выполните поручение и отправьте подтверждение ответственному сотруднику.","none",0,listOf("Выполнить поручение","Проверить результат")))
    fun catalog():List<TaskTemplateDto> {access.requireExplicitPermission(MerchTaskPermissions.view);return templates}

    @Transactional
    fun create(req:CreateTemplatedTasks,actor:UUID):MerchTaskBulkResult {
        access.lockCurrentActor();val person=access.currentVerifiedPersonId()
        val template=templates.singleOrNull{it.id==req.templateType}?:invalid("Выберите известный шаблон")
        month(req.month)
        if(req.branchIds.size !in 1..50 || req.shelfMode !in setOf("all","occupied","none") || req.stockProducts.size>20) invalid("Выберите до 50 аптек и допустимый режим полок")
        val ids=req.branchIds.sorted()
        ids.forEach {access.requireMerchBranchPermission(MerchTaskPermissions.view,it);access.requireMerchBranchPermission(MerchTaskPermissions.create,it)
            if(req.rewardAmountKzt>0) access.requireMerchBranchPermission(MerchTaskPermissions.financial,it)}
        val canonical=json.writeValueAsString(req.copy(branchIds=ids.toSet()))
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,817109))",req.requestId.toString())
        val prior=jdbc.query("SELECT created_by,request_payload::text,dispatch_ids::text FROM merch_task_template_receipts WHERE id=?",{rs,_->Triple(rs.getObject(1,UUID::class.java),rs.getString(2),rs.getString(3))},req.requestId).singleOrNull()
        if(prior!=null) {
            if(prior.first!=actor || json.readTree(prior.second)!=json.readTree(canonical)) conflict("Идентификатор назначения уже использован с другими условиями")
            return MerchTaskBulkResult(json.readValue(prior.third))
        }
        placements.lock(ids.toSet(),emptySet())
        val requests=ids.map {branch->active(branch)
            val shelves=if(req.shelfMode=="none") emptyList() else targets(branch,req.month,req.shelfMode)
            if(req.shelfMode!="none" && shelves.isEmpty()) invalid("В одной из аптек нет полок, подходящих выбранному режиму")
            val items=if(req.shelfMode=="none") listOf(CreateMerchTaskItem(template.title,template.instructions,requiredPhotos=template.requiredPhotos,checklist=template.checklist,stockProducts=req.stockProducts))
            else shelves.map{CreateMerchTaskItem("${template.title} · ${it.second}".take(240),template.instructions,requiredPhotos=template.requiredPhotos,checklist=template.checklist,shelfId=it.first,stockProducts=req.stockProducts)}
            CreateMerchTaskDispatch(branch,template.title,req.dueAt,req.rewardAmountKzt,items=items,taskType=template.id)
        }
        if(requests.sumOf{it.items.size}>500) invalid("Снимок назначения превышает 500 пунктов. Сократите выбор аптек")
        val created=requests.map{tasks.insertTask(it,actor,person)}
        jdbc.update("INSERT INTO merch_task_template_receipts(id,created_by,request_payload,dispatch_ids,created_at) VALUES(?,?,?::jsonb,?::jsonb,now())",req.requestId,actor,canonical,json.writeValueAsString(created))
        return MerchTaskBulkResult(created)
    }
    @Transactional(readOnly=true)
    fun visitBranches(pharmacist:String?=null):List<MerchTaskBranchDto> {
        if(pharmacist==null) {
            access.requireExplicitPermission(MerchTaskPermissions.view);access.currentVerifiedPersonId()
            return tasks.branches().filter{it.active && access.canMerchBranchPermission(MerchTaskPermissions.execute,it.id)}.map{it.copy(createEligible=true,canConfigureReward=false)}
        }
        return jdbc.query("""SELECT b.id,b.name,b.city,b.address FROM merch_branches b JOIN merch_organizations o ON o.id=b.organization_id
            JOIN pharmacists p ON p.pharmacy_id=b.epharm_pharmacy_id AND p.status='active'
            JOIN pharmacist_person_links l ON l.pharmacist_id=p.id AND length(btrim(l.verification_reference))>0
            JOIN unified_persons u ON u.id=l.person_id AND length(btrim(u.verification_reference))>0
            WHERE p.id=? AND b.pharmacy_binding_verified AND b.active AND o.active ORDER BY b.id LIMIT 50001""",{rs,_->
            MerchTaskBranchDto(rs.getObject("id",UUID::class.java),rs.getString("name"),rs.getString("city"),rs.getString("address"),true,true,false)},pharmacist).also{if(it.size>50000) invalid("Слишком много аптек")}
    }
    @Transactional
    fun visit(req:StartMonthlyVisit,actor:UUID?=null,pharmacist:String?=null):MonthlyVisitResult {
        if(actor!=null) access.lockCurrentActor() else UnifiedAuthorityLock.acquire(jdbc)
        month(req.month)
        val person=if(actor!=null) {access.requireMerchBranchPermission(MerchTaskPermissions.view,req.branchId);access.requireMerchBranchPermission(MerchTaskPermissions.execute,req.branchId);access.currentVerifiedPersonId()}
        else jdbc.query("""SELECT l.person_id FROM pharmacists p JOIN pharmacist_person_links l ON l.pharmacist_id=p.id
            JOIN unified_persons u ON u.id=l.person_id AND length(btrim(u.verification_reference))>0
            JOIN merch_branches b ON b.epharm_pharmacy_id=p.pharmacy_id AND b.pharmacy_binding_verified
            WHERE p.id=? AND p.status='active' AND b.id=? AND length(btrim(l.verification_reference))>0 FOR UPDATE OF p""",{rs,_->rs.getObject(1,UUID::class.java)},pharmacist,req.branchId).singleOrNull()?:forbidden()
        placements.lock(setOf(req.branchId),emptySet());active(req.branchId)
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,817110))","${req.branchId}:${req.month}:$person")
        val prior=jdbc.query("SELECT dispatch_id FROM merch_monthly_visits WHERE branch_id=? AND placement_month=? AND executor_person_id=?",{rs,_->rs.getObject(1,UUID::class.java)},req.branchId,req.month,person).singleOrNull()
        if(prior!=null) return MonthlyVisitResult(prior,true)
        val shelves=jdbc.query("""SELECT i.id,s.id AS shelf_id,s.code FROM merch_media_plan_items i JOIN merch_media_plans p ON p.id=i.media_plan_id
            JOIN merch_shelves s ON s.id=i.shelf_id WHERE s.branch_id=? AND s.active AND i.placement_month=? AND i.status='approved' AND p.status='approved'
            ORDER BY s.id,i.id LIMIT 501 FOR UPDATE OF s""",{rs,_->Triple(rs.getObject("id",UUID::class.java),rs.getObject("shelf_id",UUID::class.java),rs.getString("code"))},req.branchId,req.month)
        if(shelves.isEmpty()) invalid("На этот месяц нет подтверждённых размещений в аптеке")
        if(shelves.size>500) invalid("Месячный визит превышает 500 пунктов")
        val zone=ZoneId.of("Asia/Almaty");val due=req.month.plusMonths(1).atStartOfDay(zone).toInstant().minusSeconds(1)
        if(!due.isAfter(Instant.now())) conflict("Срок выбранного месяца уже завершён")
        val items=shelves.map{CreateMerchTaskItem("Фотоотчёт · ${it.third}","Сфотографируйте полку целиком; укажите фактические остатки.",requiredPhotos=1,shelfId=it.second,mediaPlanItemId=it.first)}
        val id=tasks.insertTask(CreateMerchTaskDispatch(req.branchId,"Месячный визит · ${req.month}",due,assignedPharmacistId=pharmacist,items=items,availableFrom=req.month.atStartOfDay(zone).toInstant()),actor,person)
        jdbc.update("UPDATE merch_task_dispatches SET task_type='monthly_visit' WHERE id=?",id)
        jdbc.update("INSERT INTO merch_monthly_visits(branch_id,placement_month,executor_person_id,dispatch_id,created_at) VALUES(?,?,?,?,now())",req.branchId,req.month,person,id)
        return MonthlyVisitResult(id,false)
    }
    private fun targets(branch:UUID,month:LocalDate,mode:String):List<Pair<UUID,String>> = jdbc.query("""SELECT s.id,s.code FROM merch_shelves s WHERE s.branch_id=? AND s.active
        ${if(mode=="occupied") "AND EXISTS(SELECT 1 FROM merch_media_plan_items i JOIN merch_media_plans p ON p.id=i.media_plan_id WHERE i.shelf_id=s.id AND i.placement_month=? AND i.status='approved' AND p.status='approved')" else ""}
        ORDER BY s.id LIMIT 501 FOR UPDATE OF s""",{rs,_->rs.getObject(1,UUID::class.java) to rs.getString(2)},*if(mode=="occupied") arrayOf(branch,month) else arrayOf(branch))
    private fun active(branch:UUID) {if(jdbc.queryForObject("SELECT count(*) FROM merch_branches b JOIN merch_organizations o ON o.id=b.organization_id WHERE b.id=? AND b.active AND o.active",Int::class.java,branch)!=1) forbidden()}
    private fun month(value:LocalDate) {if(value.dayOfMonth!=1) invalid("Месяц должен начинаться с первого числа")}
    private fun invalid(message:String):Nothing=throw AppException(ErrorCode.VALIDATION_FAILED,message)
    private fun conflict(message:String):Nothing=throw AppException(ErrorCode.CONFLICT,message,HttpStatus.CONFLICT)
    private fun forbidden():Nothing=throw AppException(ErrorCode.FORBIDDEN,"Нет доступа к аптеке или проверенной личности",HttpStatus.FORBIDDEN)
}
