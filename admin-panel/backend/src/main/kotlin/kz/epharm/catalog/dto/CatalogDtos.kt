package kz.epharm.catalog.dto

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import kz.epharm.catalog.entity.ProductEntity

data class ProductDto(
    val id: String,
    val name: String,
    val brand: String,
    val vendor: String,
    val mnn: String,
    val price: Int,
) {
    companion object {
        fun of(e: ProductEntity): ProductDto = ProductDto(
            id = e.id,
            name = e.name,
            brand = e.brand,
            vendor = e.vendor,
            mnn = e.mnn,
            price = e.price,
        )
    }
}

/**
 * Список брендов с количеством продуктов — для фильтров и палитры в UI.
 * Vendor — короткое название («Jadran»), brand — полное юр.лицо («Jadran-Galenski»).
 */
data class BrandDto(
    val brand: String,
    val vendor: String,
    val productCount: Int,
)

/**
 * MNN-группа (Международное непатентованное наименование). Используется в
 * RuleBuilder для триггера kind='mnn'.
 */
data class MnnGroupDto(
    val mnn: String,
    val productCount: Int,
)

/**
 * Create-запрос товара. id задаёт пользователь (а не сервер), потому что на него
 * ссылаются правила (rule.recommend / trigger.value) и seed-данные — он должен быть
 * стабильным и человекочитаемым (`p_aql_norm_s`). Формат залочен Pattern'ом, чтобы
 * id оставался URL-safe и однородным с seed-форматом.
 */
data class CreateProductRequest(
    @field:NotBlank
    @field:Pattern(regexp = "[a-z0-9_]{2,64}", message = "id: только [a-z0-9_], 2-64 символа")
    val id: String,
    @field:NotBlank
    @field:Size(max = 255)
    val name: String,
    @field:NotBlank
    @field:Size(max = 255)
    val brand: String,
    @field:NotBlank
    @field:Size(max = 255)
    val vendor: String,
    @field:NotBlank
    @field:Size(max = 255)
    val mnn: String,
    @field:Min(0)
    val price: Int,
)

/**
 * Partial-update (PATCH). id неизменяем (на него ссылаются правила) — меняем
 * только описательные поля. Каждое поле опционально: применяется только если != null.
 */
data class UpdateProductRequest(
    @field:Size(max = 255)
    val name: String? = null,
    @field:Size(max = 255)
    val brand: String? = null,
    @field:Size(max = 255)
    val vendor: String? = null,
    @field:Size(max = 255)
    val mnn: String? = null,
    @field:Min(0)
    val price: Int? = null,
)
