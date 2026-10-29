package kz.epharm.learnercredentials

import com.fasterxml.jackson.annotation.JsonAnySetter
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import java.time.Instant
import java.util.UUID

data class CredentialExportAuthority(val accountId:UUID,val authorizationFingerprint:String)
abstract class CredentialExportRequest {
    @JsonAnySetter fun unknown(@Suppress("UNUSED_PARAMETER") name:String,@Suppress("UNUSED_PARAMETER") value:Any?) {
        throw AppException(ErrorCode.VALIDATION_FAILED,"Проверьте поля запроса выгрузки")
    }
}
data class PrepareCredentialExport(val pharmacistIds:List<String>,val purpose:String,val currentPassword:String):CredentialExportRequest() {
    override fun toString()="PrepareCredentialExport(REDACTED)"
}
data class DownloadCredentialExport(val currentPassword:String):CredentialExportRequest() {
    override fun toString()="DownloadCredentialExport(REDACTED)"
}
data class PreparedCredentialExport(val id:UUID,val expiresAt:Instant,val rowCount:Int)
