package kz.epharm.merch.producer.auth

import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import org.springframework.http.ResponseEntity
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import java.util.UUID

/** Source018 legal/bank metadata only; phone, account identity and supplier tariffs are separate authorities. */
data class ProducerLegalProfile(val companyName:String?=null,val legalName:String?=null,val bin:String?=null,
    val legalAddress:String?=null,val actualAddress:String?=null,val bankName:String?=null,val bic:String?=null,
    val iban:String?=null,val kbe:String?=null,val directorName:String?=null,val directorPosition:String?=null,
    val contactPosition:String?=null,val contactEmail:String?=null,val vatRegistered:Boolean=false) {
    @JsonAnySetter fun rejectUnknown(key:String,value:Any?) {throw IllegalArgumentException("Unsupported profile field")}
}
data class UpdateProducerLegalProfile(@field:Min(0) val expectedRevision:Long,@field:Valid val profile:ProducerLegalProfile) {
    @JsonAnySetter fun rejectUnknown(key:String,value:Any?) {throw IllegalArgumentException("Unsupported profile field")}
}
data class ProducerLegalProfileView(val id:UUID,val revision:Long,val profile:ProducerLegalProfile,val profileComplete:Boolean)

@Service
class ProducerCompanyProfileService(private val jdbc:JdbcTemplate,private val json:ObjectMapper,private val auth:ProducerAuthService) {
    private val names=mapOf("companyName" to "company_name","legalName" to "legal_name","bin" to "bin","legalAddress" to "legal_address",
        "actualAddress" to "actual_address","bankName" to "bank_name","bic" to "bic","iban" to "iban","kbe" to "kbe",
        "directorName" to "director_name","directorPosition" to "director_position","contactPosition" to "contact_position",
        "contactEmail" to "contact_email","vatRegistered" to "vat_registered")
    @Transactional(readOnly=true)
    fun view(principal:ProducerPrincipal):ProducerLegalProfileView {
        auth.me(principal)
        val row=jdbc.queryForList("SELECT source_payload::text AS original,profile_json::text AS edited,profile_revision FROM merch_producers WHERE id=?",principal.customerId).single()
        val original=json.readTree(row["original"] as String)
        val edited: Map<String,Any?> = json.readValue(row["edited"] as String)
        val effective=linkedMapOf<String,Any?>()
        for((camel,snake) in names) effective[camel]=when {
            edited.containsKey(camel)->edited[camel]
            original.hasNonNull(snake)->json.convertValue(original[snake],Any::class.java)
            camel=="vatRegistered"->false
            else->null
        }
        // The typed name is current operational data; null is a truthful incomplete profile.
        effective["companyName"]=jdbc.queryForObject("SELECT name FROM merch_producers WHERE id=?",String::class.java,principal.customerId)
        val fields=json.convertValue(effective,ProducerLegalProfile::class.java)
        return ProducerLegalProfileView(principal.customerId,(row["profile_revision"] as Number).toLong(),fields,!fields.companyName.isNullOrBlank())
    }
    @Transactional
    fun save(principal:ProducerPrincipal,request:UpdateProducerLegalProfile):ProducerLegalProfileView {
        auth.lockForWrite(principal)
        val before=view(principal)
        if(before.revision!=request.expectedRevision) throw AppException(ErrorCode.CONFLICT,"Профиль изменился: загрузите актуальную версию",HttpStatus.CONFLICT)
        val raw: Map<String,Any?> = json.convertValue(request.profile,Map::class.java).entries.associate {it.key.toString() to it.value}
        val cleaned=raw.mapValues {(_,value)->if(value is String) value.trim().takeIf(String::isNotEmpty) else value}.toMutableMap()
        for(key in listOf("bic","iban")) (cleaned[key] as? String)?.let {cleaned[key]=it.uppercase()}
        if(cleaned.values.any {it is String && it.length>1000}) invalid()
        fun matches(field:String,pattern:String) {val value=cleaned[field] as? String;if(value!=null && !Regex(pattern).matches(value)) invalid()}
        matches("bin","[0-9]{12}");matches("bic","[A-Z0-9]{8,11}");matches("iban","KZ[0-9A-Z]{18}");matches("kbe","[0-9]{2}")
        matches("contactEmail","[^\\s@]+@[^\\s@]+\\.[^\\s@]+")
        val fields=json.convertValue(cleaned,ProducerLegalProfile::class.java)
        jdbc.update("UPDATE merch_producers SET name=?,profile_json=?::jsonb,profile_revision=profile_revision+1,updated_at=now() WHERE id=?",
            fields.companyName,json.writeValueAsString(fields),principal.customerId)
        jdbc.update("""INSERT INTO merch_producer_profile_audit(id,producer_id,session_id,revision,occurred_at,before_json,after_json)
            VALUES(?,?,?,?,now(),?::jsonb,?::jsonb)""",UUID.randomUUID(),principal.customerId,principal.sessionId,before.revision+1,
            json.writeValueAsString(before.profile),json.writeValueAsString(fields))
        return view(principal)
    }
    private fun invalid():Nothing=throw AppException(ErrorCode.VALIDATION_FAILED,"Проверьте реквизиты компании",HttpStatus.BAD_REQUEST)
}

@RestController
@RequestMapping("/api/producer/profile")
@PreAuthorize("hasRole('PRODUCER')")
class ProducerCompanyProfileController(private val profiles:ProducerCompanyProfileService) {
    @GetMapping fun view(@AuthenticationPrincipal principal:ProducerPrincipal)=ResponseEntity.ok().header("Cache-Control","no-store").body(profiles.view(principal))
    @PutMapping fun save(@AuthenticationPrincipal principal:ProducerPrincipal,@Valid @RequestBody body:UpdateProducerLegalProfile)=
        ResponseEntity.ok().header("Cache-Control","no-store").body(profiles.save(principal,body))
}
