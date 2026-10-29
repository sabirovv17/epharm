package kz.epharm.reporting.training

import kz.epharm.auth.access.AdministrativeScope
import kz.epharm.auth.access.UnifiedAdminCatalog
import kz.epharm.auth.security.AdminPrincipal
import kz.epharm.lms.entity.CourseStatus
import kz.epharm.reporting.xlsx.ReportWorkbook
import kz.epharm.training.domain.TrainingProgramStatus
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/admin/training/exports")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','HQ_HEAD','TRAINING_MANAGER','REGIONAL_MANAGER','TRAINER')")
class TrainingAuthoringExportController(private val access:AdministrativeScope,private val reader:TrainingAuthoringExportReader) {
    @GetMapping("/courses.xlsx",produces=["application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"])
    fun courses(@RequestParam(required=false) status:CourseStatus?,@AuthenticationPrincipal actor:AdminPrincipal):ResponseEntity<ByteArray> {
        requireExport("courses")
        return file(reader.courses(status),actor)
    }
    @GetMapping("/programs.xlsx",produces=["application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"])
    fun programs(@RequestParam(required=false) status:TrainingProgramStatus?,@AuthenticationPrincipal actor:AdminPrincipal):ResponseEntity<ByteArray> {
        requireExport("programs")
        return file(reader.programs(status),actor)
    }
    private fun requireExport(section:String) {
        for(action in listOf("export","view")) {
            val key=UnifiedAdminCatalog.key("training",section,action)
            access.requireExplicitPermission(key);access.requireAllPermission(key)
        }
    }
    private fun file(report:TrainingAuthoringExport,actor:AdminPrincipal):ResponseEntity<ByteArray> {
        val bytes=ReportWorkbook.render(report.sheets)
        log.info("report_export actor={} report=training_{} status={} rows={} bytes={} scope=ALL",actor.userId,report.kind,report.statusFilter,report.records,bytes.size)
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(ReportWorkbook.MIME)).header(HttpHeaders.CACHE_CONTROL,"no-store")
            .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"training-${report.kind}-${report.statusFilter?:"all"}.xlsx\"")
            .header("X-Content-Type-Options","nosniff").body(bytes)
    }
    private val log=LoggerFactory.getLogger(javaClass)
}
