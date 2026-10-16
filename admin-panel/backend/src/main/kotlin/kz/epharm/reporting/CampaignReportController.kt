package kz.epharm.reporting

import java.time.LocalDate
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/admin/reports/campaigns")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','HQ_HEAD')")
class CampaignReportController(private val service: CampaignReportService,private val pdf: CampaignReportPdf) {
    @GetMapping("/{promoId}")
    fun report(@PathVariable promoId: String,
        @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) to: LocalDate,
    ): ResponseEntity<CampaignReport> = ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"no-store")
        .body(service.report(promoId,from,to))

    @GetMapping("/{promoId}/pdf",produces=["application/pdf"])
    fun pdf(@PathVariable promoId: String,
        @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) to: LocalDate,
    ): ResponseEntity<ByteArray> = ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
        .header(HttpHeaders.CACHE_CONTROL,"no-store")
        .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=campaign-report-$from-$to.pdf")
        .body(pdf.render(service.report(promoId,from,to)))
}
