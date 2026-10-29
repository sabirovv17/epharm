package kz.epharm.merch.tasks

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.time.Instant
import java.util.UUID
import kotlin.math.*
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

data class TaskStockProduct(val key:String,val name:String)
data class SaveTaskStock(val expectedAttempt:Int,val counts:Map<String,com.fasterxml.jackson.databind.JsonNode>,val shortageReason:String="")
data class TaskCapture(val status:String="unavailable",val latitude:Double?=null,val longitude:Double?=null,
    val accuracy:Double?=null,val measuredAt:Instant?=null,val reason:String?=null,val captureSource:String?=null)
data class TaskStockDto(val counts:Map<String,Int>,val shortageReason:String,val savedAt:Instant,val source:String="current_attempt")
data class TaskExecutionPolicy(val requiresStock:Boolean,val products:List<TaskStockProduct>,val stock:TaskStockDto?,
    val trainingMaterialId:UUID?,val requiresVideo:Boolean,val referenceImage:String?,val clientReferencePhotoId:UUID?=null)

/** The source's stock/geo requirements are execution evidence, never proof of identity or automatic approval. */
@Component
class TaskExecutionEvidence(private val jdbc:JdbcTemplate,private val json:ObjectMapper,private val storage:TaskEvidenceStorage) {
    fun policy(item:UUID,attempt:UUID?=null):TaskExecutionPolicy {
        val row=jdbc.query("""SELECT i.shelf_id,i.task_type,i.training_material_id,i.stock_products::text,i.reference_image_ref,
            p.product_name,p.products::text AS placement_products,i.client_reference_photo_id FROM merch_task_items i
            LEFT JOIN merch_placement_item_product_details p ON p.media_plan_item_id=i.media_plan_item_id WHERE i.id=?""",
            {rs,_->arrayOf<Any?>(rs.getObject("shelf_id"),rs.getString("task_type"),rs.getObject("training_material_id",UUID::class.java),
                rs.getString("stock_products"),rs.getString("reference_image_ref"),rs.getString("product_name"),rs.getString("placement_products"),rs.getObject("client_reference_photo_id",UUID::class.java))},item).single()
        val material=row[2] as UUID?
        val custom=json.readTree(row[3] as String)
        val requires=material==null && (custom.size()>0 || row[0]!=null && row[1] in setOf("photo_report","placement","inventory"))
        val products=if(!requires) emptyList() else if(custom.size()>0) custom.map {TaskStockProduct(it.path("key").asText(),it.path("name").asText())} else {
            val details=(row[6] as String?)?.let(json::readTree)
            if(details?.isArray==true && details.size()>0) details.mapIndexed {index,p->TaskStockProduct(index.toString(),
                p.path("productName").asText().ifBlank {p.path("name").asText().ifBlank {row[5] as String? ?: "Товар на полке"}})}
            else listOf(TaskStockProduct("0",row[5] as String? ?: "Товар на полке"))
        }
        if(products.size>100 || products.any {it.key.length !in 1..64 || it.name.length !in 1..1000} || products.map {it.key}.distinct().size!=products.size)
            conflict("Состав товаров задания требует проверки источника")
        val stock=if(attempt==null) jdbc.query("SELECT counts::text,shortage_reason,updated_at FROM merch_task_source_stock WHERE item_id=?",
            {rs,_->TaskStockDto(json.readValue(rs.getString(1)),rs.getString(2),rs.getTimestamp(3).toInstant(),"preserved_source")},item).singleOrNull()
        else jdbc.query("SELECT counts::text,shortage_reason,updated_at FROM merch_task_stock_observations WHERE item_id=? AND attempt_id=?",
            {rs,_->TaskStockDto(json.readValue(rs.getString(1)),rs.getString(2),rs.getTimestamp(3).toInstant())},item,attempt).singleOrNull()
        return TaskExecutionPolicy(requires,products,stock,material,material!=null,trustedReference(row[4] as String?),row[7] as UUID?)
    }
    fun save(item:UUID,attempt:UUID,request:SaveTaskStock):TaskStockDto {
        val policy=policy(item,attempt)
        if(!policy.requiresStock) invalid("Для этого пункта подсчёт не требуется")
        val counts=request.counts.mapValues {(_,v)->if(v.isIntegralNumber && v.canConvertToInt()) v.intValue() else invalid("Количество должно быть целым числом")}
        validate(policy.products,counts,request.shortageReason)
        jdbc.update("""INSERT INTO merch_task_stock_observations(attempt_id,item_id,counts,shortage_reason,created_at,updated_at)
            VALUES(?,?,CAST(? AS jsonb),?,now(),now()) ON CONFLICT(attempt_id) DO UPDATE SET counts=excluded.counts,
            shortage_reason=excluded.shortage_reason,updated_at=now()""",attempt,item,json.writeValueAsString(counts),request.shortageReason.trim())
        return policy(item,attempt).stock!!
    }
    fun requireSubmission(item:UUID,attempt:UUID) {
        val policy=policy(item,attempt)
        if(policy.requiresStock) validate(policy.products,policy.stock?.counts,policy.stock?.shortageReason.orEmpty())
        if(policy.requiresVideo && jdbc.queryForObject("SELECT count(*) FROM merch_task_evidence WHERE attempt_id=? AND mime_type IN ('video/mp4','video/quicktime','video/webm')",Int::class.java,attempt)==0)
            invalid("Прикрепите новое видео прохождения инструктажа")
    }
    fun requireReview(item:UUID,attempt:UUID,reason:String) {
        val policy=policy(item,attempt)
        if(policy.requiresStock && (policy.stock==null || policy.products.any {(policy.stock.counts[it.key]?:-1)<5}) && reason.trim().length<3)
            invalid("Для исключения по остаткам укажите причину принятия")
    }
    fun instruction(item:UUID):Pair<MerchTaskEvidenceDto,ByteArray> {
        val row=jdbc.query("""SELECT m.* FROM merch_task_items i JOIN merch_instruction_materials m ON m.id=i.training_material_id
            WHERE i.id=? AND m.status='approved'""",{rs,_->
            val bytes=storage.read(rs.getString("storage_backend"),rs.getString("object_key"),rs.getString("sha256"))
            if(bytes.size<5 || !bytes.copyOfRange(0,5).contentEquals("%PDF-".toByteArray())) conflict("Файл инструктажа не соответствует PDF")
            rs.getObject("byte_size")?.let {if((it as Number).toLong()!=bytes.size.toLong()) conflict("Размер инструктажа не совпадает с исходным")}
            MerchTaskEvidenceDto(rs.getObject("id",UUID::class.java),"application/pdf",bytes.size.toLong(),TaskEvidenceStorage.hash(bytes),
                rs.getString("original_filename"),rs.getTimestamp("created_at").toInstant()) to bytes
        },item).singleOrNull()
        return row?:throw AppException(ErrorCode.NOT_FOUND,"Подтверждённый PDF-инструктаж не найден",HttpStatus.NOT_FOUND)
    }
    fun reference(item:UUID):Pair<MerchTaskEvidenceDto,ByteArray> {
        // The native writer pins the active immutable version. JVM task timestamps
        // and PostgreSQL archive timestamps are independent clocks, not an authorization boundary.
        val row=jdbc.queryForList("""SELECT r.* FROM merch_task_items i JOIN merch_task_dispatches d ON d.id=i.dispatch_id
            JOIN merch_placement_reference_photos r ON r.id=i.client_reference_photo_id AND r.media_plan_item_id=i.media_plan_item_id
            JOIN merch_media_plan_items p ON p.id=i.media_plan_item_id AND p.shelf_id=i.shelf_id
            JOIN merch_shelves s ON s.id=p.shelf_id AND s.branch_id=d.branch_id
            WHERE i.id=?""",item).singleOrNull()
            ?:throw AppException(ErrorCode.NOT_FOUND,"Зафиксированный эталон пункта не найден",HttpStatus.NOT_FOUND)
        val bytes=storage.read(row["storage_backend"].toString(),row["object_key"].toString(),row["sha256"].toString())
        if(bytes.size.toLong()!=(row["byte_size"] as Number).toLong()) conflict("Размер эталона не совпадает с сохранённым")
        val mime=row["mime_type"].toString();if(mime !in setOf("image/png","image/jpeg","image/webp")) conflict("Формат эталона не поддерживается")
        return MerchTaskEvidenceDto(row["id"] as UUID,mime,bytes.size.toLong(),row["sha256"].toString(),row["file_name"].toString(),(row["created_at"] as java.sql.Timestamp).toInstant()) to bytes
    }
    fun capture(item:UUID,capture:TaskCapture?):Map<String,Any?> {
        val now=Instant.now()
        if(capture?.status!="available") return mapOf("verification" to "manual_review_required","location" to mapOf("status" to "unavailable",
            "reason" to (capture?.reason?:"Местоположение не получено").take(300)),"captureSource" to capture?.captureSource?.take(30),"receivedAt" to now.toString())
        val lat=capture.latitude;val lng=capture.longitude;val accuracy=capture.accuracy
        if(lat==null || lng==null || accuracy==null || !lat.isFinite() || !lng.isFinite() || !accuracy.isFinite() || lat !in -90.0..90.0 || lng !in -180.0..180.0 || accuracy !in 0.0..100000.0)
            invalid("Некорректные координаты фотографии")
        if(capture.measuredAt==null || abs(java.time.Duration.between(capture.measuredAt,now).seconds)>600)
            return mapOf("verification" to "manual_review_required","location" to mapOf("status" to "unavailable","reason" to "Местоположение устарело"),"receivedAt" to now.toString())
        val branch=jdbc.query("""SELECT b.latitude,b.longitude FROM merch_task_items i JOIN merch_task_dispatches d ON d.id=i.dispatch_id
            JOIN merch_branches b ON b.id=d.branch_id WHERE i.id=?""",{rs,_->rs.getBigDecimal(1)?.toDouble() to rs.getBigDecimal(2)?.toDouble()},item).single()
        val distance=if(branch.first==null || branch.second==null) null else distance(branch.first!!,branch.second!!,lat,lng).roundToLong()
        return mapOf("verification" to if(distance!=null && distance<=200 && accuracy<=100) "location_nearby_manual_review" else "manual_review_required",
            "location" to mapOf("status" to "available","latitude" to lat,"longitude" to lng,"accuracy" to accuracy,"measuredAt" to capture.measuredAt.toString()),
            "distanceMeters" to distance,"captureSource" to capture.captureSource?.take(30),"receivedAt" to now.toString())
    }
    private fun validate(products:List<TaskStockProduct>,counts:Map<String,Int>?,reason:String) {
        if(counts==null || counts.keys!=products.map {it.key}.toSet() || counts.values.any {it !in 0..100000})
            invalid("Укажите целое количество упаковок каждого товара от 0 до 100000")
        if(reason.length>1200 || counts.values.any {it<5} && reason.trim().length<3)
            invalid("Если упаковок меньше пяти, укажите причину")
    }
    private fun trustedReference(value:String?):String? = value?.takeIf {
        it=="/pharmacy-shelf.png" || it.matches(Regex("(?:https://(?:crm|epharm)\\.inkar\\.kz)?/api/(?:request-item-files|placement-files)/[A-Za-z0-9_/-]+\\.[A-Za-z0-9]+"))
    }
    private fun distance(a:Double,b:Double,c:Double,d:Double):Double {val dlat=Math.toRadians(c-a);val dlng=Math.toRadians(d-b)
        return 6371000*2*asin(min(1.0,sqrt(sin(dlat/2).pow(2)+cos(Math.toRadians(a))*cos(Math.toRadians(c))*sin(dlng/2).pow(2))))}
    private fun invalid(message:String):Nothing=throw AppException(ErrorCode.VALIDATION_FAILED,message)
    private fun conflict(message:String):Nothing=throw AppException(ErrorCode.CONFLICT,message,HttpStatus.CONFLICT)
}
