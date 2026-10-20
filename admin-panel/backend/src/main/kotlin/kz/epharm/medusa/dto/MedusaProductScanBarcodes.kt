package kz.epharm.medusa.dto

import kz.epharm.shared.validation.BarcodeNormalizer

/** Explicit product codes only; unlike variants may be different forms, strengths or packs. */
fun MedusaProduct.scanBarcodes(): List<String> {
    val variantCodes = if (variants.size <= 1 || (
        variants.all { !it.sku.isNullOrBlank() } &&
            variants.map { it.sku?.trim() }.distinct().size == 1 &&
            variants.map { it.title?.trim().orEmpty() }.distinct().size == 1
        )) {
        variants.flatMap { listOf(it.barcode) + it.barcodes }
    } else emptyList()
    val declared = when (val value = metadata?.get("barcodes")) {
        is Iterable<*> -> value.mapNotNull { it as? String }
        is String -> listOf(value)
        else -> emptyList()
    }
    return BarcodeNormalizer.all(declared + variantCodes + listOf(metadata?.get("barcode") as? String))
}
