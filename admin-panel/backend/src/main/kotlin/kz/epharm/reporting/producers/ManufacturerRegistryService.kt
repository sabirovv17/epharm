package kz.epharm.reporting.producers

import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.UUID
import kz.epharm.auth.access.AdministrativeScope
import kz.epharm.auth.access.UnifiedAdminCatalog
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class CreateManufacturer(val requestId:UUID,val name:String,val reference:String) {
    @JsonAnySetter fun rejectUnknown(key:String,value:Any?) {throw IllegalArgumentException("Unsupported manufacturer field")}
}
data class ManufacturerRegistryView(val id:UUID,val name:String,val profileComplete:Boolean=true)

/** An explicit business resource for campaign attribution, with no external login or inferred person identity. */
@Service
class ManufacturerRegistryService(private val jdbc:JdbcTemplate,private val access:AdministrativeScope,
    private val catalog:UnifiedAdminCatalog,private val json:ObjectMapper) {
    @Transactional
    fun create(request:CreateManufacturer,actor:UUID):ManufacturerRegistryView {
        access.lockCurrentActor()
        access.requireExplicitPermission(catalog.producerBindingManage);access.requireAllPermission(catalog.producerBindingManage)
        val view=UnifiedAdminCatalog.key("motivation","campaigns","view")
        access.requireExplicitPermission(view);access.requireAllPermission(view)
        val name=request.name.trim();val reference=request.reference.trim()
        if(name.length !in 1..255 || reference.length !in 1..1000 || name.any(Char::isISOControl) || reference.any(Char::isISOControl))
            throw AppException(ErrorCode.VALIDATION_FAILED,"Укажите название производителя (до 255 символов) и основание (до 1000 символов)")
        val existing=jdbc.queryForList("SELECT id,source_system,source_origin,source_id,source_payload::text AS evidence,name,source_account_role,active,phone,phone_normalized,phone_verified_at FROM merch_producers WHERE id=? FOR UPDATE",request.requestId).singleOrNull()
        val link=jdbc.queryForList("""SELECT canonical_entity_type,canonical_id,verification_reference FROM unified_source_links
            WHERE source_system='EPHARM' AND source_origin='https://epharm.inkar.kz' AND source_entity_type='MANUFACTURER' AND source_id=?""",request.requestId.toString()).singleOrNull()
        if(existing!=null) {
            val evidence=json.readTree(existing["evidence"] as String)
            val audited=jdbc.queryForObject("""SELECT count(*) FROM unified_identity_audit WHERE entity_type='MANUFACTURER'
                AND entity_id=? AND operation='CREATE_MANUFACTURER' AND actor_id=?""",Long::class.java,request.requestId.toString(),actor)==1L
            if(existing["source_system"]!="EPHARM" || existing["source_origin"]!="https://epharm.inkar.kz" || existing["source_id"]!=request.requestId ||
                existing["source_account_role"]!="internal_manufacturer" || existing["active"]!=true || existing["name"]!=name ||
                existing["phone"]!=null || existing["phone_normalized"]!=null || existing["phone_verified_at"]!=null ||
                evidence["kind"]?.asText()!="HQ_DEFINED_MANUFACTURER" || evidence["createdByAdminId"]?.asText()!=actor.toString() ||
                link==null || link["canonical_entity_type"]!="CUSTOMER" || link["canonical_id"]!=request.requestId.toString() || link["verification_reference"]!=reference || !audited) conflict()
            return ManufacturerRegistryView(request.requestId,name)
        }
        if(link!=null) conflict()
        jdbc.update("""INSERT INTO merch_producers(id,source_system,source_origin,source_id,source_payload,name,phone,phone_normalized,
            phone_verified_at,source_account_role,active,created_at,updated_at)
            VALUES(?,'EPHARM','https://epharm.inkar.kz',?,?::jsonb,?,NULL,NULL,NULL,'internal_manufacturer',true,now(),now())""",
            request.requestId,request.requestId,json.writeValueAsString(mapOf("kind" to "HQ_DEFINED_MANUFACTURER","createdByAdminId" to actor)),name)
        jdbc.update("""INSERT INTO unified_source_links(source_system,source_origin,source_entity_type,source_id,canonical_entity_type,canonical_id,verification_reference)
            VALUES('EPHARM','https://epharm.inkar.kz','MANUFACTURER',?,'CUSTOMER',?,?)""",request.requestId.toString(),request.requestId.toString(),reference)
        jdbc.update("""INSERT INTO unified_identity_audit(id,actor_id,entity_type,entity_id,operation)
            VALUES(?,?,'MANUFACTURER',?,'CREATE_MANUFACTURER')""",UUID.randomUUID(),actor,request.requestId.toString())
        return ManufacturerRegistryView(request.requestId,name)
    }
    private fun conflict():Nothing=throw AppException(ErrorCode.CONFLICT,"Этот requestId уже относится к другой записи или другому основанию; данные не изменены",HttpStatus.CONFLICT)
}
