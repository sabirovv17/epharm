package kz.epharm.medusa

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MedusaCatalogCacheTest {
    @Test
    fun `expired entry serves last known good value when refresh fails`() {
        val cache = MedusaCatalogCache(ttlSeconds = 1, staleIfErrorSeconds = 30)

        assertThat(cache.get("products") { "catalog-v1" }).isEqualTo("catalog-v1")
        Thread.sleep(1_100)

        val fallback = cache.get<String>("products") { error("Medusa is temporarily unavailable") }
        assertThat(fallback).isEqualTo("catalog-v1")
    }
}
