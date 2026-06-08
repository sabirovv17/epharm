package kz.epharm.catalog.service

import kz.epharm.catalog.dto.BrandDto
import kz.epharm.catalog.dto.CreateProductRequest
import kz.epharm.catalog.dto.MnnGroupDto
import kz.epharm.catalog.dto.ProductDto
import kz.epharm.catalog.dto.UpdateProductRequest
import kz.epharm.catalog.entity.ProductEntity
import kz.epharm.catalog.repository.ProductRepository
import kz.epharm.rules.repository.RuleRepository
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class CatalogService(
    private val productRepository: ProductRepository,
    private val ruleRepository: RuleRepository,
) {

    fun listProducts(): List<ProductDto> =
        productRepository.findAllByOrderByNameAsc().map(ProductDto::of)

    fun listBrands(): List<BrandDto> =
        productRepository.findAll()
            .groupBy { it.brand to it.vendor }
            .map { (key, products) ->
                BrandDto(brand = key.first, vendor = key.second, productCount = products.size)
            }
            .sortedBy { it.brand }

    fun listMnnGroups(): List<MnnGroupDto> =
        productRepository.findAll()
            .groupBy(ProductEntity::mnn)
            .map { (mnn, products) -> MnnGroupDto(mnn = mnn, productCount = products.size) }
            .sortedBy { it.mnn }

    fun getProduct(id: String): ProductDto = ProductDto.of(loadOrThrow(id))

    @Transactional
    fun createProduct(req: CreateProductRequest): ProductDto {
        if (productRepository.existsById(req.id)) {
            throw AppException(
                ErrorCode.CONFLICT,
                "Product ${req.id} already exists",
                HttpStatus.CONFLICT,
            )
        }
        val entity = ProductEntity(
            id = req.id,
            name = req.name,
            brand = req.brand,
            vendor = req.vendor,
            mnn = req.mnn,
            price = req.price,
        )
        return ProductDto.of(productRepository.save(entity))
    }

    @Transactional
    fun updateProduct(id: String, req: UpdateProductRequest): ProductDto {
        val entity = loadOrThrow(id)
        req.name?.let { entity.name = it }
        req.brand?.let { entity.brand = it }
        req.vendor?.let { entity.vendor = it }
        req.mnn?.let { entity.mnn = it }
        req.price?.let { entity.price = it }
        return ProductDto.of(productRepository.save(entity))
    }

    /**
     * Удаление с guard'ом по ссылочной целостности: правила хранят productId в
     * `recommend` и в `trigger.value` (jsonb, FK на уровне БД нет). Удалить товар,
     * на который ссылается правило, = осиротить POSM-матчер (он в рантайме делает
     * lookup по id). Поэтому при наличии ссылок отдаём 409 CONFLICT со списком
     * правил — пусть пользователь сначала разрулит их.
     */
    @Transactional
    fun deleteProduct(id: String) {
        val entity = loadOrThrow(id)
        val referencing = rulesReferencing(id)
        if (referencing.isNotEmpty()) {
            throw AppException(
                ErrorCode.CONFLICT,
                "Product $id is referenced by rules: ${referencing.joinToString()}. " +
                    "Update or archive these rules before deleting the product.",
                HttpStatus.CONFLICT,
            )
        }
        productRepository.delete(entity)
    }

    private fun loadOrThrow(id: String): ProductEntity =
        productRepository.findById(id).orElseThrow {
            AppException(
                ErrorCode.NOT_FOUND,
                "Product $id not found",
                HttpStatus.NOT_FOUND,
            )
        }

    /** id'ы правил, которые ссылаются на товар через recommend или trigger.value. */
    private fun rulesReferencing(productId: String): List<String> =
        ruleRepository.findAll().filter { rule ->
            rule.recommend == productId || triggerReferences(rule.trigger.kind, rule.trigger.value, productId)
        }.map { it.id }

    private fun triggerReferences(kind: String, value: Any, productId: String): Boolean = when (kind) {
        "product" -> value == productId
        "product_any" -> (value as? List<*>)?.contains(productId) == true
        else -> false // mnn: value — название МНН, не productId
    }
}
