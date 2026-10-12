package kz.epharm.storefront.client

import kz.epharm.medusa.dto.MedusaCategory
import kz.epharm.medusa.dto.MedusaProduct
import kz.epharm.medusa.dto.MedusaProductListResponse
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import kz.epharm.storefront.dto.StorefrontCatalogPage
import kz.epharm.storefront.dto.StorefrontCategoryNode
import kz.epharm.storefront.dto.StorefrontProductWrapper
import kz.epharm.storefront.dto.toMedusaProduct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Read-only client for the new Inkar website catalogue.
 *
 * This source is preferred over the retired Medusa instance. All calls remain
 * server-to-server; the pharmacist browser still talks only to ePharm. The
 * durable ePharm snapshot is used by [MobileCatalogService] whenever this site
 * is unavailable, so a website outage cannot empty the catalogue.
 */
@Component
class StorefrontCatalogClient(
    @Value("\${app.storefront-catalog.enabled:false}") enabled: Boolean,
    @Value("\${app.storefront-catalog.base-url:}") rawBaseUrl: String,
    @Value("\${app.storefront-catalog.connect-timeout-ms:2000}") connectTimeoutMs: Int,
    @Value("\${app.storefront-catalog.read-timeout-ms:8000}") readTimeoutMs: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val baseUrl = validateBaseUrl(enabled, rawBaseUrl, connectTimeoutMs, readTimeoutMs)
    val active: Boolean = baseUrl != null
    private val productsById = ConcurrentHashMap<String, MedusaProduct>()

    private val rest: RestClient by lazy {
        RestClient.builder()
            .baseUrl(checkNotNull(baseUrl))
            .requestFactory(
                object : SimpleClientHttpRequestFactory() {
                    override fun prepareConnection(connection: HttpURLConnection, httpMethod: String) {
                        super.prepareConnection(connection, httpMethod)
                        connection.instanceFollowRedirects = false
                    }
                }.apply {
                    setConnectTimeout(connectTimeoutMs)
                    setReadTimeout(readTimeoutMs)
                },
            )
            .build()
    }

    fun listProducts(
        q: String? = null,
        category: String? = null,
        limit: Int,
        offset: Int,
    ): MedusaProductListResponse {
        if (!active) return MedusaProductListResponse(limit = limit, offset = offset)
        return try {
            val page = rest.get().uri { uri ->
                uri.path("/api/catalog")
                    .queryParam("limit", limit)
                    .queryParam("offset", offset)
                    .queryParam("includeFacets", false)
                if (!q.isNullOrBlank()) uri.queryParam("q", q.trim())
                if (!category.isNullOrBlank()) uri.queryParam("category", category.trim())
                uri.build()
            }.retrieve().body(StorefrontCatalogPage::class.java) ?: StorefrontCatalogPage()
            val mapped = page.products.map { it.toMedusaProduct(checkNotNull(baseUrl)) }
            if (q.isNullOrBlank() && category.isNullOrBlank() && offset == 0 && mapped.isEmpty()) {
                throw IllegalStateException("Website catalogue returned an empty first page")
            }
            mapped.filter { it.id.isNotBlank() }.forEach { productsById[it.id] = it }
            MedusaProductListResponse(
                products = mapped,
                count = page.count.coerceAtLeast(mapped.size),
                offset = offset,
                limit = limit,
            )
        } catch (error: Exception) {
            log.warn("Storefront catalog request failed (q={}, category={}): {}", q, category, error.message)
            throw upstream(error)
        }
    }

    fun getProduct(id: String, knownHandle: String? = null): MedusaProduct? {
        if (!active || id.isBlank()) return null
        productsById[id]?.let { cached ->
            if (!cached.description.isNullOrBlank()) return cached
        }
        val handle = knownHandle?.trim()?.takeIf { it.isNotBlank() }
            ?: productsById[id]?.handle?.trim()?.takeIf { it.isNotBlank() }
            ?: return productsById[id]
        return try {
            val wrapper = rest.get().uri { uri ->
                uri.path("/api/product/{handle}").build(handle)
            }.retrieve().body(StorefrontProductWrapper::class.java)
            wrapper?.product?.toMedusaProduct(checkNotNull(baseUrl))
                ?.takeIf { it.id == id }
                ?.also { productsById[it.id] = it }
        } catch (error: Exception) {
            log.warn("Storefront product request failed (id={}, handle={}): {}", id, handle, error.message)
            throw upstream(error)
        }
    }

    fun listCategories(): List<MedusaCategory> {
        if (!active) return emptyList()
        return try {
            val nodes = rest.get().uri("/api/category-tree")
                .retrieve().body(Array<StorefrontCategoryNode>::class.java)?.toList().orEmpty()
            flatten(nodes)
        } catch (error: Exception) {
            log.warn("Storefront category request failed: {}", error.message)
            throw upstream(error)
        }
    }

    fun cachedProduct(id: String): MedusaProduct? = productsById[id]

    private fun flatten(nodes: List<StorefrontCategoryNode>, parentId: String? = null): List<MedusaCategory> =
        nodes.flatMap { node ->
            // /api/catalog filters by category *handle*, not by the database node id.
            // This id is returned to mobile clients and sent back unchanged as a filter.
            val handle = node.handle.trim()
            val current = MedusaCategory(
                id = handle,
                name = node.name.ifBlank { handle },
                handle = handle,
                parentCategoryId = parentId,
            )
            if (handle.isBlank()) flatten(node.children, parentId)
            else listOf(current) + flatten(node.children, handle)
        }

    private fun upstream(error: Exception) =
        AppException(ErrorCode.UPSTREAM_UNAVAILABLE, "Каталог сайта временно недоступен", HttpStatus.BAD_GATEWAY, error)

    companion object {
        internal fun validateBaseUrl(enabled: Boolean, raw: String, connectTimeoutMs: Int, readTimeoutMs: Int): String? {
            if (!enabled) return null
            require(connectTimeoutMs in 100..30_000) { "STOREFRONT_CATALOG_CONNECT_TIMEOUT_MS must be between 100 and 30000" }
            require(readTimeoutMs in 500..120_000) { "STOREFRONT_CATALOG_READ_TIMEOUT_MS must be between 500 and 120000" }
            val value = raw.trim().trimEnd('/')
            require(value.isNotBlank()) { "STOREFRONT_CATALOG_BASE_URL is required when website catalogue is enabled" }
            val uri = runCatching { URI(value) }.getOrElse {
                throw IllegalArgumentException("STOREFRONT_CATALOG_BASE_URL is invalid", it)
            }
            require(uri.host != null && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
                "STOREFRONT_CATALOG_BASE_URL must be an origin without credentials, query or fragment"
            }
            require(uri.rawPath.isNullOrBlank() || uri.rawPath == "/") {
                "STOREFRONT_CATALOG_BASE_URL must be an origin without a path"
            }
            val loopback = uri.host.equals("localhost", true) || uri.host == "127.0.0.1" || uri.host == "::1"
            val pinnedInternalHttp = uri.scheme == "http" &&
                uri.host.equals(PINNED_INTERNAL_HOST, true) &&
                (uri.port == -1 || uri.port == 80)
            require(uri.scheme == "https" || (uri.scheme == "http" && loopback) || pinnedInternalHttp) {
                "STOREFRONT_CATALOG_BASE_URL must use HTTPS (HTTP is allowed only for loopback or the pinned internal storefront)"
            }
            return value
        }

        private const val PINNED_INTERNAL_HOST = "inkeshopapteka.inkar.kz"
    }
}
