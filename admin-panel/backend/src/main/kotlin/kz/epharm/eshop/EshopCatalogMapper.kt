package kz.epharm.eshop

import com.fasterxml.jackson.databind.JsonNode
import kz.epharm.medusa.dto.MedusaCalculatedPrice
import kz.epharm.medusa.dto.MedusaCategory
import kz.epharm.medusa.dto.MedusaImage
import kz.epharm.medusa.dto.MedusaProduct
import kz.epharm.medusa.dto.MedusaVariant
import kz.epharm.shared.validation.BarcodeNormalizer
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.math.BigDecimal
import java.math.RoundingMode

/** Converts the shop's normalized product into the existing public catalogue shape. */
@Component
class EshopCatalogMapper(
    @Value("\${app.public-base-url:https://epharm.inkar.kz}") publicBaseUrl: String,
) {
    private val publicOrigin = publicBaseUrl.trimEnd('/')

    fun map(sku: String, productId: String, variantId: String?, raw: JsonNode, priceAmount: BigDecimal?): MedusaProduct {
        require(productId.length in 1..64 && raw.text("id") == productId) {
            "Shop product ID does not match its payload for SKU $sku"
        }
        val title = raw.text("name") ?: throw IllegalArgumentException("Shop product $sku has no name")
        val categoryKeys = raw.path("categoryHandles").takeIf(JsonNode::isArray)
            ?.mapNotNull { it.asText().trim().takeIf(String::isNotBlank) }
            ?.distinct().orEmpty()
            .ifEmpty { listOfNotNull(raw.text("categorySlug")) }
        val media = sourceMediaUrl(sku, raw)
        val metadata = linkedMapOf<String, Any?>()
        listOf("mnn", "atc", "manufacturer", "country").forEach { key ->
            raw.text(key)?.let { metadata[if (key == "country") "country_official" else key] = it }
        }
        raw.text("brand")?.let { metadata["brand_name"] = it }
        raw.path("keyFacts").takeIf(JsonNode::isArray)?.let { facts ->
            metadata["key_facts"] = facts.mapNotNull { it.asText().trim().takeIf(String::isNotBlank) }
        }
        raw.path("faq").takeIf(JsonNode::isArray)?.let { faq ->
            metadata["faq"] = faq.map { entry ->
                mapOf("q" to entry.path("q").asText(""), "a" to entry.path("a").asText(""))
            }
        }
        val barcode = BarcodeNormalizer.first(raw.text("barcode"))
            ?: raw.path("variants").takeIf(JsonNode::isArray)
                ?.firstNotNullOfOrNull { BarcodeNormalizer.first(it.text("barcode")) }
        return MedusaProduct(
            id = productId,
            title = title,
            description = raw.text("description"),
            handle = raw.text("slug"),
            thumbnail = media,
            images = media?.let { listOf(MedusaImage(url = it)) }.orEmpty(),
            categories = categoryKeys.map { MedusaCategory(id = it, name = CATEGORY_NAMES[it] ?: it, handle = it) },
            variants = listOf(
                MedusaVariant(
                    id = variantId?.trim()?.takeIf(String::isNotBlank) ?: sku,
                    sku = sku,
                    barcode = barcode,
                    calculatedPrice = priceAmount?.let {
                        MedusaCalculatedPrice(it.setScale(0, RoundingMode.HALF_UP).toDouble(), "KZT")
                    },
                ),
            ),
            metadata = metadata,
        )
    }

    private fun sourceMediaUrl(sku: String, raw: JsonNode): String? {
        val source = raw.text("image") ?: raw.path("images").takeIf(JsonNode::isArray)
            ?.firstNotNullOfOrNull { it.asText().trim().takeIf(String::isNotBlank) }
        if (source == null) return null
        // Never return a shop-relative URL to the mobile client. The fixed ePharm
        // endpoint fetches only the configured shop media origin for this SKU.
        if (!source.startsWith("/api/media/daribar?")) return null
        val encoded = URLEncoder.encode(sku, StandardCharsets.UTF_8)
        return "$publicOrigin/api/media/eshop?sku=$encoded"
    }

    private fun JsonNode.text(key: String): String? =
        path(key).takeIf { it.isTextual }?.asText()?.trim()?.takeIf(String::isNotBlank)

    companion object {
        private val CATEGORY_NAMES = mapOf(
            "bady" to "БАДы",
            "lekarstva-i-bady" to "Лекарства",
            "gigiyena" to "Гигиена",
            "kosmetika" to "Косметика",
            "linzy" to "Линзы",
            "mama-i-malysh" to "Мама и малыш",
            "med-pribory-i-izdeliya" to "Мед. приборы и изделия",
            "sport-i-fitnes" to "Спорт и фитнес",
            "intim" to "Товары для взрослых",
            "drugoe" to "Другие товары",
        )
    }
}
