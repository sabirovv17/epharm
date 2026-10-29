package kz.epharm.merch.commercial

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import kz.epharm.auth.access.AdministrativeScope
import kz.epharm.merch.placements.dto.*
import kz.epharm.merch.placements.repository.PlacementRepository
import kz.epharm.merch.placements.service.PlacementPermissions
import kz.epharm.merch.placements.service.PlacementService
import kz.epharm.merch.producer.auth.ProducerAuthService
import kz.epharm.merch.producer.auth.ProducerPrincipal
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.math.BigInteger
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

internal data class CommercialRecord(val id:UUID,val producer:UUID,val revision:Long,val data:CommercialPlanData,
    val requests:List<UUID>,val submittedRevision:Long?,val requestHash:String?,val copiedFrom:UUID?,val copiedRevision:Long?,val copyHash:String?,val updatedAt:Instant)

@Service
class CommercialService(private val jdbc:JdbcTemplate,private val named:NamedParameterJdbcTemplate,private val json:ObjectMapper,
    private val access:AdministrativeScope,private val sessions:ProducerAuthService,private val repository:PlacementRepository,
    private val placements:PlacementService) {
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    fun workspace(actor:PlacementActor.Producer):CommercialWorkspace {
        active(actor.customerId)
        val settings=settings(actor.customerId)
        return CommercialWorkspace(settings,settings.supplierCategory?.let(::pricing),members(actor.customerId),list(actor,null,50,0))
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    fun list(actor:PlacementActor,producerId:UUID?,limit:Int,offset:Int):CommercialPlanPage {
        if(limit !in 1..100 || offset !in 0..100_000) invalid("Некорректная страница планов")
        val allowed=when(actor) {
            is PlacementActor.Producer->{active(actor.customerId);if(producerId!=null && producerId!=actor.customerId) forbidden();setOf(actor.customerId)}
            is PlacementActor.Internal->{access.requireExplicitPermission(PlacementPermissions.key("view"));access.currentProducerIds()}
            PlacementActor.System->forbidden()
        }
        if(allowed?.isEmpty()==true) return CommercialPlanPage(emptyList(),0,limit,offset)
        val ids=named.query("SELECT id FROM merch_supplier_budget_plans WHERE 1=1${if(allowed!=null) " AND producer_id IN (:owners)" else ""}${if(producerId!=null) " AND producer_id=:producer" else ""} ORDER BY updated_at DESC,id LIMIT 5001",
            mapOf("owners" to allowed,"producer" to producerId)) {rs,_->rs.getObject(1,UUID::class.java)}
        if(ids.size>5000) tooLarge()
        val visible=ids.map(::load).filter {canRecord(it,actor,"view")}
        return CommercialPlanPage(visible.drop(offset).take(limit).map {dto(it,actor)},visible.size.toLong(),limit,offset)
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    fun view(id:UUID,actor:PlacementActor):CommercialPlanDto {
        val record=load(id);requireRecord(record,actor,"view")
        return dto(record,actor)
    }
    @Transactional
    fun save(id:UUID,producer:UUID,body:SaveCommercialPlan,actor:PlacementActor):CommercialPlanDto {
        lockActor(actor)
        active(producer,lock=true)
        val data=CommercialValidation.plan(body.plan)
        if(body.expectedRevision<0 || json.writeValueAsBytes(data).size>500_000) invalid("Некорректная ревизия или слишком большой план")
        val old=record(id)
        if(old!=null && old.producer!=producer) forbidden()
        if(old!=null) {requireRecord(old,actor,"edit");requireRecord(old,actor,"financial_data")}
        val selected=branches(data)
        require(actor,"edit",selected+(old?.let(::branches).orEmpty()),producer)
        require(actor,"financial_data",selected+(old?.let(::branches).orEmpty()),producer)
        repository.lock(selected+(old?.let(::branches).orEmpty()),data.shelfSelection.orEmpty().map {it.shelfId}.toSet()+(old?.data?.shelfSelection.orEmpty().map {it.shelfId}))
        if(branches(data)!=selected) conflict("Расположение мест изменилось: обновите страницу")
        val current=record(id,lock=true)
        val hash=commandHash(actor,body)
        if(current!=null && current.requestHash==hash && current.revision==body.expectedRevision+1) return dto(current,actor)
        if(current?.revision!=body.expectedRevision && !(current==null && body.expectedRevision==0L)) conflict("План изменился: загрузите актуальную версию")
        if(current?.requests?.isNotEmpty()==true) conflict("Отправленный план нельзя переписывать: создайте копию")
        if(current==null) jdbc.update("""INSERT INTO merch_supplier_budget_plans(id,producer_id,source_system,source_origin,source_id,source_payload,
            title,start_month,end_month,budget_limit,status,notes,planning_mode,product_briefs,shelf_selection,extra_activities,created_at,updated_at)
            VALUES(?,?,'EPHARM','https://epharm.inkar.kz',?,'{}'::jsonb,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,now(),now())""",
            id,producer,id,data.title,data.startMonth,data.endMonth,data.budgetLimit,data.status,data.notes,data.planningMode,
            json.writeValueAsString(data.productBriefs),data.shelfSelection?.let(json::writeValueAsString),json.writeValueAsString(data.extraActivities))
        jdbc.update("""UPDATE merch_supplier_budget_plans SET title=?,start_month=?,end_month=?,budget_limit=?,status=?,notes=?,planning_mode=?,
            product_briefs=?::jsonb,shelf_selection=?::jsonb,extra_activities=?::jsonb,revision=revision+1,last_request_hash=?,updated_at=now() WHERE id=?""",
            data.title,data.startMonth,data.endMonth,data.budgetLimit,data.status,data.notes,data.planningMode,json.writeValueAsString(data.productBriefs),
            data.shelfSelection?.let(json::writeValueAsString),json.writeValueAsString(data.extraActivities),hash,id)
        val retained=data.items.mapNotNull {it.id}.toSet()
        for(row in jdbc.query("SELECT id FROM merch_supplier_budget_plan_items WHERE plan_id=? AND retired_at IS NULL",{rs,_->rs.getObject(1,UUID::class.java)},id))
            if(row !in retained) jdbc.update("UPDATE merch_supplier_budget_plan_items SET retired_at=now() WHERE id=?",row)
        for((index,item) in data.items.withIndex()) {
            val itemId=item.id?:UUID.randomUUID()
            val parent=jdbc.query("SELECT plan_id FROM merch_supplier_budget_plan_items WHERE id=?",{rs,_->rs.getObject(1,UUID::class.java)},itemId).singleOrNull()
            if(parent!=null && parent!=id) conflict("ID позиции уже принадлежит другому плану")
            if(parent==null) jdbc.update("""INSERT INTO merch_supplier_budget_plan_items(id,plan_id,source_system,source_origin,source_id,source_payload,
                product_name,sku,activity_type,active_months,pharmacy_count,facing_count,people_count,unit_price,supplier_category,note,position_index,created_at)
                VALUES(?,?,'EPHARM','https://epharm.inkar.kz',?,'{}'::jsonb,?,?,?,ARRAY(SELECT value::date FROM jsonb_array_elements_text(?::jsonb)),?,?,?,?,?,?,?,now())""",
                itemId,id,itemId,item.productName,item.sku,item.activityType,json.writeValueAsString(item.activeMonths),item.pharmacyCount,item.facingCount,item.peopleCount,
                item.unitPrice,item.supplierCategory,item.note,index)
            else jdbc.update("""UPDATE merch_supplier_budget_plan_items SET product_name=?,sku=?,activity_type=?,active_months=ARRAY(SELECT value::date FROM jsonb_array_elements_text(?::jsonb)),
                pharmacy_count=?,facing_count=?,people_count=?,unit_price=?,supplier_category=?,note=?,position_index=?,retired_at=NULL WHERE id=?""",
                item.productName,item.sku,item.activityType,json.writeValueAsString(item.activeMonths),item.pharmacyCount,item.facingCount,item.peopleCount,
                item.unitPrice,item.supplierCategory,item.note,index,itemId)
        }
        val after=load(id)
        audit("PLAN",id,producer,after.revision,"saved",actor,mapOf("before" to current?.data,"after" to after.data))
        return dto(after,actor)
    }
    @Transactional
    fun submit(id:UUID,body:SubmitCommercialPlan,actor:PlacementActor):CommercialPlanDto {
        lockActor(actor)
        val before=load(id);active(before.producer,lock=true)
        requireRecord(before,actor,"submit");requireRecord(before,actor,"financial_data")
        if(lineage(before).unresolved.isNotEmpty()) conflict("Исторические заявки требуют явной проверки HQ; повторная отправка запрещена")
        val allBranches=branches(before)
        require(actor,"submit",allBranches,before.producer);require(actor,"financial_data",allBranches,before.producer)
        repository.lock(allBranches,before.data.shelfSelection.orEmpty().map {it.shelfId}.toSet())
        val current=record(id,lock=true)?:missing()
        if(current.requests.isNotEmpty()) {
            if(current.submittedRevision==body.expectedRevision || current.revision==body.expectedRevision) return dto(current,actor)
            conflict("План уже отправлен; обновите страницу")
        }
        if(current.revision!=body.expectedRevision || current.data.status=="archived") conflict("План изменился или архивирован")
        val data=CommercialValidation.plan(current.data)
        val rows=data.shelfSelection.orEmpty()
        if(rows.isEmpty()) invalid("Для отправки выберите реальные места; бюджетная оценка сама по себе не является заявкой")
        val settings=settings(current.producer)
        val category=settings.supplierCategory?:conflict("Сначала HQ должен подтвердить тарифную категорию производителя")
        if(pricing(category)==null) conflict("Подтверждённый тариф ещё не перенесён или неактивен")
        val campaign=UUID.randomUUID()
        jdbc.update("INSERT INTO merch_placement_campaigns(id,source_system,source_origin,source_id,provenance) VALUES(?,'EPHARM','https://epharm.inkar.kz',?,?::jsonb)",
            campaign,campaign,json.writeValueAsString(mapOf("kind" to "NATIVE_COMMERCIAL_SUBMIT","commercialPlanId" to id)))
        val ids=mutableListOf<UUID>()
        for(month in rows.flatMap {it.placementMonths}.distinct().sorted()) {
            val selected=rows.filter {month in it.placementMonths}
            val products=selected.associate {row->
                val active=row.products.filter {month in it.activeMonths}
                if(active.any {it.productName.isBlank() || it.productSubcategory !in CommercialValidation.taxonomy[it.productCategory].orEmpty()}) invalid("Для отправки заполните названия и категории товаров")
                PlacementSelection(row.shelfId,row.portion) to PlacementProductSnapshot(active.joinToString(", ") {it.productName}.take(180),
                    active.firstOrNull()?.sku,active.sumOf {it.facingCount},category,json.writeValueAsString(active))
            }
            val draft=placements.createCommercialDraft(CreatePlacementDraft(current.producer,month,products.keys.toList(),data.notes?.take(500)),products,actor)
            jdbc.update("UPDATE merch_media_plans SET commercial_plan_id=?,campaign_id=? WHERE id=?",id,campaign,draft.id)
            val pending=placements.transition(draft.id,"submit",PlacementTransition(draft.revision),actor)
            ids+=pending.id
        }
        jdbc.update("""UPDATE merch_supplier_budget_plans SET request_ids=ARRAY(SELECT value::uuid FROM jsonb_array_elements_text(?::jsonb)),
            status='ready',submitted_revision=?,revision=revision+1,updated_at=now() WHERE id=?""",json.writeValueAsString(ids),body.expectedRevision,id)
        val after=load(id);audit("PLAN",id,current.producer,after.revision,"submitted",actor,mapOf("requestIds" to ids,"campaignId" to campaign))
        return dto(after,actor)
    }
    @Transactional
    fun copy(id:UUID,body:CopyCommercialPlan,actor:PlacementActor):CommercialPlanDto {
        lockActor(actor)
        val source=load(id);active(source.producer,lock=true)
        requireRecord(source,actor,"view");requireRecord(source,actor,"edit")
        if(body.newId==id || body.startMonth.dayOfMonth!=1) conflict("Некорректный ID или период копии")
        val existing=record(body.newId)
        if(existing!=null) {
            if(existing.copiedFrom==id && existing.copiedRevision==body.expectedRevision && existing.copyHash==commandHash(actor,body) && existing.producer==source.producer && existing.data.startMonth==body.startMonth) {
                requireRecord(existing,actor,"view")
                return dto(existing,actor)
            }
            conflict("ID новой копии уже занят")
        }
        if(source.revision!=body.expectedRevision) conflict("Источник изменился: обновите страницу")
        val shift=java.time.temporal.ChronoUnit.MONTHS.between(source.data.startMonth,body.startMonth)
        fun product(p:CommercialProduct)=p.copy(activeMonths=p.activeMonths.map {it.plusMonths(shift)})
        fun activity(a:CommercialActivity)=a.copy(id=UUID.randomUUID(),activeMonths=a.activeMonths.map {it.plusMonths(shift)})
        val data=source.data.copy(title=(source.data.title+" · копия").take(180),startMonth=body.startMonth,endMonth=source.data.endMonth.plusMonths(shift),status="draft",
            productBriefs=source.data.productBriefs.map(::product),shelfSelection=source.data.shelfSelection?.map {it.copy(placementMonths=it.placementMonths.map {m->m.plusMonths(shift)},products=it.products.map(::product))},
            items=source.data.items.map(::activity),extraActivities=source.data.extraActivities.map(::activity))
        save(body.newId,source.producer,SaveCommercialPlan(0,data),actor)
        jdbc.update("UPDATE merch_supplier_budget_plans SET copied_from=?,copied_from_revision=?,copy_request_hash=? WHERE id=?",id,body.expectedRevision,commandHash(actor,body),body.newId)
        return dto(load(body.newId),actor)
    }
    @Transactional
    fun saveSettings(body:SaveCommercialSettings,actor:PlacementActor.Producer):CommercialSettings {
        lockActor(actor);active(actor.customerId,lock=true)
        if(body.expectedRevision<0 || body.sixMonthBudget?.let {it<0}==true) invalid("Некорректный бюджет или ревизия")
        val role=body.primaryContactRole?.let {CommercialValidation.text(it,255)}
        ensureSettings(actor.customerId)
        val old=settings(actor.customerId,true)
        val command=commandHash(actor,body)
        if(old.revision==body.expectedRevision+1 && old.sixMonthBudget==body.sixMonthBudget && old.primaryContactRole==role && wasOperation("SETTINGS",actor.customerId,old.revision,"settings_saved",command)) return old
        if(old.revision!=body.expectedRevision) conflict("Настройки изменились")
        jdbc.update("UPDATE merch_supplier_settings SET six_month_budget=?,primary_contact_role=?,revision=revision+1,updated_at=now() WHERE producer_id=?",body.sixMonthBudget,role,actor.customerId)
        val result=settings(actor.customerId);audit("SETTINGS",actor.customerId,actor.customerId,result.revision,"settings_saved",actor,mapOf("before" to old,"after" to result,"commandHash" to command))
        return result
    }
    @Transactional
    fun member(id:UUID,body:SaveCommercialMember,actor:PlacementActor.Producer):CommercialMember {
        lockActor(actor);active(actor.customerId,lock=true)
        val name=CommercialValidation.text(body.fullName,160,2);val email=CommercialValidation.text(body.email,254,3).lowercase(Locale.ROOT)
        val position=body.position?.let {CommercialValidation.text(it,255)}
        if(body.expectedRevision<0 || !Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(email) || body.accessRole !in setOf("head","planner","finance","viewer")) invalid("Проверьте email и роль участника команды")
        val row=jdbc.queryForList("SELECT producer_id,revision,last_request_hash FROM merch_supplier_company_members WHERE id=? FOR UPDATE",id).singleOrNull()
        if(row!=null && row["producer_id"]!=actor.customerId) forbidden()
        val hash=commandHash(actor,body)
        val revision=(row?.get("revision") as? Number)?.toLong()?:0
        if(row!=null && row["last_request_hash"]==hash && revision==body.expectedRevision+1) return members(actor.customerId).single {it.id==id}
        if(revision!=body.expectedRevision) conflict("Состав команды изменился")
        if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM merch_supplier_company_members WHERE producer_id=? AND lower(email)=? AND id<>?)",Boolean::class.java,actor.customerId,email,id)==true) conflict("Этот email уже указан в команде")
        if(row==null) jdbc.update("""INSERT INTO merch_supplier_company_members(id,producer_id,source_system,source_origin,source_id,source_payload,full_name,email,position,access_role,active,created_at,updated_at)
            VALUES(?,?,'EPHARM','https://epharm.inkar.kz',?,'{}'::jsonb,?,?,?,?,?,now(),now())""",id,actor.customerId,id,name,email,position,body.accessRole,body.active)
        jdbc.update("UPDATE merch_supplier_company_members SET full_name=?,email=?,position=?,access_role=?,active=?,revision=revision+1,last_request_hash=?,updated_at=now() WHERE id=?",name,email,position,body.accessRole,body.active,hash,id)
        val result=members(actor.customerId).single {it.id==id}
        audit("MEMBER",id,actor.customerId,result.revision,"member_saved",actor,mapOf("active" to body.active,"role" to body.accessRole))
        return result
    }
    @Transactional(readOnly=true)
    fun categorySettings(producer:UUID,actor:PlacementActor.Internal):CommercialCategorySettings {
        access.requireAllMerchPlacementForProducerPermission(PlacementPermissions.key("view"),producer)
        access.requireAllMerchPlacementForProducerPermission(PlacementPermissions.key("financial_data"),producer)
        active(producer)
        val hq=jdbc.queryForObject("SELECT status='active' AND role IN ('SYSTEM_ADMIN','HQ_HEAD') FROM admin_users WHERE id=?",Boolean::class.java,actor.accountId)==true
        return CommercialCategorySettings(settings(producer),hq && access.canAllMerchPlacementForProducerPermission(PlacementPermissions.key("edit"),producer))
    }
    @Transactional(readOnly=true)
    fun pricingIndex():List<CommercialPricing> {
        access.requireAllMerchPlacementPermission(PlacementPermissions.key("financial_data"))
        access.requireAllMerchPlacementPermission(PlacementPermissions.key("view"))
        return jdbc.query("SELECT category FROM merch_supplier_pricing_tiers WHERE active ORDER BY category",{rs,_->pricing(rs.getString(1))!!})
    }
    @Transactional
    fun setCategory(producer:UUID,body:SetCommercialCategory,actor:PlacementActor.Internal):CommercialSettings {
        lockActor(actor)
        for(action in listOf("view","edit","financial_data")) access.requireAllMerchPlacementForProducerPermission(PlacementPermissions.key(action),producer)
        if(jdbc.queryForObject("SELECT role IN ('SYSTEM_ADMIN','HQ_HEAD') FROM admin_users WHERE id=?",Boolean::class.java,actor.accountId)!=true) forbidden()
        active(producer,lock=true)
        if(body.category !in CommercialValidation.categories || pricing(body.category)==null || body.expectedRevision<0) invalid("Выберите подтверждённую тарифную категорию")
        ensureSettings(producer)
        val old=settings(producer,true)
        val command=commandHash(actor,body)
        if(old.revision==body.expectedRevision+1 && old.supplierCategory==body.category && wasOperation("SETTINGS",producer,old.revision,"category_assigned",command)) return old
        if(old.revision!=body.expectedRevision) conflict("Тарифная категория изменилась")
        jdbc.update("UPDATE merch_supplier_settings SET supplier_category=?,revision=revision+1,updated_at=now() WHERE producer_id=?",body.category,producer)
        val result=settings(producer);audit("SETTINGS",producer,producer,result.revision,"category_assigned",actor,mapOf("beforeCategory" to old.supplierCategory,"category" to body.category,"commandHash" to command))
        return result
    }
    internal fun settings(producer:UUID,lock:Boolean=false):CommercialSettings=jdbc.query("SELECT * FROM merch_supplier_settings WHERE producer_id=?${if(lock) " FOR UPDATE" else ""}",
        {rs,_->CommercialSettings(rs.getLong("revision"),rs.getString("supplier_category"),rs.getObject("six_month_budget",java.lang.Long::class.java)?.toLong(),rs.getString("primary_contact_role"))},producer).singleOrNull()
        ?:CommercialSettings(0,null,null,null)
    private fun ensureSettings(producer:UUID) {jdbc.update("INSERT INTO merch_supplier_settings(producer_id,source_payload,updated_at) VALUES(?,'{}'::jsonb,now()) ON CONFLICT DO NOTHING",producer)}
    internal fun pricing(category:String):CommercialPricing?=jdbc.query("SELECT * FROM merch_supplier_pricing_tiers WHERE category=? AND active",{rs,_->
        CommercialPricing(rs.getString("category"),rs.getString("label"),rs.getBigDecimal("price_multiplier"),rs.getLong("facing_month_price"),rs.getLong("promotion_month_price"),rs.getLong("training_event_price"),rs.getLong("motivation_person_price"))},category).singleOrNull()
    private fun members(producer:UUID)=jdbc.query("SELECT * FROM merch_supplier_company_members WHERE producer_id=? ORDER BY active DESC,full_name,id",{rs,_->
        CommercialMember(rs.getObject("id",UUID::class.java),rs.getLong("revision"),rs.getString("full_name"),rs.getString("email"),rs.getString("position"),rs.getString("access_role"),rs.getBoolean("active"))},producer)
    internal fun load(id:UUID)=record(id)?:missing()
    private fun record(id:UUID,lock:Boolean=false):CommercialRecord?=jdbc.query("SELECT * FROM merch_supplier_budget_plans WHERE id=?${if(lock) " FOR UPDATE" else ""}",{rs,_->
        val items=jdbc.query("SELECT * FROM merch_supplier_budget_plan_items WHERE plan_id=? AND retired_at IS NULL ORDER BY position_index,created_at,id",{r,_->activity(r)},id)
        CommercialRecord(rs.getObject("id",UUID::class.java),rs.getObject("producer_id",UUID::class.java),rs.getLong("revision"),
            CommercialPlanData(rs.getString("title"),rs.getObject("start_month",LocalDate::class.java),rs.getObject("end_month",LocalDate::class.java),rs.getLong("budget_limit"),rs.getString("status"),rs.getString("notes"),rs.getString("planning_mode"),
                read(rs.getString("product_briefs")),rs.getString("shelf_selection")?.let(::readShelves),read(rs.getString("extra_activities")),items),
            (rs.getArray("request_ids").array as Array<*>).map {UUID.fromString(it.toString())},rs.getObject("submitted_revision",java.lang.Long::class.java)?.toLong(),rs.getString("last_request_hash"),rs.getObject("copied_from",UUID::class.java),
            rs.getObject("copied_from_revision",java.lang.Long::class.java)?.toLong(),rs.getString("copy_request_hash"),rs.getTimestamp("updated_at").toInstant())},id).singleOrNull()
    private fun activity(rs:ResultSet)=CommercialActivity(rs.getObject("id",UUID::class.java),rs.getString("product_name"),rs.getString("sku"),rs.getString("activity_type"),
        (rs.getArray("active_months").array as Array<*>).map {LocalDate.parse(it.toString())},rs.getInt("pharmacy_count"),rs.getInt("facing_count"),rs.getInt("people_count"),rs.getLong("unit_price"),rs.getString("supplier_category"),rs.getString("note"))
    private data class RequestLineage(val resolved:List<UUID>,val branches:Set<UUID>,val unresolved:List<UUID>)
    private fun lineage(record:CommercialRecord):RequestLineage {
        if(record.requests.isEmpty()) return RequestLineage(emptyList(),emptySet(),emptyList())
        data class Row(val id:UUID,val verified:Boolean,val branch:UUID?)
        val rows=named.query("""SELECT p.id,s.branch_id,
            p.producer_id=:producer AND p.commercial_plan_id=:budget AND c.id IS NOT NULL
            AND (p.source_campaign_id IS NULL OR (p.source_campaign_id=c.source_id AND p.source_system=c.source_system AND p.source_origin=c.source_origin)) AS verified
            FROM merch_media_plans p LEFT JOIN merch_placement_campaigns c ON c.id=p.campaign_id
            LEFT JOIN merch_media_plan_items i ON i.media_plan_id=p.id LEFT JOIN merch_shelves s ON s.id=i.shelf_id
            WHERE p.id IN (:ids)""",mapOf("ids" to record.requests,"producer" to record.producer,"budget" to record.id)) {rs,_->
            Row(rs.getObject("id",UUID::class.java),rs.getBoolean("verified"),rs.getObject("branch_id",UUID::class.java))}.groupBy {it.id}
        val resolved=record.requests.distinct().filter {id->rows[id]?.let {group->group.all {it.verified && it.branch!=null}}==true}
        return RequestLineage(resolved,resolved.flatMap {rows.getValue(it).map {row->row.branch!!}}.toSet(),record.requests.distinct().filter {it !in resolved})
    }
    internal fun branches(record:CommercialRecord):Set<UUID> = branches(record.data)+lineage(record).branches
    private fun canRecord(record:CommercialRecord,actor:PlacementActor,action:String):Boolean {
        // An absent historical request has unknown branch scope; a partial branch grant cannot authorize its parent.
        if(actor is PlacementActor.Internal && lineage(record).unresolved.isNotEmpty())
            return access.canAllMerchPlacementForProducerPermission(PlacementPermissions.key(action),record.producer)
        return can(actor,action,branches(record),record.producer)
    }
    internal fun requireRecord(record:CommercialRecord,actor:PlacementActor,action:String) {if(!canRecord(record,actor,action)) forbidden()}
    private fun branches(data:CommercialPlanData):Set<UUID> {
        val ids=data.shelfSelection.orEmpty().map {it.shelfId}.toSet()
        if(ids.isEmpty()) return emptySet()
        val rows=named.query("SELECT id,branch_id FROM merch_shelves WHERE id IN (:ids)",mapOf("ids" to ids)) {rs,_->rs.getObject(1,UUID::class.java) to rs.getObject(2,UUID::class.java)}.toMap()
        if(rows.keys!=ids || data.shelfSelection.orEmpty().any {it.pharmacyId!=null && it.pharmacyId!=rows[it.shelfId]}) invalid("Место или филиал не соответствуют плану")
        return rows.values.toSet()
    }
    internal fun require(actor:PlacementActor,action:String,branches:Set<UUID>,producer:UUID) {
        if(!can(actor,action,branches,producer)) forbidden()
    }
    internal fun can(actor:PlacementActor,action:String,branches:Set<UUID>,producer:UUID):Boolean=when(actor) {
        is PlacementActor.Producer->actor.customerId==producer && action in setOf("view","edit","submit","export","financial_data") && repository.producer(producer)?.active==true
        is PlacementActor.Internal->if(branches.isEmpty()) access.canAllMerchPlacementForProducerPermission(PlacementPermissions.key(action),producer)
            else access.canMerchPlacementPermission(PlacementPermissions.key(action),branches,producer)
        PlacementActor.System->false
    }
    internal fun dto(record:CommercialRecord,actor:PlacementActor):CommercialPlanDto {
        val lineage=lineage(record);val money=canRecord(record,actor,"financial_data")
        val estimate=if(money) estimate(record) else null
        val data=if(money) record.data else record.data.copy(budgetLimit=null,items=record.data.items.map {it.copy(unitPrice=null)},extraActivities=record.data.extraActivities.map {it.copy(unitPrice=null)},shelfSelection=record.data.shelfSelection?.map {it.copy(price=null)})
        val editable=record.requests.isEmpty() && canRecord(record,actor,"edit") && money
        return CommercialPlanDto(record.id,record.producer,record.revision,data,record.requests,lineage.resolved.map {placements.view(it,actor)},record.copiedFrom,estimate,
            if(!money) "hidden" else if(estimate==null) "tariff_unverified" else "nonbinding_estimate",editable,
            editable && record.data.status!="archived" && repository.producer(record.producer)?.profileComplete==true && record.data.shelfSelection.orEmpty().isNotEmpty() && canRecord(record,actor,"submit") && settings(record.producer).supplierCategory?.let(::pricing)!=null,
            money && canRecord(record,actor,"export"),record.updatedAt,canCopy=money && canRecord(record,actor,"edit"),unresolvedRequestIds=lineage.unresolved)
    }
    private fun estimate(record:CommercialRecord):BigInteger? {
        // Source supplier_budget_plan_items already includes extras: never add the JSON extras to those rows again.
        if(record.data.items.isNotEmpty()) return sum(record.data.items.map(::activityTotal))
        val tariff=settings(record.producer).supplierCategory?.let(::pricing)
        val shelves=record.data.shelfSelection.orEmpty()
        if(shelves.isNotEmpty() && (tariff==null || shelves.any {it.price==null})) return null
        val placement=shelves.flatMap {row->row.placementMonths.map {month->row.price!!.toBigInteger()+tariff!!.facingMonthPrice.toBigInteger()*row.products.filter {month in it.activeMonths}.sumOf {it.facingCount}.toBigInteger()}}
        return sum(placement+record.data.extraActivities.map(::activityTotal))
    }
    internal fun activityTotal(a:CommercialActivity):BigInteger {
        val factor=when(a.activityType) {"placement"->a.pharmacyCount.toBigInteger()*a.facingCount.toBigInteger();"motivation"->a.peopleCount.coerceAtLeast(1).toBigInteger();else->a.pharmacyCount.toBigInteger()}
        return (a.unitPrice?:conflict("Неизвестна предварительная цена активности")).toBigInteger()*factor*a.activeMonths.size.toBigInteger()
    }
    private fun sum(values:List<BigInteger>)=values.fold(BigInteger.ZERO,BigInteger::add)
    private fun readShelves(value:String):List<CommercialShelf> {
        val raw=json.readTree(value)
        return read<List<CommercialShelf>>(value).mapIndexed {index,row->row.copy(products=row.products.mapIndexed {productIndex,product->
            // Source 071 treats an absent activeMonths key as all months of this selection; explicit [] stays empty.
            if(!raw[index]["products"][productIndex].has("activeMonths")) product.copy(activeMonths=row.placementMonths) else product
        })}
    }
    private inline fun <reified T> read(value:String):T=json.readerFor(object:TypeReference<T>(){}).without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(value)
    private fun lockActor(actor:PlacementActor) {when(actor) {
        is PlacementActor.Internal->access.lockCurrentActor()
        is PlacementActor.Producer->sessions.lockForWrite(ProducerPrincipal(actor.customerId,actor.sessionId))
        PlacementActor.System->forbidden()
    }}
    private fun active(id:UUID,lock:Boolean=false)=repository.producer(id,lock)?.takeIf {it.active}?:forbidden()
    private fun audit(type:String,id:UUID,producer:UUID,revision:Long,action:String,actor:PlacementActor,details:Any) {
        jdbc.update("""INSERT INTO merch_commercial_audit(id,resource_type,resource_id,producer_id,revision,action,actor_type,admin_account_id,occurred_at,details)
            VALUES(?,?,?,?,?,?,?,?,now(),?::jsonb)""",UUID.randomUUID(),type,id,producer,revision,action,if(actor is PlacementActor.Internal) "ADMIN" else "PRODUCER",(actor as? PlacementActor.Internal)?.accountId,json.writeValueAsString(details))
    }
    private fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
    private fun commandHash(actor:PlacementActor,body:Any)=hash(json.writeValueAsBytes(mapOf("actor" to when(actor) {
        is PlacementActor.Internal->"ADMIN:${actor.accountId}";is PlacementActor.Producer->"PRODUCER:${actor.customerId}";PlacementActor.System->forbidden()
    },"body" to body)))
    private fun wasOperation(type:String,id:UUID,revision:Long,action:String,hash:String)=jdbc.queryForObject("""SELECT EXISTS(SELECT 1 FROM merch_commercial_audit
        WHERE resource_type=? AND resource_id=? AND revision=? AND action=? AND details->>'commandHash'=?)""",Boolean::class.java,type,id,revision,action,hash)==true
    private fun missing():Nothing=throw AppException(ErrorCode.NOT_FOUND,"Коммерческий план не найден",HttpStatus.NOT_FOUND)
    private fun forbidden():Nothing=throw AppException(ErrorCode.FORBIDDEN,"Нет доступа к коммерческому плану",HttpStatus.FORBIDDEN)
    private fun invalid(message:String):Nothing=CommercialValidation.invalid(message)
    private fun conflict(message:String):Nothing=throw AppException(ErrorCode.CONFLICT,message,HttpStatus.CONFLICT)
    private fun tooLarge():Nothing=throw AppException(ErrorCode.VALIDATION_FAILED,"Слишком много планов: уточните производителя",HttpStatus.PAYLOAD_TOO_LARGE)
}
