package kz.epharm.merch.inventory.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kz.epharm.auth.access.AdministrativeScope
import kz.epharm.auth.policy.PermissionKey
import kz.epharm.merch.inventory.dto.*
import kz.epharm.merch.inventory.repository.MerchInventoryRepository
import kz.epharm.merch.tasks.TaskEvidenceStorage
import kz.epharm.merch.tasks.TaskPhotoGate
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.annotation.Isolation
import org.springframework.web.multipart.MultipartFile
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.imageio.ImageIO

object MerchEditorPermissions {
    val structure=requireNotNull(PermissionKey.parse("merchandising","inventory","edit_structure"))
    val passport=requireNotNull(PermissionKey.parse("merchandising","inventory","edit_passport"))
    val prices=requireNotNull(PermissionKey.parse("merchandising","inventory","configure_prices"))
}

/** All writers share the booking lock order: current authority, branch, then shelf. */
@Service
class MerchInventoryEditorService(private val jdbc: JdbcTemplate,private val repository: MerchInventoryRepository,
    private val access: AdministrativeScope,private val mapper: ObjectMapper,private val files: TaskEvidenceStorage) {
    @Transactional(readOnly=true)
    fun organizations(): List<MerchOrganizationEditor> {
        access.requireAllMerchBranchPermission(MerchEditorPermissions.structure)
        return jdbc.query("SELECT * FROM merch_organizations ORDER BY name,id",{rs,_->MerchOrganizationEditor(rs.getObject("id",UUID::class.java),
            rs.getLong("editor_revision"),rs.getString("name"),rs.getString("legal_name"),rs.getString("bin"),rs.getBoolean("active"))})
    }

    @Transactional
    fun saveOrganization(id: UUID?,req: SaveMerchOrganization,actor: UUID): MerchOrganizationEditor {
        access.lockCurrentActor(); access.requireAllMerchBranchPermission(MerchEditorPermissions.structure)
        structureLock()
        val name=text(req.name,255); val legal=text(req.legalName,255)
        val bin=req.bin?.trim()?.takeIf(String::isNotEmpty)?.also{if(!it.matches(Regex("[0-9]{12}"))) invalid("БИН должен содержать 12 цифр")}
        val target=id?:UUID.randomUUID()
        var before: Map<String,Any?>?=null
        val revision=if(id==null) {
            if(req.expectedRevision!=null) invalid("Новая организация не имеет версии")
            jdbc.update("""INSERT INTO merch_organizations(id,source_system,source_origin,source_id,source_payload,name,legal_name,bin,active,created_at,updated_at)
                VALUES(?,'EPHARM','https://epharm.inkar.kz',?,'{}'::jsonb,?,?,?,?,now(),now())""",target,target,name,legal,bin,req.active)
            1L
        } else {
            val old=jdbc.queryForList("SELECT editor_revision,name,legal_name,bin,active FROM merch_organizations WHERE id=? FOR UPDATE",id).singleOrNull()?:missing()
            before=old
            if(req.expectedRevision!=old["editor_revision"]) conflict()
            val branches=jdbc.queryForList("SELECT id FROM merch_branches WHERE organization_id=? ORDER BY id FOR UPDATE",UUID::class.java,id)
            if(!req.active) branches.forEach{assertNoProtected(it,null)}
            jdbc.update("UPDATE merch_organizations SET name=?,legal_name=?,bin=?,active=?,editor_revision=editor_revision+1,updated_at=now() WHERE id=?",name,legal,bin,req.active,id)
            req.expectedRevision!!+1
        }
        audit(null,target,actor,"organization_saved",revision,mapOf("before" to before,"after" to mapOf("name" to name,"legalName" to legal,"bin" to bin,"active" to req.active)))
        return MerchOrganizationEditor(target,revision,name,legal,bin,req.active)
    }

    @Transactional
    fun createBranch(req: SaveMerchBranch,actor: UUID): MerchInventoryEditor {
        access.lockCurrentActor();access.requireAllMerchBranchPermission(MerchEditorPermissions.structure)
        structureLock()
        if(req.expectedRevision!=null) invalid("Новая аптека не имеет версии")
        validateBranch(req); requireOrganization(req.organizationId)
        uniqueBranchCode(req.organizationId,req.code.trim(),null)
        val id=UUID.randomUUID()
        jdbc.update("""INSERT INTO merch_branches(id,source_system,source_origin,source_id,source_payload,organization_id,code,name,address,city,division,
            latitude,longitude,active,created_at,updated_at) VALUES(?,'EPHARM','https://epharm.inkar.kz',?,'{}'::jsonb,?,?,?,?,?,?,?,?,?,now(),now())""",
            id,id,req.organizationId,req.code.trim(),req.name.trim(),req.address.trim(),req.city.trim(),optional(req.division,120),req.latitude,req.longitude,req.active)
        if(req.traffic!=null) jdbc.update("UPDATE merch_branches SET traffic=?::jsonb WHERE id=?",mapper.writeValueAsString(req.traffic),id)
        audit(id,null,actor,"branch_created",1,mapOf("organizationId" to req.organizationId))
        return editor(id,currentMonth())
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    fun editor(id: UUID,month: LocalDate): MerchInventoryEditor {
        access.requireMerchBranchPermission(MerchInventoryPermissions.view,id); requireMonth(month)
        val branch=repository.branch(id)?:missing()
        val row=jdbc.queryForMap("SELECT editor_revision,latitude,longitude,floor_plan::text,floor_plan_draft::text,floor_plan_published_at,traffic::text FROM merch_branches WHERE id=?",id)
        val money=access.canMerchBranchPermission(MerchInventoryPermissions.financial,id)
        val capacities=jdbc.query("SELECT id,specifications::text FROM merch_shelves WHERE branch_id=?",{rs,_->
            rs.getObject("id",UUID::class.java) to mapper.readTree(rs.getString("specifications")).path("maxProducts").asInt(20).coerceIn(1,20)},id).toMap()
        val shelves=repository.shelves(id).map { s -> MerchShelfEditor(if(money)s.dto else s.dto.copy(baseMonthlyPrice=null,halfMonthlyPrice=null),capacities.getValue(s.dto.id)) }
        var warning: String?=null
        fun plan(column: String): MerchFloorPlan? {
            val raw=row[column]?.toString()?.takeUnless{it=="null"}?:return null
            return runCatching{normalizePlan(mapper.readTree(raw))}.getOrElse{warning="Сохранённая схема требует проверки; исходная версия сохранена";null}
        }
        val published=plan("floor_plan");val draft=plan("floor_plan_draft")
        val prices=jdbc.query("""SELECT i.shelf_id,i.placement_month,i.status,i.monthly_price FROM merch_monthly_inventory i
            JOIN merch_shelves s ON s.id=i.shelf_id WHERE s.branch_id=? AND i.placement_month BETWEEN ? AND ? ORDER BY s.code,i.placement_month""",
            {rs,_->MerchEditorPrices(rs.getObject("shelf_id",UUID::class.java),rs.getObject("placement_month",LocalDate::class.java),rs.getString("status"),if(money)rs.getLong("monthly_price")else null)},id,month,month.plusMonths(11))
        val photos=jdbc.query("SELECT id,shelf_id,angle,caption,mime_type,archived_at FROM merch_inventory_media WHERE branch_id=? ORDER BY created_at,id",
            {rs,_->MerchEditorPhoto(rs.getObject("id",UUID::class.java),rs.getObject("shelf_id",UUID::class.java),rs.getString("angle"),rs.getString("caption"),rs.getString("mime_type"),rs.getTimestamp("archived_at")!=null)},id)
        return MerchInventoryEditor(branch,row["editor_revision"] as Long,row["latitude"] as BigDecimal?,row["longitude"] as BigDecimal?,
            repository.containers(id),shelves,prices,photos,published,draft,warning,(row["floor_plan_published_at"] as java.sql.Timestamp?)?.toInstant(),
            access.canMerchBranchPermission(MerchEditorPermissions.structure,id),access.canMerchBranchPermission(MerchEditorPermissions.passport,id),
            money&&access.canMerchBranchPermission(MerchEditorPermissions.prices,id),access.canAllMerchBranchPermission(MerchEditorPermissions.structure),mapper.readValue(row["traffic"].toString(),MerchBranchTraffic::class.java))
    }

    @Transactional
    fun saveBranch(id: UUID,req: SaveMerchBranch,actor: UUID): Long=write(id,req.expectedRevision?:invalid("Укажите версию"),MerchEditorPermissions.structure,actor,"branch_saved") {
        validateBranch(req)
        val old=repository.branch(id)?:missing()
        val beforeExtra=jdbc.queryForMap("SELECT latitude,longitude,traffic::text FROM merch_branches WHERE id=?",id)
        if(req.organizationId!=old.organizationId) {
            access.requireAllMerchBranchPermission(MerchEditorPermissions.structure);requireOrganization(req.organizationId)
        }
        if(!req.active) assertNoProtected(id,null)
        uniqueBranchCode(req.organizationId,req.code.trim(),id)
        jdbc.update("UPDATE merch_branches SET organization_id=?,code=?,name=?,address=?,city=?,division=?,latitude=?,longitude=?,active=? WHERE id=?",
            req.organizationId,req.code.trim(),req.name.trim(),req.address.trim(),req.city.trim(),optional(req.division,120),req.latitude,req.longitude,req.active,id)
        if(req.traffic!=null) jdbc.update("UPDATE merch_branches SET traffic=?::jsonb WHERE id=?",mapper.writeValueAsString(req.traffic),id)
        mapOf("before" to mapOf("branch" to old,"locationAndTraffic" to beforeExtra),"after" to req)
    }

    @Transactional
    fun saveContainer(branch: UUID,id: UUID?,req: SaveMerchContainer,actor: UUID): Long=write(branch,req.expectedRevision,MerchEditorPermissions.structure,actor,"container_saved") {
        val code=text(req.code,40);text(req.name,120)
        if(id!=null&&jdbc.queryForObject("SELECT count(*) FROM merch_inventory_containers WHERE id=? AND branch_id=?",Int::class.java,id,branch)!=1) missing()
        uniqueCode("merch_inventory_containers",branch,code,id)
        if(req.type !in setOf("cabinet","zone","wall","stand")||req.position !in 0..999||req.category!=null&&req.category !in setOf("A","B","C")) invalid("Некорректные параметры шкафа")
        val target=id?:UUID.randomUUID()
        var before: Map<String,Any?>?=null
        if(id==null) jdbc.update("""INSERT INTO merch_inventory_containers(id,source_system,source_origin,source_id,source_payload,branch_id,container_type,code,name,category,position_index,active,metadata,created_at,updated_at)
            VALUES(?,'EPHARM','https://epharm.inkar.kz',?,'{}'::jsonb,?,?,?,?,?,?,?,'{}'::jsonb,now(),now())""",target,target,branch,req.type,code,req.name.trim(),req.category,req.position,req.active)
        else {
            val old=jdbc.queryForList("SELECT code,name,container_type,category,position_index,active FROM merch_inventory_containers WHERE id=? AND branch_id=? FOR UPDATE",id,branch).singleOrNull()?:missing()
            before=old
            if(!req.active) {
                assertNoProtected(branch,null,id)
                jdbc.update("UPDATE merch_shelves SET active=false,updated_at=now() WHERE container_id=? AND branch_id=?",id,branch)
            }
            if(old["code"]!=code&&jdbc.queryForObject("""SELECT EXISTS(SELECT 1 FROM jsonb_array_elements(CASE WHEN jsonb_typeof(floor_plan->'cabinetPositions')='array'
                THEN floor_plan->'cabinetPositions' ELSE '[]'::jsonb END) p WHERE p->>'cabinetCode'=?) FROM merch_branches WHERE id=?""",Boolean::class.java,old["code"],branch)==true)
                invalid("Шкаф используется в опубликованной схеме. Сначала сохраните и опубликуйте схему без него")
            jdbc.update("UPDATE merch_inventory_containers SET code=?,name=?,container_type=?,category=?,position_index=?,active=?,updated_at=now() WHERE id=? AND branch_id=?",
                code,req.name.trim(),req.type,req.category,req.position,req.active,id,branch)
        }
        mapOf("containerId" to target,"before" to before,"after" to req)
    }

    @Transactional
    fun saveShelf(branch: UUID,id: UUID?,req: SaveMerchShelf,actor: UUID): Long=write(branch,req.expectedRevision,MerchEditorPermissions.structure,actor,"shelf_saved") {
        validateShelf(req);requireContainer(branch,req.containerId)
        if(id!=null&&jdbc.queryForObject("SELECT count(*) FROM merch_shelves WHERE id=? AND branch_id=?",Int::class.java,id,branch)!=1) missing()
        uniqueCode("merch_shelves",branch,req.code.trim(),id)
        val target=id?:UUID.randomUUID()
        var before: Map<String,Any?>?=null
        if(id==null) {
            initialPrice(branch,req.initialPrice)
            jdbc.update("""INSERT INTO merch_shelves(id,source_system,source_origin,source_id,source_payload,branch_id,container_id,code,category,width_m,base_monthly_price,active,place_type,title,position_index,specifications,sale_mode,created_at,updated_at)
                VALUES(?,'EPHARM','https://epharm.inkar.kz',?,'{}'::jsonb,?,?,?,?,?,?,?,?,?,?,?::jsonb,'whole_only',now(),now())""",
                target,target,branch,req.containerId,req.code.trim(),req.category,req.width,req.initialPrice,req.active,req.type,optional(req.title,255),req.position,mapper.writeValueAsString(mapOf("maxProducts" to req.maxProducts)))
        }
        else {
            if(req.initialPrice!=null) invalid("Цены существующих полок меняются в отдельном разделе")
            before=jdbc.queryForList("""SELECT id,container_id,code,title,category,width_m,active,place_type,position_index,
                specifications::text,base_monthly_price,half_monthly_price,sale_mode FROM merch_shelves WHERE id=? AND branch_id=? FOR UPDATE""",id,branch).singleOrNull()?:missing()
            if(!req.active) assertNoProtected(branch,id)
            jdbc.update("""UPDATE merch_shelves SET container_id=?,code=?,category=?,width_m=?,active=?,place_type=?,title=?,position_index=?,
                specifications=specifications||?::jsonb,updated_at=now() WHERE id=? AND branch_id=?""",req.containerId,req.code.trim(),req.category,req.width,req.active,req.type,
                optional(req.title,255),req.position,mapper.writeValueAsString(mapOf("maxProducts" to req.maxProducts)),id,branch)
        }
        mapOf("shelfId" to target,"before" to before,"after" to req)
    }

    @Transactional
    fun configure(branch: UUID,container: UUID,req: ConfigureMerchShelves,actor: UUID): Long=write(branch,req.expectedRevision,MerchEditorPermissions.structure,actor,"cabinet_configured") {
        if(req.counts.keys!=categories||req.counts.values.any{it !in 0..50}||req.width<BigDecimal("0.25")||req.width>BigDecimal("20")) invalid("Задайте количество 0–50 для шести категорий")
        requireContainer(branch,container)
        val created=mutableListOf<UUID>();val archived=mutableListOf<UUID>();val before=linkedMapOf<String,List<UUID>>()
        for(category in categories.sorted()) {
            val current=jdbc.queryForList("SELECT id FROM merch_shelves WHERE branch_id=? AND container_id=? AND category=? AND active ORDER BY position_index DESC,id DESC FOR UPDATE",UUID::class.java,branch,container,category)
            before[category]=current
            val difference=req.counts.getValue(category)-current.size
            if(difference<0) {
                val free=current.filterNot{hasProtected(branch,it)}.take(-difference)
                if(free.size != -difference) invalid("Нельзя уменьшить число полок: действующие размещения защищены")
                for(id in free) { jdbc.update("UPDATE merch_shelves SET active=false,updated_at=now() WHERE id=? AND branch_id=?",id,branch);archived.add(id) }
            }
            if(difference>0) { initialPrice(branch,req.initialPrice);repeat(difference) {
                val id=UUID.randomUUID();val code="$category-${id.toString().take(8)}"
                created.add(id)
                jdbc.update("""INSERT INTO merch_shelves(id,source_system,source_origin,source_id,source_payload,branch_id,container_id,code,category,width_m,base_monthly_price,active,place_type,position_index,specifications,sale_mode,created_at,updated_at)
                    VALUES(?,'EPHARM','https://epharm.inkar.kz',?,'{}'::jsonb,?,?,?,?,?,?,true,'shelf',0,'{"maxProducts":20}'::jsonb,'whole_only',now(),now())""",id,id,branch,container,code,category,req.width,req.initialPrice)
            } }
        }
        mapOf("containerId" to container,"before" to before,"afterCounts" to req.counts,"createdShelfIds" to created,"archivedShelfIds" to archived)
    }

    @Transactional
    fun prices(branch: UUID,req: UpdateMerchPrices,actor: UUID): MerchPriceResult {
        var changed=0;var protected=0
        val revision=write(branch,req.expectedRevision,MerchEditorPermissions.prices,actor,"prices_changed") {
            access.requireMerchBranchPermission(MerchInventoryPermissions.financial,branch)
            val months=months(req.fromMonth,req.toMonth)
            if(req.shelfIds.isEmpty()||req.shelfIds.size>500||req.shelfIds.distinct().size!=req.shelfIds.size||req.price !in 10000..Int.MAX_VALUE.toLong()) invalid("Выберите до 500 полок и цену от 10 000 ₸")
            if(req.saleMode!=null&&req.saleMode !in setOf("whole_only","whole_or_halves")) invalid("Некорректный режим продажи")
            if(req.saleMode=="whole_or_halves"&&(req.halfPrice==null||req.halfPrice !in 5000..Int.MAX_VALUE.toLong())) invalid("Цена половины от 5 000 ₸ обязательна")
            if(req.saleMode!="whole_or_halves"&&req.halfPrice!=null) invalid("Цена половины не соответствует режиму продажи")
            val shelves=repository.shelves(branch,req.shelfIds,true)
            if(shelves.size!=req.shelfIds.size||shelves.any{!it.dto.active}) invalid("Выбрана чужая или архивная полка")
            for(shelf in shelves) {
                // Sale options are shelf-wide: never reinterpret a protected existing contract.
                if(req.saleMode!=null&&hasProtected(branch,shelf.dto.id)) {protected+=months.size;continue}
                if(req.saleMode!=null) jdbc.update("UPDATE merch_shelves SET sale_mode=?,half_monthly_price=?,updated_at=now() WHERE id=?",req.saleMode,req.halfPrice,shelf.dto.id)
                val affectedMonths=if(req.applyFuture) (months+jdbc.queryForList("SELECT placement_month FROM merch_monthly_inventory WHERE shelf_id=? AND placement_month>? ORDER BY placement_month",LocalDate::class.java,shelf.dto.id,months.last())).distinct() else months
                for(month in affectedMonths) {
                    if(protectedMonth(shelf.dto.id,month)) {protected++;continue}
                    val id=UUID.randomUUID()
                    changed+=jdbc.update("""INSERT INTO merch_monthly_inventory(id,source_system,source_origin,source_id,source_payload,shelf_id,placement_month,status,monthly_price,created_at,updated_at)
                        VALUES(?,'EPHARM','https://epharm.inkar.kz',?,'{}'::jsonb,?,?,'available',?,now(),now())
                        ON CONFLICT(shelf_id,placement_month) DO UPDATE SET monthly_price=excluded.monthly_price,updated_at=now()
                        WHERE merch_monthly_inventory.status IN('available','blocked')""",id,id,shelf.dto.id,month,req.price)
                }
                if(req.applyFuture) jdbc.update("UPDATE merch_shelves SET base_monthly_price=?,updated_at=now() WHERE id=?",req.price,shelf.dto.id)
            }
            mapOf("shelfIds" to req.shelfIds,"fromMonth" to req.fromMonth,"toMonth" to req.toMonth,"price" to req.price,
                "applyFuture" to req.applyFuture,"changedCount" to changed,"protectedCount" to protected)
        }
        return MerchPriceResult(revision,changed,protected)
    }

    @Transactional
    fun floorPlan(branch: UUID,req: SaveMerchFloorPlan,actor: UUID): Long=write(branch,req.expectedRevision,MerchEditorPermissions.passport,actor,"floor_draft_saved") {
        validatePlan(branch,req.plan)
        jdbc.update("UPDATE merch_branches SET floor_plan_draft=?::jsonb WHERE id=?",mapper.writeValueAsString(req.plan),branch)
        mapOf("plan" to req.plan)
    }
    @Transactional
    fun publishFloorPlan(branch: UUID,req: MerchEditorRevision,actor: UUID): Long=write(branch,req.expectedRevision,MerchEditorPermissions.passport,actor,"floor_published") {
        val raw=jdbc.queryForObject("SELECT floor_plan_draft::text FROM merch_branches WHERE id=?",String::class.java,branch)?:invalid("Сначала сохраните схему")
        val plan=normalizePlan(mapper.readTree(raw));validatePlan(branch,plan)
        jdbc.update("INSERT INTO merch_floor_plan_versions(id,branch_id,revision,plan,published_by) VALUES(?,?,?,?::jsonb,?)",UUID.randomUUID(),branch,req.expectedRevision+1,raw,actor)
        jdbc.update("UPDATE merch_branches SET floor_plan=floor_plan_draft,floor_plan_draft=NULL,floor_plan_published_at=now() WHERE id=?",branch)
        mapOf("plan" to plan)
    }

    @Transactional
    fun upload(branch: UUID,expected: Long,shelf: UUID?,angle: String,caption: String,file: MultipartFile,actor: UUID): Long =
        TaskPhotoGate.run { write(branch,expected,MerchEditorPermissions.passport,actor,"photo_uploaded") {
            if(angle !in setOf("facade","entrance","hall","checkout","panorama","shelf")||(angle=="shelf")!=(shelf!=null)) invalid("Укажите ракурс фотографии")
            if(shelf!=null&&jdbc.queryForObject("SELECT count(*) FROM merch_shelves WHERE id=? AND branch_id=?",Int::class.java,shelf,branch)!=1) missing()
            val count=jdbc.queryForObject("SELECT count(*) FROM merch_inventory_media WHERE branch_id=? AND shelf_id IS NOT DISTINCT FROM ? AND archived_at IS NULL",Int::class.java,branch,shelf)!!
            if(count>=if(shelf==null)40 else 8) invalid("Достигнут лимит фотографий")
            val bytes=image(file);val mime=if(bytes[0]==0x89.toByte())"image/png" else "image/jpeg"
            val id=UUID.randomUUID();val key="inventory/$branch/$id"
            val sha=files.write(key,bytes)
            jdbc.update("""INSERT INTO merch_inventory_media(id,branch_id,shelf_id,angle,caption,storage_backend,object_key,mime_type,byte_size,sha256,created_by)
                VALUES(?,?,?,?,?,'private_host',?,?,?,?,?)""",id,branch,shelf,angle,text(caption,300,true),key,mime,bytes.size,sha,actor)
            mapOf("photoId" to id,"shelfId" to shelf,"sha256" to sha)
        } }
    @Transactional
    fun archivePhoto(branch: UUID,id: UUID,req: MerchEditorRevision,actor: UUID): Long=write(branch,req.expectedRevision,MerchEditorPermissions.passport,actor,"photo_archived") {
        if(jdbc.update("UPDATE merch_inventory_media SET archived_at=now() WHERE id=? AND branch_id=? AND archived_at IS NULL",id,branch)!=1) missing()
        mapOf("photoId" to id)
    }
    @Transactional(readOnly=true)
    fun media(branch: UUID,id: UUID): Pair<String,ByteArray> {
        access.requireMerchBranchPermission(MerchInventoryPermissions.view,branch)
        val photo=jdbc.queryForList("SELECT storage_backend,object_key,sha256,mime_type FROM merch_inventory_media WHERE id=? AND branch_id=?",id,branch).singleOrNull()?:missing()
        return photo["mime_type"].toString() to files.read(photo["storage_backend"].toString(),photo["object_key"].toString(),photo["sha256"].toString())
    }

    private fun write(branch: UUID,expected: Long,permission: PermissionKey,actor: UUID,operation: String,body: ()->Map<String,Any?>): Long {
        access.lockCurrentActor();access.requireMerchBranchPermission(MerchInventoryPermissions.view,branch);access.requireMerchBranchPermission(permission,branch)
        if(permission==MerchEditorPermissions.structure) structureLock()
        val previous=jdbc.queryForList("SELECT editor_revision FROM merch_branches WHERE id=? FOR UPDATE",branch).singleOrNull()?:missing()
        if(expected<1||previous["editor_revision"]!=expected) conflict()
        val details=body()
        val revision=expected+1
        jdbc.update("UPDATE merch_branches SET editor_revision=?,updated_at=now() WHERE id=?",revision,branch)
        audit(branch,null,actor,operation,revision,details)
        return revision
    }
    private fun audit(branch: UUID?,org: UUID?,actor: UUID,operation: String,revision: Long,details: Map<String,Any?>) {
        jdbc.update("INSERT INTO merch_inventory_editor_audit(id,branch_id,organization_id,actor_id,operation,revision,details) VALUES(?,?,?,?,?,?,?::jsonb)",UUID.randomUUID(),branch,org,actor,operation,revision,mapper.writeValueAsString(details))
    }
    private fun validateBranch(req: SaveMerchBranch) {
        text(req.code,80);text(req.name,255);text(req.address,500);text(req.city,120);optional(req.division,120)
        if(req.latitude!=null&&(req.latitude<BigDecimal(-90)||req.latitude>BigDecimal(90))||req.longitude!=null&&(req.longitude<BigDecimal(-180)||req.longitude>BigDecimal(180))) invalid("Некорректные координаты")
        if((req.latitude==null)!=(req.longitude==null)) invalid("Укажите обе координаты")
        req.traffic?.let { traffic ->
            if(listOfNotNull(traffic.otsPerDay,traffic.vehicleTrafficPerDay,traffic.pedestrianTrafficPerDay).any{it !in 0..1_000_000_000L}) invalid("Показатели трафика должны быть неотрицательными")
            optional(traffic.source,255)
        }
    }
    private fun validateShelf(req: SaveMerchShelf) {
        text(req.code,80);optional(req.title,255)
        if(req.category !in categories||req.type !in setOf("shelf","screen","tv")||req.position !in 0..999||req.maxProducts !in 1..20||req.width<BigDecimal("0.25")||req.width>BigDecimal("20")) invalid("Некорректные параметры рекламного места")
    }
    private fun requireOrganization(id: UUID) {
        if(jdbc.queryForObject("SELECT count(*) FROM merch_organizations WHERE id=? AND active",Int::class.java,id)!=1) invalid("Выберите действующую организацию")
    }
    // Rare structural writes serialize before branch locks, including organization moves/archives.
    private fun structureLock() { jdbc.queryForList("SELECT pg_advisory_xact_lock(718097,78)") }
    private fun uniqueBranchCode(org: UUID,code: String,id: UUID?) {
        if(jdbc.queryForObject("SELECT count(*) FROM merch_branches WHERE organization_id=? AND code=? AND (?::uuid IS NULL OR id<>?)",Int::class.java,org,code,id,id)!=0)
            throw AppException(ErrorCode.CONFLICT,"Код аптеки уже используется в организации",HttpStatus.CONFLICT)
    }
    private fun uniqueCode(table: String,branch: UUID,code: String,id: UUID?) {
        check(table in setOf("merch_inventory_containers","merch_shelves"))
        if(jdbc.queryForObject("SELECT count(*) FROM $table WHERE branch_id=? AND code=? AND (?::uuid IS NULL OR id<>?)",Int::class.java,branch,code,id,id)!=0)
            throw AppException(ErrorCode.CONFLICT,"Код уже используется в аптеке",HttpStatus.CONFLICT)
    }
    private fun initialPrice(branch: UUID,price: Long?) {
        access.requireMerchBranchPermission(MerchEditorPermissions.prices,branch)
        access.requireMerchBranchPermission(MerchInventoryPermissions.financial,branch)
        if(price==null||price !in 10000..Int.MAX_VALUE.toLong()) invalid("Начальная цена от 10 000 ₸ обязательна")
    }
    private fun requireContainer(branch: UUID,id: UUID?) {
        if(id!=null&&jdbc.queryForObject("SELECT count(*) FROM merch_inventory_containers WHERE id=? AND branch_id=? AND active",Int::class.java,id,branch)!=1) invalid("Шкаф не относится к аптеке или архивирован")
    }
    private fun hasProtected(branch: UUID,shelf: UUID?=null,container: UUID?=null): Boolean = jdbc.queryForObject("""SELECT EXISTS(
        SELECT 1 FROM merch_shelves s WHERE s.branch_id=? AND (?::uuid IS NULL OR s.id=?) AND (?::uuid IS NULL OR s.container_id=?) AND (
        EXISTS(SELECT 1 FROM merch_monthly_inventory i WHERE i.shelf_id=s.id AND i.placement_month>=? AND i.status IN('held','booked')) OR
        EXISTS(SELECT 1 FROM merch_media_plan_items i JOIN merch_media_plans p ON p.id=i.media_plan_id WHERE i.shelf_id=s.id AND i.placement_month>=? AND
            (i.status='approved' OR(i.status='pending' AND p.status='pending' AND p.held_until>now())))))""",Boolean::class.java,branch,shelf,shelf,container,container,currentMonth(),currentMonth())!!
    private fun assertNoProtected(branch: UUID,shelf: UUID?,container: UUID?=null) {
        if(hasProtected(branch,shelf,container)) throw AppException(ErrorCode.CONFLICT,"Сначала завершите или отмените действующие размещения. История не удалена",HttpStatus.CONFLICT)
    }
    private fun protectedMonth(shelf: UUID,month: LocalDate): Boolean = jdbc.queryForObject("""SELECT
        EXISTS(SELECT 1 FROM merch_monthly_inventory WHERE shelf_id=? AND placement_month=? AND status IN('held','booked')) OR
        EXISTS(SELECT 1 FROM merch_media_plan_items i JOIN merch_media_plans p ON p.id=i.media_plan_id WHERE i.shelf_id=? AND i.placement_month=? AND
            (i.status='approved' OR(i.status='pending' AND p.status='pending' AND p.held_until>now())))""",Boolean::class.java,shelf,month,shelf,month)!!
    private fun requireMonth(month: LocalDate) { if(month.dayOfMonth!=1||month.year !in 2000..2100) invalid("Укажите первое число месяца 2000–2100") }
    private fun months(from: LocalDate,to: LocalDate): List<LocalDate> {
        requireMonth(from);requireMonth(to);val count=ChronoUnit.MONTHS.between(from,to)+1
        if(count !in 1..12) invalid("Выберите период до 12 месяцев")
        return (0 until count).map{from.plusMonths(it)}
    }
    private fun currentMonth()=LocalDate.now(ZoneId.of("Asia/Almaty")).withDayOfMonth(1)
    private fun text(value: String,max: Int,empty: Boolean=false): String=value.trim().also {
        if((!empty&&it.isEmpty())||it.length>max||it.any(Char::isISOControl)) invalid("Проверьте обязательные поля и длину текста")
    }
    private fun optional(value: String?,max: Int)=value?.let{text(it,max,true)}?.takeIf(String::isNotEmpty)
    private fun invalid(message: String): Nothing=throw AppException(ErrorCode.VALIDATION_FAILED,message)
    private fun missing(): Nothing=throw AppException(ErrorCode.NOT_FOUND,"Объект не найден в выбранной аптеке",HttpStatus.NOT_FOUND)
    private fun conflict(): Nothing=throw AppException(ErrorCode.CONFLICT,"Данные изменились. Обновите страницу перед сохранением",HttpStatus.CONFLICT)
    private val categories=setOf("AA","AB","BA","BB","CA","CB")

    internal fun normalizePlan(raw: JsonNode): MerchFloorPlan {
        if(raw["version"]?.asInt()==3) return mapper.treeToValue(raw,MerchFloorPlan::class.java)
        if(raw["version"]?.asInt()!=2) invalid("Неподдерживаемая версия схемы")
        val room=mapper.treeToValue(raw["room"],MerchFloorRoom::class.java)
        fun fixture(type: String,width: String,depth: String): MerchFloorFixture {
            val node=raw[type]?:invalid("Неполная сохранённая схема")
            val vertical=node["orientation"]?.asText()=="vertical"
            return MerchFloorFixture("$type-1",type,if(type=="cashier")"Касса"else"Вход",node["x"].decimalValue(),node["y"].decimalValue(),
                node["width"]?.decimalValue()?:BigDecimal(if(vertical)depth else width),node["depth"]?.decimalValue()?:BigDecimal(if(vertical)width else depth),if(vertical)"vertical"else"horizontal")
        }
        val cabinets=raw["cabinetPositions"].map { node ->
            val vertical=node["orientation"]?.asText()=="vertical"
            MerchCabinetPosition(node["cabinetCode"].asText(),node["x"].decimalValue(),node["y"].decimalValue(),node["width"]?.decimalValue()?:BigDecimal(if(vertical)"0.55"else"1.4"),
                node["depth"]?.decimalValue()?:BigDecimal(if(vertical)"1.4"else"0.55"),if(vertical)"vertical"else"horizontal",node["zone"]?.asText()?:"custom")
        }
        return MerchFloorPlan(3,room,listOf(fixture("cashier","4.2","1"),fixture("entrance","2.6","0.5")),cabinets)
    }
    private fun validatePlan(branch: UUID,plan: MerchFloorPlan) {
        if(plan.version!=3||plan.room.width !in BigDecimal(10)..BigDecimal(40)||plan.room.depth !in BigDecimal(7)..BigDecimal(30)||plan.fixtures.size>20||plan.cabinetPositions.size>300) invalid("Проверьте размеры схемы 10×7–40×30 м")
        if(plan.fixtures.count{it.type=="cashier"}!=1||plan.fixtures.none{it.type=="entrance"}||plan.fixtures.any{it.type !in setOf("cashier","entrance")}||plan.fixtures.map{it.id}.distinct().size!=plan.fixtures.size) invalid("Нужна одна касса и хотя бы один вход без повторов")
        plan.fixtures.forEach { text(it.id,80);text(it.label,120);if(it.orientation !in setOf("horizontal","vertical")) invalid("Некорректный поворот объекта") }
        plan.cabinetPositions.forEach { text(it.cabinetCode,40);text(it.zone,80);if(it.orientation !in setOf("horizontal","vertical")) invalid("Некорректный поворот шкафа") }
        val codes=jdbc.queryForList("SELECT code FROM merch_inventory_containers WHERE branch_id=? AND active AND container_type='cabinet'",String::class.java,branch).toSet()
        if(plan.cabinetPositions.map{it.cabinetCode}.toSet()!=codes||plan.cabinetPositions.size!=codes.size) invalid("Разместите каждый действующий шкаф ровно один раз")
        val rectangles=plan.fixtures.map { listOf(it.x,it.y,it.width,it.depth) }+plan.cabinetPositions.map{listOf(it.x,it.y,it.width,it.depth)}
        rectangles.forEach { r ->
            if(r[2]<BigDecimal("0.2")||r[2]>BigDecimal(12)||r[3]<BigDecimal("0.2")||r[3]>BigDecimal(12)||r[0]-r[2]/BigDecimal(2)<BigDecimal.ZERO||r[1]-r[3]/BigDecimal(2)<BigDecimal.ZERO||r[0]+r[2]/BigDecimal(2)>plan.room.width||r[1]+r[3]/BigDecimal(2)>plan.room.depth) invalid("Объект выходит за границы помещения")
        }
        for(i in rectangles.indices)for(j in i+1 until rectangles.size) {
            val a=rectangles[i];val b=rectangles[j]
            if((a[0]-b[0]).abs()<(a[2]+b[2])/BigDecimal(2)&&(a[1]-b[1]).abs()<(a[3]+b[3])/BigDecimal(2)) invalid("Объекты схемы пересекаются")
        }
    }
    private fun image(file: MultipartFile): ByteArray {
        if(file.isEmpty||file.size>20L*1024*1024) throw AppException(ErrorCode.VALIDATION_FAILED,"Нужна фотография JPG/PNG до 20 МБ",HttpStatus.PAYLOAD_TOO_LARGE)
        val bytes=file.bytes
        val png=bytes.size>=8&&bytes.copyOfRange(0,8).contentEquals(byteArrayOf(0x89.toByte(),80,78,71,13,10,26,10))
        val jpg=bytes.size>=3&&bytes[0]==0xff.toByte()&&bytes[1]==0xd8.toByte()&&bytes[2]==0xff.toByte()
        if(!png&&!jpg) invalid("Файл не является JPG/PNG")
        ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { stream ->
            val reader=ImageIO.getImageReaders(stream).asSequence().firstOrNull()?:invalid("Фотография не читается")
            try { reader.input=stream;val width=reader.getWidth(0);val height=reader.getHeight(0)
                if(width<1||height<1||width.toLong()*height>12_000_000) invalid("Уменьшите фотографию до 12 мегапикселей")
                val type=reader.getImageTypes(0).asSequence().firstOrNull{it.colorModel.pixelSize<=32}?:invalid("Недопустимая глубина цвета")
                reader.read(0,reader.defaultReadParam.apply{destinationType=type})?.flush()?:invalid("Фотография не читается")
            }catch(e: AppException){throw e}catch(_: Exception){invalid("Фотография не читается")}finally{reader.dispose()}
        }
        return bytes
    }
}
