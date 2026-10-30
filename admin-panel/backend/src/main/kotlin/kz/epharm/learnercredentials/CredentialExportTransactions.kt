package kz.epharm.learnercredentials

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kz.epharm.auth.access.AdministrativeScope
import kz.epharm.auth.access.UnifiedAdminCatalog
import kz.epharm.reporting.xlsx.ReportSheet
import kz.epharm.reporting.xlsx.ReportWorkbook
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** D9 is an explicit small report, never a field in the ordinary directory or its statistical XLSX. */
@Service
class CredentialExportTransactions(private val jdbc:JdbcTemplate,private val named:NamedParameterJdbcTemplate,
    private val access:AdministrativeScope,private val catalog:UnifiedAdminCatalog,private val config:LearnerCredentialConfig,
    private val crypto:LearnerCredentialCrypto,private val vault:LearnerPasswordVault,
    private val json:ObjectMapper) {
    @Transactional(noRollbackFor=[AppException::class])
    fun prepare(req:PrepareCredentialExport,actor:UUID):PreparedCredentialExport {
        if(req.pharmacistIds.size !in 1..config.maxBatchSize || req.pharmacistIds.distinct().size!=req.pharmacistIds.size || req.pharmacistIds.any{it.length !in 1..64 || it.any(Char::isISOControl)} ||
            req.purpose.trim().length !in 3..200 || req.purpose.any(Char::isISOControl) || req.currentPassword.length !in 1..256) invalid()
        val ids=req.pharmacistIds.sorted();val purpose=req.purpose.trim();val scopeHash=hash(json.writeValueAsString(ids))
        try {
            ready()
            val initial=rows(ids,false)
            var authority=authorize(req.currentPassword,initial,actor)
            val current=rows(ids,true)
            if(scope(initial)!=scope(current)) authority=authorize(req.currentPassword,current,actor)
            val fingerprints=current.associate{it.id to fingerprint(it)}
            val snapshot=hash(json.writeValueAsString(fingerprints))
            val now=Instant.now();val id=UUID.randomUUID();val expires=now.plusSeconds(config.exportTtlSeconds)
            val content=workbook(current,now)
            val encrypted=try {crypto.seal("learner-export",identity(id,actor),authority.authorizationFingerprint+snapshot,content)} finally {content.fill(0)}
                ?:unavailable()
            jdbc.update("""INSERT INTO learner_credential_exports(id,actor_id,authority_fingerprint,purpose,pharmacist_ids,row_fingerprints,snapshot_fingerprint,
                key_id,nonce,ciphertext,row_count,created_at,expires_at) VALUES(?,?,?,?,?::jsonb,?::jsonb,?,?,?,?,?,?,?)""",
                id,actor,authority.authorizationFingerprint,purpose,json.writeValueAsString(ids),json.writeValueAsString(fingerprints),snapshot,
                encrypted.keyId,encrypted.nonce,encrypted.ciphertext,current.size,Timestamp.from(now),Timestamp.from(expires))
            audit(actor,id,"prepared",purpose,scopeHash,current.size,"prepared")
            return PreparedCredentialExport(id,expires,current.size)
        } catch(error:AppException) {
            audit(actor,null,"denied",purpose,scopeHash,0,outcome(error));throw error
        }
    }

    @Transactional(noRollbackFor=[AppException::class])
    fun download(id:UUID,req:DownloadCredentialExport,actor:UUID):ByteArray {
        if(req.currentPassword.length !in 1..256) invalid()
        // Only metadata is read before proof. Another account cannot discover this resource.
        val before=job(id,actor,false)?:missing()
        if(!before.expires.isAfter(Instant.now())) {expire(before);missing()}
        try {
            ready()
            val initial=rows(before.ids,false)
            var authority=authorize(req.currentPassword,initial,actor)
            val current=rows(before.ids,true)
            if(scope(initial)!=scope(current)) authority=authorize(req.currentPassword,current,actor)
            val locked=job(id,actor,true)?:missing()
            if(!locked.expires.isAfter(Instant.now())) {expire(locked);missing()}
            val fingerprints=current.associate{it.id to fingerprint(it)}
            if(locked.authority!=authority.authorizationFingerprint || fingerprints!=locked.fingerprints) {
                jdbc.update("DELETE FROM learner_credential_exports WHERE id=? AND actor_id=?",id,actor)
                audit(actor,id,"invalidated",locked.purpose,hash(json.writeValueAsString(locked.ids)),current.size,"credentials_or_authority_changed")
                conflict()
            }
            val bytes=crypto.open("learner-export",identity(id,actor),locked.authority+locked.snapshot,locked.sealed)?:unavailable()
            // A single transaction holds account and learner locks through the download decision.
            if(jdbc.update("DELETE FROM learner_credential_exports WHERE id=? AND actor_id=?",id,actor)!=1) {bytes.fill(0);missing()}
            audit(actor,id,"downloaded",locked.purpose,hash(json.writeValueAsString(locked.ids)),current.size,"downloaded")
            return bytes
        } catch(error:AppException) {
            audit(actor,id,"denied",before.purpose,hash(json.writeValueAsString(before.ids)),0,outcome(error));throw error
        }
    }

    @Transactional
    fun revoke(id:UUID,actor:UUID) {
        access.lockCurrentActor()
        val row=job(id,actor,true)?:return
        jdbc.update("DELETE FROM learner_credential_exports WHERE id=? AND actor_id=?",id,actor)
        audit(actor,id,"revoked",row.purpose,hash(json.writeValueAsString(row.ids)),row.ids.size,"revoked")
    }
    @Transactional
    fun cleanup():Int {
        val expired=jdbc.query("SELECT id,actor_id,purpose,pharmacist_ids::text,row_count FROM learner_credential_exports WHERE expires_at<=now() ORDER BY expires_at,id LIMIT 100 FOR UPDATE SKIP LOCKED",{rs,_->
            arrayOf<Any>(rs.getObject("id",UUID::class.java),rs.getObject("actor_id",UUID::class.java),rs.getString("purpose"),rs.getString("pharmacist_ids"),rs.getInt("row_count"))})
        for(row in expired) {
            jdbc.update("DELETE FROM learner_credential_exports WHERE id=?",row[0])
            audit(row[1] as UUID,row[0] as UUID,"expired",row[2] as String,hash(row[3] as String),row[4] as Int,"expired")
        }
        jdbc.update("DELETE FROM learner_credential_export_attempts WHERE id IN (SELECT id FROM learner_credential_export_attempts WHERE created_at<now()-interval '1 day' ORDER BY created_at LIMIT 1000)")
        return expired.size
    }
    private fun expire(job:Job) {
        jdbc.update("DELETE FROM learner_credential_exports WHERE id=? AND actor_id=?",job.id,job.actor)
        audit(job.actor,job.id,"expired",job.purpose,hash(json.writeValueAsString(job.ids)),job.ids.size,"expired")
    }
    private fun authorize(password:String,rows:List<Row>,actor:UUID):CredentialExportAuthority {
        val (pharmacies,unassigned)=scope(rows)
        return access.requireLearnerCredentialExport(password,pharmacies,unassigned).also{if(it.accountId!=actor) denied()}
    }
    private fun scope(rows:List<Row>)=rows.mapNotNull{it.pharmacyId}.toSet() to rows.any{it.pharmacyId==null}
    private fun rows(ids:List<String>,lock:Boolean):List<Row> {
        val scope=access.currentPharmacyIds()
        if(scope?.isEmpty()==true) denied()
        val where=if(scope==null)"" else " AND p.pharmacy_id IN (:scope)"
        val found=named.query("""SELECT p.id,p.name,p.iin,p.phone,p.password_hash,p.status,p.pharmacy_id,ph.name AS pharmacy_name,ph.city,ph.addr
            FROM pharmacists p LEFT JOIN pharmacies ph ON ph.id=p.pharmacy_id
            WHERE p.id IN (:ids)$where ORDER BY p.id ${if(lock)"FOR UPDATE OF p" else ""}""",mapOf("ids" to ids,"scope" to scope)) {rs,_->
            Row(rs.getString("id"),rs.getString("name"),rs.getString("iin"),rs.getString("phone"),rs.getString("password_hash"),rs.getString("status"),rs.getString("pharmacy_id"),rs.getString("pharmacy_name"),rs.getString("city"),rs.getString("addr"))}
        if(found.size!=ids.size) denied()
        return found
    }
    private fun workbook(rows:List<Row>,now:Instant):ByteArray {
        val data=rows.map {row->
            val personal=if(row.pharmacyId==null) access.canAllPermission(catalog.sensitive) else access.canPharmacyPermission(catalog.sensitive,row.pharmacyId)
            val copy=if(row.status=="blocked") LearnerPasswordCopy(null,"Недоступен: аккаунт заблокирован") else vault.read(row.id,row.passwordHash)
            if(copy.cryptographicFailure) unavailable()
            val method=if(row.passwordHash.isNullOrBlank())"Телефон и SMS" else "ИИН и пароль"
            val configuredLogin=if(row.passwordHash.isNullOrBlank())row.phone else row.iin
            val login=if(!personal) "Недоступен: нет права на персональные данные" else
                configuredLogin.takeIf(String::isNotBlank)?:"Недоступен: логин не настроен"
            listOf(row.id,row.name,method,login,copy.password?:copy.status,copy.status,
                if(personal)row.phone else "Недоступен по правам",if(personal)row.iin else "Недоступен по правам",
                row.pharmacyId.orEmpty(),row.pharmacyName.orEmpty(),row.city.orEmpty(),row.address.orEmpty(),now.toString())
        }
        return ReportWorkbook.render(listOf(ReportSheet("Учётные данные ЛК",listOf("ID фармацевта","ФИО","Способ входа","Действующий логин","Текущий пароль / недоступность","Состояние копии","Телефон","ИИН","ID аптеки","Аптека","Город","Адрес","Время среза UTC"),data)))
    }
    private fun fingerprint(row:Row)=hash(json.writeValueAsString(listOf(row.id,row.name,row.iin,row.phone,row.passwordHash,row.status,row.pharmacyId,row.pharmacyName,row.city,row.address)))
    private fun job(id:UUID,actor:UUID,lock:Boolean):Job?=jdbc.query("SELECT * FROM learner_credential_exports WHERE id=? AND actor_id=? ${if(lock)"FOR UPDATE" else ""}",{rs,_->
        Job(id,actor,rs.getString("authority_fingerprint"),rs.getString("purpose"),json.readValue(rs.getString("pharmacist_ids")),json.readValue(rs.getString("row_fingerprints")),rs.getString("snapshot_fingerprint"),
            SealedCredential(rs.getString("key_id"),rs.getBytes("nonce"),rs.getBytes("ciphertext")),rs.getTimestamp("expires_at").toInstant())},id,actor).singleOrNull()
    private fun audit(actor:UUID,id:UUID?,operation:String,purpose:String,scope:String,count:Int,outcome:String) {
        jdbc.update("INSERT INTO learner_credential_export_audit(id,actor_id,export_id,operation,purpose,scope_fingerprint,row_count,outcome,created_at) VALUES(?,?,?,?,?,?,?,?,now())",UUID.randomUUID(),actor,id,operation,purpose,scope,count,outcome)
    }
    private fun ready() {if(!config.enabled || !crypto.ready()) unavailable()}
    private fun identity(job:UUID,actor:UUID)="$job:$actor"
    private fun hash(value:String)=LearnerCredentialCrypto.fingerprint(value)
    private fun outcome(error:AppException)=when(error.code){ErrorCode.FORBIDDEN,ErrorCode.INVALID_CREDENTIALS->"authorization_denied";ErrorCode.UPSTREAM_UNAVAILABLE->"cryptography_unavailable";ErrorCode.CONFLICT->"stale";else->"unavailable"}
    private fun invalid():Nothing=throw AppException(ErrorCode.VALIDATION_FAILED,"Выберите допустимую порцию записей и укажите цель выгрузки")
    private fun denied():Nothing=throw AppException(ErrorCode.FORBIDDEN,"Нет права на выбранную порцию учётных данных",HttpStatus.FORBIDDEN)
    private fun missing():Nothing=throw AppException(ErrorCode.NOT_FOUND,"Выгрузка недоступна или срок её действия истёк",HttpStatus.NOT_FOUND)
    private fun conflict():Nothing=throw AppException(ErrorCode.CONFLICT,"Учётные данные или права изменились. Подготовьте новую выгрузку",HttpStatus.CONFLICT)
    private fun unavailable():Nothing=throw AppException(ErrorCode.UPSTREAM_UNAVAILABLE,"Защищённая выгрузка учётных данных временно недоступна",HttpStatus.SERVICE_UNAVAILABLE)
    private class Row(val id:String,val name:String,val iin:String,val phone:String,val passwordHash:String?,val status:String,val pharmacyId:String?,val pharmacyName:String?,val city:String?,val address:String?) {override fun toString()="CredentialRow(REDACTED)"}
    private class Job(val id:UUID,val actor:UUID,val authority:String,val purpose:String,val ids:List<String>,val fingerprints:Map<String,String>,val snapshot:String,val sealed:SealedCredential,val expires:Instant) {override fun toString()="CredentialJob(REDACTED)"}
}
