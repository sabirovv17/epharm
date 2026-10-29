package kz.epharm.reporting.training

import com.fasterxml.jackson.databind.ObjectMapper
import java.time.LocalDate
import java.util.UUID
import kz.epharm.auth.access.AdministrativeScope
import kz.epharm.auth.access.UnifiedAdminCatalog
import kz.epharm.auth.security.AdminPrincipal
import kz.epharm.reporting.xlsx.ReportWorkbook
import kz.epharm.training.domain.TrainingAssignmentStatus
import kz.epharm.training.domain.TrainingFormat
import org.slf4j.LoggerFactory
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/admin/training/exports")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','HQ_HEAD','TRAINING_MANAGER','REGIONAL_MANAGER','TRAINER')")
class TrainingLearningExportController(private val access:AdministrativeScope,private val reader:TrainingLearningExportReader,private val json:ObjectMapper) {
    @GetMapping("/assignments.xlsx",produces=["application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"])
    fun assignments(@RequestParam(required=false) format:TrainingFormat?,@RequestParam(required=false) status:TrainingAssignmentStatus?,
        @RequestParam(required=false) programId:UUID?,@AuthenticationPrincipal actor:AdminPrincipal):ResponseEntity<ByteArray> {
        requireExport("assignments")
        return file(reader.assignments(actor,TrainingLearningFilter(format,status,programId)),actor)
    }
    @GetMapping("/results.xlsx",produces=["application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"])
    fun results(@RequestParam(required=false) format:TrainingFormat?,@RequestParam(required=false) status:TrainingAssignmentStatus?,
        @RequestParam(required=false) programId:UUID?,@RequestParam(required=false) sourceType:String?,
        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) from:LocalDate?,
        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) to:LocalDate?,
        @AuthenticationPrincipal actor:AdminPrincipal):ResponseEntity<ByteArray> {
        requireExport("results")
        return file(reader.results(actor,TrainingLearningFilter(format,status,programId),sourceType,from,to),actor)
    }
    private fun requireExport(section:String) {
        for(action in listOf("export","view")) access.requireExplicitPermission(UnifiedAdminCatalog.key("training",section,action))
    }
    private fun file(report:TrainingLearningExport,actor:AdminPrincipal):ResponseEntity<ByteArray> {
        val bytes=ReportWorkbook.render(report.sheets)
        log.info("report_export actor={} report=training_{} rows={} bytes={} pharmacies={} filters={}",actor.userId,report.kind,report.dataRows,bytes.size,
            access.currentPharmacyIds()?.sorted()?:"ALL",json.writeValueAsString(report.filters))
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(ReportWorkbook.MIME)).header(HttpHeaders.CACHE_CONTROL,"no-store")
            .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"training-${report.kind}.xlsx\"")
            .header("X-Content-Type-Options","nosniff").body(bytes)
    }
    private val log=LoggerFactory.getLogger(javaClass)
}
