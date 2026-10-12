package kz.epharm.storefront.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import kz.epharm.medusa.dto.MedusaCalculatedPrice
import kz.epharm.medusa.dto.MedusaCategory
import kz.epharm.medusa.dto.MedusaImage
import kz.epharm.medusa.dto.MedusaProduct
import kz.epharm.medusa.dto.MedusaVariant
import java.net.URI

/**
 * Public response contract of the Inkar storefront (`/api/catalog`).
 *
 * The website owns the authoritative PostgreSQL catalogue and enriches it with
 * current pharmacy prices/stock. ePharm intentionally parses only the fields it
 * displays, so additive website releases stay backwards compatible.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class StorefrontCatalogPage(
    val products: List<StorefrontProduct> = emptyList(),
    val count: Int = 0,
    val catalogTotal: Int? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StorefrontProductWrapper(
    val product: StorefrontProduct? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StorefrontProduct(
    val id: String = "",
    val slug: String = "",
    val name: String = "",
    val brand: String? = null,
    val categorySlug: String? = null,
    val categoryHandles: List<String> = emptyList(),
    val price: Double? = null,
    @JsonProperty("priceTBD") val priceTbd: Boolean = false,
    val image: String? = null,
    val images: List<String> = emptyList(),
    val description: String? = null,
    val prescription: Boolean? = null,
    val manufacturer: String? = null,
    val country: String? = null,
    val mnn: String? = null,
    val atc: String? = null,
    val barcode: String? = null,
    val variants: List<StorefrontVariant> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StorefrontVariant(
    val id: String = "",
    val title: String? = null,
    val sku: String? = null,
    val barcode: String? = null,
    val price: Double? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StorefrontCategoryNode(
    val id: String = "",
    val name: String = "",
    val handle: String = "",
    val children: List<StorefrontCategoryNode> = emptyList(),
)

internal fun StorefrontProduct.toMedusaProduct(storefrontOrigin: String): MedusaProduct {
    val normalizedPrice = price?.takeIf { !priceTbd && it > 0 }
    val mappedVariants = variants.map { variant ->
        MedusaVariant(
            id = variant.id,
            title = variant.title,
            sku = variant.sku,
            barcode = variant.barcode,
            calculatedPrice = (variant.price?.takeIf { it > 0 } ?: normalizedPrice)?.let {
                MedusaCalculatedPrice(calculatedAmount = it, currencyCode = "KZT")
            },
        )
    }.ifEmpty {
        normalizedPrice?.let {
            listOf(
                MedusaVariant(
                    id = "storefront-$id",
                    barcode = barcode,
                    calculatedPrice = MedusaCalculatedPrice(calculatedAmount = it, currencyCode = "KZT"),
                ),
            )
        } ?: emptyList()
    }
    val handles = (listOfNotNull(categorySlug) + categoryHandles)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
    val media = (listOfNotNull(image) + images)
        .mapNotNull { absoluteMediaUrl(storefrontOrigin, it) }
        .distinct()
    val metadata = linkedMapOf<String, Any?>()
    brand.clean()?.let { metadata["brand_name"] = it }
    manufacturer.clean()?.let { metadata["manufacturer"] = it }
    country.clean()?.let { metadata["country"] = it }
    mnn.clean()?.let { metadata["mnn"] = it }
    atc.clean()?.let { metadata["atc"] = it }
    barcode.clean()?.let { metadata["barcode"] = it }
    categorySlug.clean()?.let { metadata["category"] = it }
    prescription?.let { metadata["rx_otc"] = if (it) "rx" else "otc" }

    return MedusaProduct(
        id = id,
        title = name,
        description = description,
        handle = slug,
        thumbnail = media.firstOrNull(),
        images = media.mapIndexed { index, url -> MedusaImage(url = url, rank = index) },
        categories = handles.map { handle ->
            MedusaCategory(id = handle, name = handle, handle = handle)
        },
        variants = mappedVariants,
        metadata = metadata,
    )
}

private fun absoluteMediaUrl(origin: String, raw: String): String? {
    val value = raw.trim()
    if (value.isBlank() || value.startsWith("//")) return null
    return runCatching {
        val uri = URI(value)
        when {
            uri.isAbsolute && uri.scheme in setOf("http", "https") -> uri.toString()
            !uri.isAbsolute && value.startsWith("/") -> URI(origin).resolve(value).toString()
            else -> null
        }
    }.getOrNull()
}

private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotBlank() && it != "—" }
