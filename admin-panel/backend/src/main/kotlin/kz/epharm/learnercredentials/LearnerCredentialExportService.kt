package kz.epharm.learnercredentials

import org.springframework.stereotype.Service
import java.util.UUID

/** The bounded attempt commits before the domain transaction opens a connection or takes locks. */
@Service
class LearnerCredentialExportService(private val attempts:CredentialExportAttempts,private val transactions:CredentialExportTransactions) {
    fun prepare(request:PrepareCredentialExport,actor:UUID):PreparedCredentialExport {
        attempts.consume(actor,"prepare");return transactions.prepare(request,actor)
    }
    fun download(id:UUID,request:DownloadCredentialExport,actor:UUID):ByteArray {
        attempts.consume(actor,"download");return transactions.download(id,request,actor)
    }
    fun revoke(id:UUID,actor:UUID)=transactions.revoke(id,actor)
    fun cleanup()=transactions.cleanup()
}
