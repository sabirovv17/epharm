package kz.epharm.catalog.controller

import jakarta.validation.Valid
import kz.epharm.catalog.dto.BrandDto
import kz.epharm.catalog.dto.CreateProductRequest
import kz.epharm.catalog.dto.MnnGroupDto
import kz.epharm.catalog.dto.ProductDto
import kz.epharm.catalog.dto.UpdateProductRequest
import kz.epharm.catalog.service.CatalogService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

// Каталог master-data. Read-эндпоинты нужны фронту:
//   GET /products    — список для productById lookup в RulesPage
//   GET /brands      — фильтр в RuleBuilder
//   GET /mnn-groups  — выбор МНН-триггера в RuleBuilder
// CRUD (POST/PATCH/DELETE) — Блок 2: управление справочником товаров из админки.
// Role-based access (@PreAuthorize) — отдельной задачей; сейчас любой залогиненный admin.
@RestController
@RequestMapping("/api/admin/catalog")
class CatalogController(
    private val catalogService: CatalogService,
) {

    @GetMapping("/products")
    fun listProducts(): List<ProductDto> = catalogService.listProducts()

    @GetMapping("/products/{id}")
    fun getProduct(@PathVariable id: String): ProductDto = catalogService.getProduct(id)

    @PostMapping("/products")
    @ResponseStatus(HttpStatus.CREATED)
    fun createProduct(@Valid @RequestBody req: CreateProductRequest): ProductDto =
        catalogService.createProduct(req)

    @PatchMapping("/products/{id}")
    fun updateProduct(
        @PathVariable id: String,
        @Valid @RequestBody req: UpdateProductRequest,
    ): ProductDto = catalogService.updateProduct(id, req)

    @DeleteMapping("/products/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteProduct(@PathVariable id: String) = catalogService.deleteProduct(id)

    @GetMapping("/brands")
    fun listBrands(): List<BrandDto> = catalogService.listBrands()

    @GetMapping("/mnn-groups")
    fun listMnnGroups(): List<MnnGroupDto> = catalogService.listMnnGroups()
}
