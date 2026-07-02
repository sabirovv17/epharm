package kz.epharm.medusa.client

import kz.epharm.medusa.dto.MedusaCategoryListResponse
import kz.epharm.medusa.dto.MedusaProduct
import kz.epharm.medusa.dto.MedusaProductPharmaciesResponse
import kz.epharm.medusa.dto.MedusaProductListResponse
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/**
 * Тонкий HTTP-клиент к Medusa Store API (витрина inkar.kz, Module 3).
 *
 * Архитектура — бэкенд как прокси: телефон ходит в НАШ бэкенд (`/api/mobile/catalog/…`),
 * а сюда лезем server-to-server. Поэтому:
 *  - publishable-ключ Medusa НЕ попадает в мобильный бинарь (только в env бэкенда);
 *  - мобилка общается с нашим HTTPS, даже если Medusa пока на голом HTTP;
 *  - можем кешировать/нормализовать ответ под наши DTO.
 *
 * Конфиг — `app.medusa.*` (см. application.yml). Если ключ/URL пусты или
 * `enabled=false` — клиент «неактивен»: list-методы отдают пустоту, detail — null
 * (каталог деградирует в пустой экран, а не 500).
 */
@Component
class MedusaClient(
    @Value("\${app.medusa.enabled:true}") private val enabled: Boolean,
    @Value("\${app.medusa.base-url:}") private val baseUrl: String,
    @Value("\${app.medusa.publishable-key:}") private val publishableKey: String,
    @Value("\${app.medusa.sales-channel-id:}") private val salesChannelId: String,
    @Value("\${app.medusa.region-id:}") private val regionId: String,
    @Value("\${app.medusa.timeout-ms:6000}") private val timeoutMs: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Клиент активен только при заданных URL + ключе и enabled=true. */
    val active: Boolean = enabled && baseUrl.isNotBlank() && publishableKey.isNotBlank()

    private val rest: RestClient by lazy {
        val factory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(timeoutMs)
            setReadTimeout(timeoutMs)
        }
        RestClient.builder()
            .baseUrl(baseUrl.trimEnd('/'))
            .requestFactory(factory)
            .defaultHeader("x-publishable-api-key", publishableKey)
            .build()
    }

    /**
     * Список товаров канала «Сайт». `q` — поиск по названию (ILIKE на стороне Medusa),
     * `categoryId` — фильтр по категории, `ids` — конкретные id (используется для detail).
     */
    fun listProducts(
        q: String? = null,
        categoryId: String? = null,
        ids: List<String>? = null,
        limit: Int = 24,
        offset: Int = 0,
    ): MedusaProductListResponse {
        if (!active) return MedusaProductListResponse(limit = limit, offset = offset)
        return try {
            rest.get().uri { uri ->
                uri.path("/store/products")
                    .queryParam("sales_channel_id", salesChannelId)
                    .queryParam("fields", LIST_FIELDS)
                    .queryParam("limit", limit)
                    .queryParam("offset", offset)
                if (regionId.isNotBlank()) uri.queryParam("region_id", regionId)
                if (!q.isNullOrBlank()) uri.queryParam("q", q.trim())
                if (!categoryId.isNullOrBlank()) uri.queryParam("category_id[]", categoryId)
                ids?.filter { it.isNotBlank() }?.forEach { uri.queryParam("id[]", it) }
                uri.build()
            }.retrieve().body(MedusaProductListResponse::class.java)
                ?: MedusaProductListResponse(limit = limit, offset = offset)
        } catch (e: Exception) {
            log.warn("Medusa listProducts failed (q={}, cat={}): {}", q, categoryId, e.message)
            throw upstream(e)
        }
    }

    /** Один товар по medusa-id (через list-by-id — переиспользуем парсер и набор полей). */
    fun getProduct(id: String): MedusaProduct? {
        if (!active || id.isBlank()) return null
        return try {
            rest.get().uri { uri ->
                uri.path("/store/products")
                    .queryParam("sales_channel_id", salesChannelId)
                    .queryParam("fields", DETAIL_FIELDS)
                    .queryParam("limit", 1)
                    .queryParam("id[]", id)
                if (regionId.isNotBlank()) uri.queryParam("region_id", regionId)
                uri.build()
            }.retrieve().body(MedusaProductListResponse::class.java)?.products?.firstOrNull()
        } catch (e: Exception) {
            log.warn("Medusa getProduct({}) failed: {}", id, e.message)
            throw upstream(e)
        }
    }

    /**
     * Retail-цены товара по аптекам из Medusa `pharmacy_pricing`.
     *
     * Это fallback для HQ/POSM, когда нативный `variant.calculated_price` ещё не
     * заполнен. Ошибка fallback не должна ронять каталог: возвращаем null и
     * оставляем товар без цены.
     */
    fun getProductPharmacies(id: String): MedusaProductPharmaciesResponse? {
        if (!active || id.isBlank()) return null
        return try {
            rest.get().uri { uri ->
                uri.path("/store/products/{id}/pharmacies").build(id)
            }.retrieve().body(MedusaProductPharmaciesResponse::class.java)
        } catch (e: Exception) {
            log.warn("Medusa product pharmacies fallback failed (id={}): {}", id, e.message)
            null
        }
    }

    /** Дерево категорий (верхние уровни). Товары к ним пока линкуются не все — на будущее. */
    fun listCategories(limit: Int = 100): MedusaCategoryListResponse {
        if (!active) return MedusaCategoryListResponse()
        return try {
            rest.get().uri { uri ->
                uri.path("/store/product-categories")
                    .queryParam("fields", "id,name,handle,parent_category_id")
                    .queryParam("limit", limit)
                    .build()
            }.retrieve().body(MedusaCategoryListResponse::class.java) ?: MedusaCategoryListResponse()
        } catch (e: Exception) {
            log.warn("Medusa listCategories failed: {}", e.message)
            throw upstream(e)
        }
    }

    private fun upstream(e: Exception) =
        AppException(ErrorCode.UPSTREAM_UNAVAILABLE, "Каталог временно недоступен", HttpStatus.BAD_GATEWAY, e)

    companion object {
        // Лёгкий набор полей для листинга (без description/tags — экономим трафик).
        private const val LIST_FIELDS =
            "id,title,subtitle,handle,thumbnail,status,*images,*categories,*variants.calculated_price,*variants,metadata"

        // Полный набор для карточки товара.
        private const val DETAIL_FIELDS =
            "id,title,subtitle,description,handle,thumbnail,status,*images,*categories,*tags,*variants.calculated_price,*variants,metadata"
    }
}
