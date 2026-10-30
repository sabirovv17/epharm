package kz.epharm.merchtasks.service

import com.fasterxml.jackson.databind.ObjectMapper
import kz.epharm.merchtasks.dto.MerchRecommendationContextEnvelope
import kz.epharm.merchtasks.dto.MerchRecommendationContextRequest
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.stereotype.Component
import java.sql.Savepoint
import javax.sql.DataSource

/** Optional exact-key feed, sharing the caller's connection without poisoning its write transaction. */
@Component
class NativeRecommendationContextReader(private val dataSource: DataSource, private val json: ObjectMapper) {
    fun context(request: MerchRecommendationContextRequest): MerchRecommendationContextEnvelope {
        val unavailable = MerchRecommendationContextEnvelope(available = false)
        val normalized = runCatching {
            require(request.cart.size <= 200 && request.requestedProducts.size <= 50)
            fun ref(value: String?, max: Int): String? = value?.trim()?.takeIf { it.isNotEmpty() }?.also {
                require(it.length <= max && it.matches(Regex("[0-9A-Za-z._:+@/-]+")))
            }
            request.copy(cart = request.cart.map {
                require(it.qty.isFinite() && it.qty > 0 && it.qty <= 10000)
                it.copy(sku=ref(it.sku,64),barcode=ref(it.barcode,32),name=it.name?.trim()?.take(255))
            }, requestedProducts=request.requestedProducts.map {
                it.copy(productId=ref(it.productId,64),barcode=ref(it.barcode,32),ipartId=ref(it.ipartId,64))
            })
        }.getOrElse { return unavailable }
        val connection = DataSourceUtils.getConnection(dataSource)
        var savepoint: Savepoint? = null
        try {
            if (!connection.autoCommit) savepoint = connection.setSavepoint()
            return connection.prepareStatement(SQL).use { statement ->
                // Per-statement only: do not change a pooled connection's session/transaction timeout.
                statement.queryTimeout = 1
                statement.setString(1, request.pharmacyId.trim())
                statement.setString(2, json.writeValueAsString(normalized.cart))
                statement.setString(3, json.writeValueAsString(normalized.requestedProducts))
                statement.executeQuery().use { result ->
                    check(result.next())
                    json.readValue(result.getString(1), MerchRecommendationContextEnvelope::class.java)
                }
            }
        } catch (error: Exception) {
            // PostgreSQL marks the transaction aborted on a failed SELECT/cancel. Roll it back
            // locally so later recommendation-event writes still commit on this same connection.
            if (savepoint != null) connection.rollback(savepoint)
            throw error // Existing client bulkhead records/rate-limits this failure and returns its fail-open envelope.
        } finally {
            try { if (savepoint != null) connection.releaseSavepoint(savepoint) }
            finally { DataSourceUtils.releaseConnection(connection, dataSource) }
        }
    }

    companion object {
        private val SQL = """
            WITH targets AS MATERIALIZED (
                SELECT id AS branch_id,organization_id FROM merch_branches
                WHERE epharm_pharmacy_id=? AND pharmacy_binding_verified AND active
            ), target AS MATERIALIZED (
                SELECT * FROM targets WHERE (SELECT count(*) FROM targets)=1
            ), cart AS MATERIALIZED (
                SELECT * FROM jsonb_to_recordset(?::jsonb) AS i(sku text,barcode text,name text,qty numeric)
            ), requested AS MATERIALIZED (
                SELECT i."productId" AS product_id,i.barcode,i."ipartId" AS ipart_id
                FROM jsonb_to_recordset(?::jsonb) AS i("productId" text,barcode text,"ipartId" text)
            ), matching AS MATERIALIZED (
                SELECT s.* FROM merch_recommendation_signals s JOIN target t
                  ON t.organization_id=s.organization_id AND (s.branch_id IS NULL OR s.branch_id=t.branch_id)
                WHERE s.active AND (s.valid_from IS NULL OR s.valid_from<=now()) AND (s.valid_until IS NULL OR s.valid_until>now())
                  AND EXISTS(SELECT 1 FROM cart c WHERE
                      (s.trigger_barcode IS NOT NULL AND s.trigger_barcode=c.barcode) OR
                      (s.trigger_ipart_id IS NOT NULL AND s.trigger_ipart_id=c.sku))
            ), selected AS MATERIALIZED (
                SELECT d.* FROM (
                    SELECT DISTINCT ON (s.kind,coalesce(s.trigger_barcode,'ipart:'||s.trigger_ipart_id),
                        coalesce(s.recommend_product_id,s.recommend_barcode,'ipart:'||s.recommend_ipart_id)) s.*
                    FROM matching s ORDER BY s.kind,coalesce(s.trigger_barcode,'ipart:'||s.trigger_ipart_id),
                        coalesce(s.recommend_product_id,s.recommend_barcode,'ipart:'||s.recommend_ipart_id),
                        (s.branch_id IS NOT NULL) DESC,s.priority DESC,s.id
                ) d ORDER BY d.priority DESC,d.id LIMIT 100
            ), keys AS MATERIALIZED (
                SELECT product_id,barcode,ipart_id FROM requested UNION
                SELECT recommend_product_id,recommend_barcode,recommend_ipart_id FROM selected
            ), balances AS MATERIALIZED (
                SELECT DISTINCT ON(b.external_key) b.* FROM merch_product_balances b JOIN target t
                  ON t.branch_id=b.branch_id AND t.organization_id=b.organization_id
                WHERE EXISTS(SELECT 1 FROM keys k WHERE (k.product_id IS NOT NULL AND k.product_id=b.product_id)
                    OR (k.barcode IS NOT NULL AND k.barcode=b.barcode) OR (k.ipart_id IS NOT NULL AND k.ipart_id=b.ipart_id))
                ORDER BY b.external_key,b.observed_at DESC,b.id DESC LIMIT 201
            ) SELECT jsonb_build_object('available',true,'pharmacyMapped',EXISTS(SELECT 1 FROM target),'generatedAt',now(),
                'recommendations',coalesce((SELECT jsonb_agg(jsonb_build_object(
                    'id',s.id::text,'kind',s.kind,'triggerBarcode',s.trigger_barcode,'triggerIpartId',s.trigger_ipart_id,
                    'recommendProductId',s.recommend_product_id,'recommendBarcode',s.recommend_barcode,'recommendIpartId',s.recommend_ipart_id,
                    'script',s.script,'advantages',s.advantages,'partnerLabel',s.partner_label,'bonus',s.bonus,'priority',s.priority
                ) ORDER BY s.priority DESC,s.id) FROM selected s),'[]'::jsonb),
                'balances',coalesce((SELECT jsonb_agg(jsonb_build_object(
                    'productId',b.product_id,'barcode',b.barcode,'ipartId',b.ipart_id,
                    'pharmacyAvailableQty',b.pharmacy_available_qty,'networkAvailableQty',b.network_available_qty,
                    'warehouseAvailableQty',b.warehouse_available_qty,'reservedQty',b.reserved_qty,
                    'observedAt',b.observed_at,'source',b.source,
                    'status',CASE WHEN b.observed_at<now()-interval '1800 seconds' THEN 'stale' ELSE b.quality END
                ) ORDER BY b.external_key) FROM balances b),'[]'::jsonb))::text
        """.trimIndent()
    }
}
