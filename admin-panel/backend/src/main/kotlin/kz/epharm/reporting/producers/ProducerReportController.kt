package kz.epharm.reporting.producers

import java.time.LocalDate
import java.util.UUID
import kz.epharm.auth.access.AdministrativeScope
import kz.epharm.auth.access.UnifiedAdminCatalog
import kz.epharm.auth.security.AdminPrincipal
import kz.epharm.reporting.exports.ReportPeriod
import kz.epharm.reporting.xlsx.ReportWorkbook
import org.slf4j.LoggerFactory
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
class ProducerReportController(private val bindings:ProducerBindingService,private val reports:ProducerReportReader,
    private val workbook:ProducerReportWorkbook,private val access:AdministrativeScope,private val catalog:UnifiedAdminCatalog,
    private val manufacturers:ManufacturerRegistryService) {
    @PostMapping("/api/admin/promo/producers")
    fun createManufacturer(@RequestBody request:CreateManufacturer,@AuthenticationPrincipal actor:AdminPrincipal)=
        ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"no-store").body(manufacturers.create(request,actor.userId))
    @GetMapping("/api/admin/promo/{id}/producer")
    fun binding(@PathVariable id:String)=ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"no-store").body(bindings.view(id))
    @PutMapping("/api/admin/promo/{id}/producer")
    fun bind(@PathVariable id:String,@RequestBody request:BindPromoProducer,@AuthenticationPrincipal actor:AdminPrincipal)=
        ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"no-store").body(bindings.bind(id,request,actor.userId))
    @PostMapping("/api/admin/promo/{id}/producer/retractions")
    fun retract(@PathVariable id:String,@RequestBody request:RetractPromoProducer,@AuthenticationPrincipal actor:AdminPrincipal)=
        ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"no-store").body(bindings.retract(id,request,actor.userId))
    @GetMapping("/api/admin/reports/producers")
    fun index()=ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"no-store").body(reports.index())
    @GetMapping("/api/admin/reports/producers/{producerId}/xlsx",produces=["application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"])
    fun xlsx(@PathVariable producerId:UUID,@RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) from:LocalDate,
        @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) to:LocalDate,@AuthenticationPrincipal actor:AdminPrincipal):ResponseEntity<ByteArray> {
        access.requireExplicitPermission(catalog.campaignReportExport);access.requireAllPermission(catalog.campaignReportExport)
        val report=reports.read(producerId,ReportPeriod(from,to));val bytes=workbook.render(report)
        log.info("report_export actor={} report=producer producerId={} from={} to={} campaigns={} bytes={} scope=ALL",
            actor.userId,producerId,from,to,report.campaigns.size,bytes.size)
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(ReportWorkbook.MIME)).header(HttpHeaders.CACHE_CONTROL,"no-store")
            .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"producer-$producerId-$from-$to.xlsx\"")
            .header("X-Content-Type-Options","nosniff").body(bytes)
    }
    private val log=LoggerFactory.getLogger(javaClass)
}
