package kz.epharm.reporting.merch

import com.fasterxml.jackson.databind.ObjectMapper
import java.time.LocalDate
import java.util.UUID
import kz.epharm.auth.access.AdministrativeScope
import kz.epharm.auth.security.AdminPrincipal
import kz.epharm.merch.placements.dto.PlacementStatus
import kz.epharm.reporting.xlsx.ReportWorkbook
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/admin/merch/exports")
class MerchExportController(private val reports:MerchExportReader,private val access:AdministrativeScope,private val json:ObjectMapper) {
    @GetMapping("/inventory.xlsx",produces=["application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"])
    fun inventory(@RequestParam branchId:UUID,@RequestParam startMonth:LocalDate,@RequestParam monthCount:Int,
        @AuthenticationPrincipal actor:AdminPrincipal)=attachment(reports.inventory(branchId,startMonth,monthCount),actor)
    @GetMapping("/placements.xlsx",produces=["application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"])
    fun placements(@RequestParam(required=false) producerId:UUID?,@RequestParam(required=false) branchId:UUID?,
        @RequestParam(required=false) status:PlacementStatus?,@RequestParam(required=false) from:LocalDate?,@RequestParam(required=false) to:LocalDate?,
        @AuthenticationPrincipal actor:AdminPrincipal)=attachment(reports.placements(producerId,branchId,status,from,to),actor)
    @GetMapping("/tasks.xlsx",produces=["application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"])
    fun tasks(@RequestParam(required=false) branchId:UUID?,@RequestParam(required=false) status:String?,
        @RequestParam(required=false) from:LocalDate?,@RequestParam(required=false) to:LocalDate?,
        @AuthenticationPrincipal actor:AdminPrincipal)=attachment(reports.tasks(branchId,status,from,to),actor)
    private fun attachment(report:MerchExport,actor:AdminPrincipal):ResponseEntity<ByteArray> {
        val bytes=ReportWorkbook.render(report.sheets)
        log.info("merch_export actor={} report={} rows={} bytes={} branches={} filters={}",actor.userId,report.report,report.dataRows,bytes.size,
            access.currentMerchBranchIds()?.sorted()?:"ALL",json.writeValueAsString(report.filters))
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(ReportWorkbook.MIME)).header(HttpHeaders.CACHE_CONTROL,"no-store")
            .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"merch-${report.report}.xlsx\"")
            .header("X-Content-Type-Options","nosniff").body(bytes)
    }
    private val log=LoggerFactory.getLogger(javaClass)
}
