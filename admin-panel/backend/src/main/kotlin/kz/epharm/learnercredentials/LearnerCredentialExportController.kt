package kz.epharm.learnercredentials

import kz.epharm.auth.security.AdminPrincipal
import kz.epharm.reporting.xlsx.ReportWorkbook
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/admin/learner-credentials/exports")
class LearnerCredentialExportController(private val exports:LearnerCredentialExportService) {
    @PostMapping fun prepare(@RequestBody request:PrepareCredentialExport,@AuthenticationPrincipal actor:AdminPrincipal)=
        ResponseEntity.ok().header("Cache-Control","no-store").body(exports.prepare(request,actor.userId))
    @PostMapping("/{id}/download",produces=["application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"]) fun download(@PathVariable id:UUID,@RequestBody request:DownloadCredentialExport,@AuthenticationPrincipal actor:AdminPrincipal)=
        ResponseEntity.ok().contentType(MediaType.parseMediaType(ReportWorkbook.MIME)).header("Cache-Control","no-store, max-age=0")
            .header("Pragma","no-cache").header("X-Content-Type-Options","nosniff").header("Content-Disposition","attachment; filename=\"learner-credentials.xlsx\"")
            .body(exports.download(id,request,actor.userId))
    @DeleteMapping("/{id}") fun revoke(@PathVariable id:UUID,@AuthenticationPrincipal actor:AdminPrincipal):ResponseEntity<Void> {
        exports.revoke(id,actor.userId);return ResponseEntity.noContent().header("Cache-Control","no-store").build()
    }
}
