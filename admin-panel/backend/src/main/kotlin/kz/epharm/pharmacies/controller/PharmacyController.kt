package kz.epharm.pharmacies.controller

import jakarta.validation.Valid
import kz.epharm.pharmacies.dto.ChainDto
import kz.epharm.pharmacies.dto.CreateChainRequest
import kz.epharm.pharmacies.dto.CreatePharmacyRequest
import kz.epharm.pharmacies.dto.PharmacyDto
import kz.epharm.pharmacies.dto.UpdateChainRequest
import kz.epharm.pharmacies.dto.UpdatePharmacyRequest
import kz.epharm.pharmacies.entity.PharmacyGroup
import kz.epharm.pharmacies.service.PharmacyService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/admin/pharmacies")
class PharmacyController(private val pharmacyService: PharmacyService) {

    @GetMapping("/chains")
    fun chains(): List<ChainDto> = pharmacyService.listChains()

    @PostMapping("/chains")
    @ResponseStatus(HttpStatus.CREATED)
    fun createChain(@Valid @RequestBody req: CreateChainRequest): ChainDto =
        pharmacyService.createChain(req)

    @PatchMapping("/chains/{id}")
    fun updateChain(@PathVariable id: String, @Valid @RequestBody req: UpdateChainRequest): ChainDto =
        pharmacyService.updateChain(id, req)

    @DeleteMapping("/chains/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteChain(@PathVariable id: String) = pharmacyService.deleteChain(id)

    @GetMapping
    fun list(
        @RequestParam(required = false) group: PharmacyGroup?,
        @RequestParam(required = false) chainId: String?,
    ): List<PharmacyDto> = pharmacyService.list(group = group, chainId = chainId)

    @GetMapping("/{id}")
    fun get(@PathVariable id: String): PharmacyDto = pharmacyService.get(id)

    @PostMapping
    fun create(@Valid @RequestBody req: CreatePharmacyRequest): PharmacyDto =
        pharmacyService.create(req)

    @PatchMapping("/{id}")
    fun update(@PathVariable id: String, @Valid @RequestBody req: UpdatePharmacyRequest): PharmacyDto =
        pharmacyService.update(id, req)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: String) = pharmacyService.delete(id)
}
