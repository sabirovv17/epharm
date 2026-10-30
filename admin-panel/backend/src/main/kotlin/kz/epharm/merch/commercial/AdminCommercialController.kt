package kz.epharm.merch.commercial

import kz.epharm.auth.security.AdminPrincipal
import kz.epharm.merch.placements.dto.PlacementActor
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.time.LocalDate
import java.util.UUID

@RestController
@RequestMapping("/api/admin/merch/commercial")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','HQ_HEAD','CATEGORY_LEAD','REGIONAL_MANAGER')")
class AdminCommercialController(private val service:CommercialService,private val csv:CommercialCsv,private val kit:CommercialMediaKit) {
    @GetMapping("/plans") fun list(@AuthenticationPrincipal principal:AdminPrincipal,@RequestParam(required=false) producerId:UUID?,
        @RequestParam(defaultValue="50") limit:Int,@RequestParam(defaultValue="0") offset:Int)=commercialPrivate(service.list(actor(principal),producerId,limit,offset))
    @GetMapping("/plans/{id}") fun view(@PathVariable id:UUID,@AuthenticationPrincipal principal:AdminPrincipal)=commercialPrivate(service.view(id,actor(principal)))
    @PutMapping("/plans/{id}") fun save(@PathVariable id:UUID,@RequestParam producerId:UUID,@RequestBody body:SaveCommercialPlan,@AuthenticationPrincipal principal:AdminPrincipal)=commercialPrivate(service.save(id,producerId,body,actor(principal)))
    @PostMapping("/plans/{id}/submit") fun submit(@PathVariable id:UUID,@RequestBody body:SubmitCommercialPlan,@AuthenticationPrincipal principal:AdminPrincipal)=commercialPrivate(service.submit(id,body,actor(principal)))
    @PostMapping("/plans/{id}/copy") fun copy(@PathVariable id:UUID,@RequestBody body:CopyCommercialPlan,@AuthenticationPrincipal principal:AdminPrincipal)=commercialPrivate(service.copy(id,body,actor(principal)))
    @PostMapping("/plans/import-preview") fun preview(@RequestParam producerId:UUID,@RequestBody body:CommercialCsvText,@AuthenticationPrincipal principal:AdminPrincipal)=commercialPrivate(csv.preview(body.csv,producerId,actor(principal)))
    @GetMapping("/pricing") fun pricing()=commercialPrivate(service.pricingIndex())
    @GetMapping("/producers/{id}/settings") fun settings(@PathVariable id:UUID,@AuthenticationPrincipal principal:AdminPrincipal)=commercialPrivate(service.categorySettings(id,actor(principal)))
    @PutMapping("/producers/{id}/category") fun category(@PathVariable id:UUID,@RequestBody body:SetCommercialCategory,@AuthenticationPrincipal principal:AdminPrincipal)=commercialPrivate(service.setCategory(id,body,actor(principal)))
    @GetMapping("/plans/{id}/media-kit.xlsx",produces=["application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"])
    fun mediaKit(@PathVariable id:UUID,@RequestParam(required=false) from:LocalDate?,@RequestParam(required=false) to:LocalDate?,@AuthenticationPrincipal principal:AdminPrincipal)=commercialAttachment(kit.export(id,actor(principal),from,to))
    private fun actor(principal:AdminPrincipal)=PlacementActor.Internal(principal.userId)
}
