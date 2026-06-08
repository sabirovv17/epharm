package kz.epharm.pharmacists.controller

import jakarta.validation.Valid
import kz.epharm.pharmacists.dto.CreatePharmacistRequest
import kz.epharm.pharmacists.dto.PharmacistDto
import kz.epharm.pharmacists.dto.UpdatePharmacistRequest
import kz.epharm.pharmacists.entity.PharmacistStatus
import kz.epharm.pharmacists.service.PharmacistService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/admin/pharmacists")
class PharmacistController(private val pharmacistService: PharmacistService) {

    @GetMapping
    fun list(
        @RequestParam(required = false) status: PharmacistStatus?,
        @RequestParam(required = false) pharmacyId: String?,
    ): List<PharmacistDto> = pharmacistService.list(status = status, pharmacyId = pharmacyId)

    @GetMapping("/{id}")
    fun get(@PathVariable id: String): PharmacistDto = pharmacistService.get(id)

    @PostMapping
    fun create(@Valid @RequestBody req: CreatePharmacistRequest): PharmacistDto =
        pharmacistService.create(req)

    @PatchMapping("/{id}")
    fun update(@PathVariable id: String, @Valid @RequestBody req: UpdatePharmacistRequest): PharmacistDto =
        pharmacistService.update(id, req)

    @PostMapping("/{id}/block")
    fun block(@PathVariable id: String): PharmacistDto = pharmacistService.block(id)

    @PostMapping("/{id}/unblock")
    fun unblock(@PathVariable id: String): PharmacistDto = pharmacistService.unblock(id)
}
