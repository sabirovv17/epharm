package kz.epharm.learnercredentials

import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** Commits before authority/domain locks, so failed reauthentication cannot roll back its budget. */
@Service
class CredentialExportAttempts(private val jdbc:JdbcTemplate,private val config:LearnerCredentialConfig) {
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    fun consume(actor:UUID,operation:String) {
        check(operation in setOf("prepare","download"))
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,817117))",actor.toString())
        val seconds=if(operation=="prepare")3600 else 900
        val maximum=if(operation=="prepare")config.maxExportsPerHour else 5
        val count=jdbc.queryForObject("SELECT count(*) FROM learner_credential_export_attempts WHERE actor_id=? AND operation=? AND created_at>now()-(?*interval '1 second')",Int::class.java,actor,operation,seconds)!!
        if(count>=maximum) throw AppException(ErrorCode.OTP_TOO_MANY_ATTEMPTS,"Лимит запросов учётных данных исчерпан. Повторите позже.",HttpStatus.TOO_MANY_REQUESTS)
        jdbc.update("INSERT INTO learner_credential_export_attempts(id,actor_id,operation,created_at) VALUES(?,?,?,now())",UUID.randomUUID(),actor,operation)
    }
}
