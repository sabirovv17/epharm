package kz.epharm.eshop

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kz.epharm.medusa.dto.MedusaCategory
import kz.epharm.medusa.dto.MedusaProduct
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ResultSetExtractor
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.PreparedStatement
import java.math.BigDecimal
import java.text.Normalizer
import java.time.Instant
import java.util.Locale
import java.util.UUID

@Repository
class EshopCatalogSnapshotRepository(
    private val jdbc: JdbcTemplate,
    private val mapper: EshopCatalogMapper,
    private val json: ObjectMapper,
) {
    data class ProductRow(
        val sku: String,
        val productId: String,
        val variantId: String?,
        val raw: JsonNode,
        val priceAmount: BigDecimal?,
        val published: Boolean,
        val aliasIds: List<String>,
    )

    data class CatalogItem(val product: MedusaProduct, val published: Boolean)
    data class Page(val items: List<CatalogItem>, val total: Int)
    data class SyncState(
        val ready: Boolean,
        val productCount: Int,
        val publishedCount: Int,
        val completedAt: Instant?,
        val catalogGeneratedAt: Instant?,
        val availabilityFinishedAt: Instant?,
        val catalogRunId: String?,
        val availabilityRunId: String?,
        val lastError: String?,
    )

    fun syncState(): SyncState = jdbc.queryForObject(
        """
        SELECT active_generation, product_count, published_count, completed_at,
               catalog_generated_at, availability_finished_at,
               catalog_run_id, availability_run_id, last_error
          FROM eshop_catalog_sync_state WHERE singleton = 1
        """.trimIndent(),
    ) { rs, _ ->
        val total = rs.getInt("product_count")
        val published = rs.getInt("published_count")
        SyncState(
            ready = rs.getObject("active_generation") != null && total > 0 && published > 0,
            productCount = total,
            publishedCount = published,
            completedAt = rs.getTimestamp("completed_at")?.toInstant(),
            catalogGeneratedAt = rs.getTimestamp("catalog_generated_at")?.toInstant(),
            availabilityFinishedAt = rs.getTimestamp("availability_finished_at")?.toInstant(),
            catalogRunId = rs.getString("catalog_run_id"),
            availabilityRunId = rs.getString("availability_run_id"),
            lastError = rs.getString("last_error"),
        )
    } ?: SyncState(false, 0, 0, null, null, null, null, null, null)

    fun hasCompleteSnapshot(): Boolean = syncState().ready

    fun hasPublishedSku(sku: String): Boolean = jdbc.queryForObject(
        """SELECT EXISTS (SELECT 1 FROM eshop_catalog_products
             WHERE generation = (SELECT active_generation FROM eshop_catalog_sync_state WHERE singleton = 1)
               AND sku = ? AND published)""",
        Boolean::class.java, sku,
    ) == true

    @Transactional
    fun beginSync() {
        jdbc.update(
            """DELETE FROM eshop_catalog_products WHERE generation IS DISTINCT FROM
               (SELECT active_generation FROM eshop_catalog_sync_state WHERE singleton = 1)""",
        )
        jdbc.update("UPDATE eshop_catalog_sync_state SET started_at = now(), last_error = NULL WHERE singleton = 1")
    }

    @Transactional
    fun upsertBatch(generation: UUID, offset: Int, products: List<ProductRow>) {
        if (products.isEmpty()) return
        val mapped = products.mapIndexed { index, row ->
            require(row.sku.length in 1..128 && row.sku == row.sku.trim()) { "Invalid source SKU" }
            require(row.priceAmount == null ||
                (row.priceAmount > BigDecimal.ZERO && row.priceAmount <= BigDecimal.valueOf(100_000_000) &&
                    row.priceAmount.scale() <= 2)) { "Invalid price for ${row.sku}" }
            require(row.aliasIds.all { it.length in 1..64 && it == it.trim() }) { "Invalid alias for ${row.sku}" }
            val product = mapper.map(row.sku, row.productId, row.variantId, row.raw, row.priceAmount)
            Triple(row, product, offset + index)
        }
        jdbc.batchUpdate(
            """
            INSERT INTO eshop_catalog_products
                (generation, sku, public_id, published, price_amount, source_payload, product_payload,
                 search_text, category_keys, source_position)
            VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?)
            """.trimIndent(),
            mapped,
            mapped.size,
        ) { ps: PreparedStatement, (row, product, position) ->
            ps.setObject(1, generation)
            ps.setString(2, row.sku)
            ps.setString(3, product.id)
            ps.setBoolean(4, row.published)
            ps.setBigDecimal(5, row.priceAmount)
            ps.setString(6, json.writeValueAsString(row.raw))
            ps.setString(7, json.writeValueAsString(product))
            ps.setString(8, searchDocument(row, product))
            ps.setArray(9, ps.connection.createArrayOf("text", product.categories.map { it.id }.toTypedArray()))
            ps.setInt(10, position)
        }
        val aliases = mapped.flatMap { (row, _, _) -> row.aliasIds.distinct().map { it to row.sku } }
        if (aliases.isNotEmpty()) {
            jdbc.batchUpdate(
                "INSERT INTO eshop_catalog_aliases (generation, alias_id, sku) VALUES (?, ?, ?)",
                aliases,
                aliases.size,
            ) { ps, (alias, sku) ->
                ps.setObject(1, generation)
                ps.setString(2, alias)
                ps.setString(3, sku)
            }
        }
    }

    @Transactional
    fun completeSync(
        generation: UUID,
        expectedCount: Int,
        catalogRunId: String,
        availabilityRunId: String?,
        catalogGeneratedAt: Instant,
        availabilityFinishedAt: Instant?,
    ) {
        val counts = jdbc.queryForMap(
            """SELECT count(*)::int AS total, count(*) FILTER (WHERE published)::int AS published
                 FROM eshop_catalog_products WHERE generation = ?""",
            generation,
        )
        val actual = counts["total"] as Int
        val published = counts["published"] as Int
        check(actual == expectedCount && actual > 0) {
            "Incomplete shop catalogue: expected $expectedCount unique products, got $actual"
        }
        check(published > 0) { "Shop catalogue contains no published products" }
        val conflictingAlias = jdbc.queryForObject(
            """SELECT EXISTS (
                   SELECT 1 FROM eshop_catalog_aliases a
                    WHERE a.generation = ? AND (
                      EXISTS (SELECT 1 FROM eshop_catalog_products p
                               WHERE p.generation = a.generation AND p.public_id = a.alias_id
                                 AND p.sku <> a.sku)
                      OR EXISTS (SELECT 1 FROM eshop_catalog_products p
                                  WHERE p.generation = a.generation AND p.sku = a.alias_id
                                    AND p.sku <> a.sku)
                    )
                 )""",
            Boolean::class.java, generation,
        ) == true
        check(!conflictingAlias) { "Verified shop alias collides with another product ID or SKU" }
        jdbc.update(
            """
            UPDATE eshop_catalog_sync_state
               SET active_generation = ?, catalog_run_id = ?, availability_run_id = ?,
                   product_count = ?, published_count = ?, catalog_generated_at = ?,
                   availability_finished_at = ?, completed_at = now(), last_error = NULL
             WHERE singleton = 1
            """.trimIndent(),
            generation, catalogRunId, availabilityRunId, actual, published,
            java.sql.Timestamp.from(catalogGeneratedAt), availabilityFinishedAt?.let(java.sql.Timestamp::from),
        )
        jdbc.update("DELETE FROM eshop_catalog_products WHERE generation <> ?", generation)
    }

    @Transactional
    fun failSync(generation: UUID, error: String) {
        jdbc.update("DELETE FROM eshop_catalog_products WHERE generation = ?", generation)
        jdbc.update("UPDATE eshop_catalog_sync_state SET last_error = ? WHERE singleton = 1", error.take(2_000))
    }

    fun search(q: String?, category: String?, limit: Int, offset: Int, publicOnly: Boolean, published: Boolean? = null): Page {
        val clauses = mutableListOf(
            "generation = (SELECT active_generation FROM eshop_catalog_sync_state WHERE singleton = 1)",
        )
        val params = mutableListOf<Any>()
        if (publicOnly || published != null) {
            clauses += "published = ?"
            params += if (publicOnly) true else published!!
        }
        normalize(q.orEmpty()).takeIf(String::isNotBlank)?.let {
            clauses += "search_text LIKE ? ESCAPE '!'"
            params += "%${escapeLike(it)}%"
        }
        category?.trim()?.takeIf(String::isNotBlank)?.let {
            clauses += "category_keys @> ARRAY[?]::text[]"
            params += it
        }
        val where = " WHERE ${clauses.joinToString(" AND ")}"
        val total = jdbc.queryForObject(
            "SELECT count(*) FROM eshop_catalog_products$where", Int::class.java, *params.toTypedArray(),
        ) ?: 0
        val items = jdbc.query(
            """SELECT product_payload::text, published FROM eshop_catalog_products$where
                 ORDER BY source_position, sku LIMIT ? OFFSET ?""",
            { rs, _ -> CatalogItem(json.readValue(rs.getString(1), MedusaProduct::class.java), rs.getBoolean(2)) },
            *(params + limit + offset).toTypedArray(),
        )
        return Page(items, total)
    }

    fun findById(id: String, publicOnly: Boolean): CatalogItem? {
        if (id.isBlank()) return null
        val publishedClause = if (publicOnly) "AND p.published" else ""
        val direct = jdbc.query(
            """
            SELECT p.product_payload::text, p.published
              FROM eshop_catalog_products p
             WHERE p.generation = (SELECT active_generation FROM eshop_catalog_sync_state WHERE singleton = 1)
               $publishedClause
               AND (p.public_id = ? OR p.sku = ?)
             LIMIT 1
            """.trimIndent(),
            { rs, _ ->
                val product = json.readValue(rs.getString(1), MedusaProduct::class.java)
                CatalogItem(if (id == product.id) product else product.copy(id = id), rs.getBoolean(2))
            },
            id, id,
        ).firstOrNull()
        if (direct != null) return direct
        val aliasSku = jdbc.query(
            """SELECT sku FROM eshop_catalog_aliases
                 WHERE generation = (SELECT active_generation FROM eshop_catalog_sync_state WHERE singleton = 1)
                   AND alias_id = ?""",
            { rs, _ -> rs.getString(1) }, id,
        ).firstOrNull() ?: return null
        return jdbc.query(
            """SELECT product_payload::text, published FROM eshop_catalog_products
                 WHERE generation = (SELECT active_generation FROM eshop_catalog_sync_state WHERE singleton = 1)
                   AND sku = ? ${if (publicOnly) "AND published" else ""} LIMIT 1""",
            { rs, _ ->
                val product = json.readValue(rs.getString(1), MedusaProduct::class.java)
                CatalogItem(product.copy(id = id), rs.getBoolean(2))
            }, aliasSku,
        ).firstOrNull()
    }

    fun findByIds(ids: Collection<String>, publicOnly: Boolean): List<CatalogItem> =
        ids.distinct().mapNotNull { findById(it, publicOnly) }

    /** Stable site identity for a verified legacy alias; null means no asserted match. */
    fun canonicalId(id: String): String? {
        if (id.isBlank()) return null
        val direct = jdbc.query(
            """SELECT public_id FROM eshop_catalog_products
                 WHERE generation = (SELECT active_generation FROM eshop_catalog_sync_state WHERE singleton = 1)
                   AND (public_id = ? OR sku = ?) LIMIT 1""",
            { rs, _ -> rs.getString(1) }, id, id,
        ).firstOrNull()
        if (direct != null) return direct
        return jdbc.query(
            """SELECT p.public_id FROM eshop_catalog_aliases a
                 JOIN eshop_catalog_products p ON p.generation = a.generation AND p.sku = a.sku
                 WHERE a.generation = (SELECT active_generation FROM eshop_catalog_sync_state WHERE singleton = 1)
                   AND a.alias_id = ? LIMIT 1""",
            { rs, _ -> rs.getString(1) }, id,
        ).firstOrNull()
    }

    /** Resolve verified legacy IDs and site SKUs in batches; unknown IDs remain absent. */
    fun canonicalIds(ids: Collection<String>): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (chunk in ids.filter(String::isNotBlank).distinct().chunked(500)) {
            val marks = chunk.joinToString(",") { "?" }
            jdbc.query(
                """SELECT public_id, sku FROM eshop_catalog_products
                     WHERE generation = (SELECT active_generation FROM eshop_catalog_sync_state WHERE singleton = 1)
                       AND (public_id IN ($marks) OR sku IN ($marks))""",
                ResultSetExtractor<Unit> { rs ->
                    while (rs.next()) {
                        val publicId = rs.getString(1)
                        val sku = rs.getString(2)
                        if (publicId in chunk) result[publicId] = publicId
                        if (sku in chunk) result[sku] = publicId
                    }
                },
                *(chunk + chunk).toTypedArray(),
            )
            jdbc.query(
                """SELECT a.alias_id, p.public_id FROM eshop_catalog_aliases a
                     JOIN eshop_catalog_products p ON p.generation = a.generation AND p.sku = a.sku
                     WHERE a.generation = (SELECT active_generation FROM eshop_catalog_sync_state WHERE singleton = 1)
                       AND a.alias_id IN ($marks)""",
                ResultSetExtractor<Unit> { rs ->
                    while (rs.next()) result[rs.getString(1)] = rs.getString(2)
                },
                *chunk.toTypedArray(),
            )
        }
        return result
    }

    /** Every verified identity of one product for finding legacy campaign records. */
    fun relatedIds(id: String): List<String> {
        val canonical = canonicalId(id) ?: return listOf(id)
        val aliases = jdbc.query(
            """SELECT a.alias_id FROM eshop_catalog_aliases a
                 JOIN eshop_catalog_products p ON p.generation = a.generation AND p.sku = a.sku
                 WHERE a.generation = (SELECT active_generation FROM eshop_catalog_sync_state WHERE singleton = 1)
                   AND p.public_id = ? ORDER BY a.alias_id""",
            { rs, _ -> rs.getString(1) }, canonical,
        )
        return (listOf(id, canonical) + aliases).distinct()
    }

    fun categories(publicOnly: Boolean): List<MedusaCategory> {
        val publishedClause = if (publicOnly) "AND published" else ""
        return jdbc.query(
            """
            SELECT DISTINCT ON (category ->> 'id') category::text
              FROM eshop_catalog_products p
              CROSS JOIN LATERAL jsonb_array_elements(
                  COALESCE(p.product_payload -> 'categories', '[]'::jsonb)) category
             WHERE p.generation = (SELECT active_generation FROM eshop_catalog_sync_state WHERE singleton = 1)
               $publishedClause
             ORDER BY category ->> 'id', category ->> 'name'
            """.trimIndent(),
            { rs, _ -> json.readValue(rs.getString(1), MedusaCategory::class.java) },
        )
    }

    fun isCanonicalId(id: String): Boolean = id.startsWith("prod_Daribar") && id.length <= 64

    private fun searchDocument(row: ProductRow, product: MedusaProduct): String = normalize(
        listOfNotNull(
            row.sku, product.id, product.title, product.description,
            product.metadata?.get("brand_name")?.toString(), product.metadata?.get("mnn")?.toString(),
            product.variants.firstOrNull()?.barcode,
        ).plus(product.categories.map { it.name }).plus(row.aliasIds).joinToString(" "),
    )

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
            .replace('ё', 'е').replace(Regex("\\s+"), " ").trim()

    private fun escapeLike(value: String): String = value
        .replace("!", "!!").replace("%", "!%").replace("_", "!_")
}
