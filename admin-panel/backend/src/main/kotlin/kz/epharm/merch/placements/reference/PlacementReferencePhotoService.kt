package kz.epharm.merch.placements.reference

import com.fasterxml.jackson.databind.ObjectMapper
import kz.epharm.merch.placements.dto.PlacementActor
import kz.epharm.merch.placements.repository.PlacementRepository
import kz.epharm.merch.placements.service.PlacementService
import kz.epharm.merch.tasks.TaskEvidenceStorage
import kz.epharm.merch.tasks.TaskPhotoGate
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import java.io.ByteArrayInputStream
import java.time.Instant
import java.util.UUID
import javax.imageio.ImageIO

data class ReferencePhoto(val id:UUID,val mimeType:String,val byteSize:Long,val fileName:String,val createdAt:Instant,val archivedAt:Instant?)
data class ReferencePhotoState(val placementId:UUID,val itemId:UUID,val revision:Long,val canManage:Boolean,val photo:ReferencePhoto?,val history:List<ReferencePhoto>)
data class ArchiveReferencePhoto(val expectedRevision:Long,val photoId:UUID)

@Service
class PlacementReferencePhotoService(private val placements:PlacementService,private val repository:PlacementRepository,
    private val jdbc:JdbcTemplate,private val files:TaskEvidenceStorage,private val json:ObjectMapper) {
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    fun view(plan:UUID,item:UUID,actor:PlacementActor):ReferencePhotoState {
        val (current,items)=placements.referenceContext(plan,actor)
        val selected=items.singleOrNull {it.id==item}?:missing()
        val history=rows(item)
        return ReferencePhotoState(plan,item,current.revision,placements.canManageReference(current,selected,items,actor),history.singleOrNull {it.archivedAt==null},history)
    }
    @Transactional
    fun upload(plan:UUID,item:UUID,expected:Long,request:UUID,file:MultipartFile,actor:PlacementActor):ReferencePhotoState = TaskPhotoGate.run {
        val (current,items)=placements.referenceContext(plan,actor,write=true)
        val selected=items.singleOrNull {it.id==item}?:missing()
        if(!placements.canManageReference(current,selected,items,actor)) conflict("Фото можно изменить только до утверждения ожидающей позиции")
        val (mime,bytes)=image(file);val sha=TaskEvidenceStorage.hash(bytes)
        val name=file.originalFilename.orEmpty().substringAfterLast('/').substringAfterLast('\\').filterNot(Char::isISOControl).take(240).ifBlank {"reference-photo"}
        val admin=(actor as? PlacementActor.Internal)?.accountId;val producer=(actor as? PlacementActor.Producer)?.customerId
        val existing=jdbc.queryForList("SELECT * FROM merch_placement_reference_photos WHERE id=?",request).singleOrNull()
        if(existing!=null) {
            if(existing["media_plan_item_id"]==item && existing["sha256"]==sha && existing["file_name"]==name && existing["created_by_admin"]==admin &&
                existing["created_by_producer"]==producer && existing["archived_at"]==null && current.revision==expected+1) return@run view(plan,item,actor)
            conflict("ID фотографии уже использован или версия размещения изменилась")
        }
        if(expected<0 || current.revision!=expected) conflict("Медиаплан изменился: загрузите актуальную версию")
        val key="placement-reference/$item/$request"
        files.write(key,bytes)
        jdbc.update("UPDATE merch_placement_reference_photos SET archived_at=now(),archived_by_admin=?,archived_by_producer=? WHERE media_plan_item_id=? AND archived_at IS NULL",admin,producer,item)
        if(jdbc.update("""INSERT INTO merch_placement_reference_photos(id,media_plan_item_id,storage_backend,object_key,mime_type,byte_size,sha256,file_name,created_by_admin,created_by_producer)
            VALUES(?,?,'private_host',?,?,?,?,?,?,?) ON CONFLICT(id) DO NOTHING""",request,item,key,mime,bytes.size,sha,name,admin,producer)!=1) conflict("ID фотографии уже использован")
        audit(plan,actor,"reference_photo_uploaded",mapOf("itemId" to item,"photoId" to request,"sha256" to sha))
        view(plan,item,actor)
    }
    @Transactional
    fun archive(plan:UUID,item:UUID,body:ArchiveReferencePhoto,actor:PlacementActor):ReferencePhotoState {
        val (current,items)=placements.referenceContext(plan,actor,write=true)
        val selected=items.singleOrNull {it.id==item}?:missing()
        if(!placements.canManageReference(current,selected,items,actor)) conflict("Фото можно изменить только до утверждения ожидающей позиции")
        val photo=jdbc.queryForList("SELECT * FROM merch_placement_reference_photos WHERE id=? AND media_plan_item_id=?",body.photoId,item).singleOrNull()?:missing()
        val admin=(actor as? PlacementActor.Internal)?.accountId;val producer=(actor as? PlacementActor.Producer)?.customerId
        if(photo["archived_at"]!=null && current.revision==body.expectedRevision+1 && photo["archived_by_admin"]==admin && photo["archived_by_producer"]==producer) return view(plan,item,actor)
        if(body.expectedRevision<0 || current.revision!=body.expectedRevision || photo["archived_at"]!=null) conflict("Медиаплан или фотография изменились")
        jdbc.update("UPDATE merch_placement_reference_photos SET archived_at=now(),archived_by_admin=?,archived_by_producer=? WHERE id=?",admin,producer,body.photoId)
        audit(plan,actor,"reference_photo_archived",mapOf("itemId" to item,"photoId" to body.photoId))
        return view(plan,item,actor)
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    fun file(plan:UUID,item:UUID,id:UUID,actor:PlacementActor):Pair<String,ByteArray> {
        val (_,items)=placements.referenceContext(plan,actor)
        if(items.none {it.id==item}) missing()
        val row=jdbc.queryForList("SELECT storage_backend,object_key,mime_type,sha256,byte_size FROM merch_placement_reference_photos WHERE id=? AND media_plan_item_id=?",id,item).singleOrNull()?:missing()
        val bytes=files.read(row["storage_backend"].toString(),row["object_key"].toString(),row["sha256"].toString())
        if(bytes.size.toLong()!=(row["byte_size"] as Number).toLong()) conflict("Размер сохранённой фотографии изменился")
        return row["mime_type"].toString() to bytes
    }
    private fun rows(item:UUID)=jdbc.query("SELECT id,mime_type,byte_size,file_name,created_at,archived_at FROM merch_placement_reference_photos WHERE media_plan_item_id=? ORDER BY created_at DESC,id",{rs,_->
        ReferencePhoto(rs.getObject("id",UUID::class.java),rs.getString("mime_type"),rs.getLong("byte_size"),rs.getString("file_name"),rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("archived_at")?.toInstant())},item)
    private fun audit(plan:UUID,actor:PlacementActor,action:String,details:Any) {
        val revision=repository.bump(plan)
        repository.audit(plan,revision,actor,action,json.writeValueAsString(details),Instant.now())
    }
    private fun image(file:MultipartFile):Pair<String,ByteArray> {
        if(file.isEmpty || file.size>8L*1024*1024) throw AppException(ErrorCode.VALIDATION_FAILED,"Загрузите JPG/PNG/WebP до 8 МБ",HttpStatus.PAYLOAD_TOO_LARGE)
        val bytes=file.bytes
        return try {ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use {stream->
            val reader=ImageIO.getImageReaders(stream).asSequence().firstOrNull()?:invalid("Фотография повреждена")
            try {reader.input=stream
                val mime=when(reader.formatName.lowercase(java.util.Locale.ROOT)) {"jpeg","jpg"->"image/jpeg";"png"->"image/png";"webp"->"image/webp";else->invalid("Нужна фотография JPG/PNG/WebP")}
                val width=reader.getWidth(0);val height=reader.getHeight(0)
                if(width<1 || height<1) invalid("Фотография повреждена")
                if(width.toLong()*height.toLong()>12_000_000L) throw AppException(ErrorCode.VALIDATION_FAILED,"Уменьшите фотографию до 12 мегапикселей",HttpStatus.PAYLOAD_TOO_LARGE)
                val type=reader.getImageTypes(0).asSequence().firstOrNull {it.colorModel.pixelSize<=32}?:invalid("Недопустимая глубина цвета")
                reader.read(0,reader.defaultReadParam.apply {destinationType=type})?.flush()?:invalid("Фотография повреждена")
                mime to bytes
            } finally {reader.dispose()}
        }} catch(e:AppException) {throw e} catch(_:Exception) {invalid("Фотография повреждена")}
    }
    private fun missing():Nothing=throw AppException(ErrorCode.NOT_FOUND,"Фотография или позиция не найдена",HttpStatus.NOT_FOUND)
    private fun invalid(message:String):Nothing=throw AppException(ErrorCode.VALIDATION_FAILED,message,HttpStatus.BAD_REQUEST)
    private fun conflict(message:String):Nothing=throw AppException(ErrorCode.CONFLICT,message,HttpStatus.CONFLICT)
}
