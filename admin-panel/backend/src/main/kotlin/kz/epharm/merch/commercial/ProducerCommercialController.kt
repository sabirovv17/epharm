package kz.epharm.merch.commercial

import kz.epharm.merch.placements.dto.PlacementActor
import kz.epharm.merch.producer.auth.ProducerPrincipal
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.time.LocalDate
import java.util.UUID

data class CommercialCsvText(val csv:String)

@RestController
@RequestMapping("/api/producer/commercial")
@PreAuthorize("hasRole('PRODUCER')")
class ProducerCommercialController(private val service:CommercialService,private val csv:CommercialCsv,private val kit:CommercialMediaKit) {
    @GetMapping("/workspace") fun workspace(@AuthenticationPrincipal principal:ProducerPrincipal)=commercialPrivate(service.workspace(actor(principal)))
    @GetMapping("/plans") fun list(@AuthenticationPrincipal principal:ProducerPrincipal,@RequestParam(defaultValue="50") limit:Int,@RequestParam(defaultValue="0") offset:Int)=commercialPrivate(service.list(actor(principal),null,limit,offset))
    @GetMapping("/plans/{id}") fun view(@PathVariable id:UUID,@AuthenticationPrincipal principal:ProducerPrincipal)=commercialPrivate(service.view(id,actor(principal)))
    @PutMapping("/plans/{id}") fun save(@PathVariable id:UUID,@RequestBody body:SaveCommercialPlan,@AuthenticationPrincipal principal:ProducerPrincipal)=commercialPrivate(service.save(id,principal.customerId,body,actor(principal)))
    @PostMapping("/plans/{id}/submit") fun submit(@PathVariable id:UUID,@RequestBody body:SubmitCommercialPlan,@AuthenticationPrincipal principal:ProducerPrincipal)=commercialPrivate(service.submit(id,body,actor(principal)))
    @PostMapping("/plans/{id}/copy") fun copy(@PathVariable id:UUID,@RequestBody body:CopyCommercialPlan,@AuthenticationPrincipal principal:ProducerPrincipal)=commercialPrivate(service.copy(id,body,actor(principal)))
    @PostMapping("/plans/import-preview") fun preview(@RequestBody body:CommercialCsvText,@AuthenticationPrincipal principal:ProducerPrincipal)=commercialPrivate(csv.preview(body.csv,principal.customerId,actor(principal)))
    @PutMapping("/settings") fun settings(@RequestBody body:SaveCommercialSettings,@AuthenticationPrincipal principal:ProducerPrincipal)=commercialPrivate(service.saveSettings(body,actor(principal)))
    @PutMapping("/members/{id}") fun member(@PathVariable id:UUID,@RequestBody body:SaveCommercialMember,@AuthenticationPrincipal principal:ProducerPrincipal)=commercialPrivate(service.member(id,body,actor(principal)))
    @GetMapping("/plans/{id}/media-kit.xlsx",produces=["application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"])
    fun mediaKit(@PathVariable id:UUID,@RequestParam(required=false) from:LocalDate?,@RequestParam(required=false) to:LocalDate?,@AuthenticationPrincipal principal:ProducerPrincipal)=commercialAttachment(kit.export(id,actor(principal),from,to))
    private fun actor(principal:ProducerPrincipal)=PlacementActor.Producer(principal.customerId,principal.sessionId)
}
