package kz.epharm.learnercredentials

import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Instant

class LearnerPasswordCopy(val password:String?,val status:String,val cryptographicFailure:Boolean=false) {
    override fun toString()="LearnerPasswordCopy(REDACTED)"
}

@Service
class LearnerPasswordVault(private val jdbc:JdbcTemplate,private val crypto:LearnerCredentialCrypto,private val config:LearnerCredentialConfig) {
    /** Called only after a writer/normal password login has proved the exact effective input. */
    @Transactional(propagation=Propagation.MANDATORY)
    fun capture(pharmacistId:String,passwordHash:String,password:String) {
        if(!config.enabled) return
        // A missing vault/key must not stop ordinary authentication. A savepoint contains optional
        // JDBC capture failures; any old row remains bound to its old hash and can never be current.
        jdbc.execute(ConnectionCallback<Unit> { connection->
            val savepoint=connection.setSavepoint()
            try {
                val current=jdbc.query("SELECT password_hash FROM pharmacists WHERE id=? FOR UPDATE",{rs,_->rs.getString(1)},pharmacistId).singleOrNull()
                if(current!=passwordHash) {connection.releaseSavepoint(savepoint);return@ConnectionCallback}
                val fingerprint=LearnerCredentialCrypto.fingerprint(passwordHash)
                val plaintext=password.toByteArray(Charsets.UTF_8)
                val sealed=try {crypto.seal("learner-password",pharmacistId,fingerprint,plaintext)} finally {plaintext.fill(0)}
                jdbc.update("""INSERT INTO learner_password_vault(pharmacist_id,credential_fingerprint,format_version,state,key_id,nonce,ciphertext,captured_at)
                    VALUES(?,?,1,?,?,?,?,?) ON CONFLICT(pharmacist_id) DO UPDATE SET credential_fingerprint=excluded.credential_fingerprint,
                    format_version=1,state=excluded.state,key_id=excluded.key_id,nonce=excluded.nonce,ciphertext=excluded.ciphertext,captured_at=excluded.captured_at""",
                    pharmacistId,fingerprint,if(sealed==null)"unavailable" else "captured",sealed?.keyId,sealed?.nonce,sealed?.ciphertext,Timestamp.from(Instant.now()))
                connection.releaseSavepoint(savepoint)
            } catch (_:Exception) {connection.rollback(savepoint);connection.releaseSavepoint(savepoint)}
        })
    }
    @Transactional(propagation=Propagation.MANDATORY)
    fun read(pharmacistId:String,passwordHash:String?):LearnerPasswordCopy {
        if(passwordHash.isNullOrBlank()) return LearnerPasswordCopy(null,"Вход по SMS: пароль не задан")
        val fingerprint=LearnerCredentialCrypto.fingerprint(passwordHash)
        val row=jdbc.queryForList("SELECT * FROM learner_password_vault WHERE pharmacist_id=?",pharmacistId).singleOrNull()
            ?:return LearnerPasswordCopy(null,"Недоступен: нет зашифрованной копии")
        if(row["credential_fingerprint"]!=fingerprint) return LearnerPasswordCopy(null,"Недоступен: копия не соответствует текущему паролю")
        if(row["state"]!="captured") return LearnerPasswordCopy(null,"Недоступен: зашифрованная копия не создана")
        val bytes=crypto.open("learner-password",pharmacistId,fingerprint,SealedCredential(row["key_id"].toString(),row["nonce"] as ByteArray,row["ciphertext"] as ByteArray))
            ?:return LearnerPasswordCopy(null,"Недоступен: ключ или целостность копии не подтверждены",true)
        return try {LearnerPasswordCopy(bytes.toString(Charsets.UTF_8),"Доступен")} finally {bytes.fill(0)}
    }
}
