package kz.epharm.medusa.client

import java.net.URI

/** Fail-closed validation for the external Medusa Store API origin. */
internal object MedusaEndpointPolicy {
    private val loopbackHosts = setOf("localhost", "127.0.0.1", "::1")

    fun validate(
        enabled: Boolean,
        rawBaseUrl: String,
        publishableKey: String,
        salesChannelId: String,
        regionId: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): String? {
        if (!enabled) return null

        fun requireValue(value: String, envName: String) {
            check(value.isNotBlank()) { "$envName is required when MEDUSA_ENABLED=true" }
        }

        requireValue(rawBaseUrl, "MEDUSA_BASE_URL")
        requireValue(publishableKey, "MEDUSA_PUBLISHABLE_KEY")
        requireValue(salesChannelId, "MEDUSA_SALES_CHANNEL_ID")
        requireValue(regionId, "MEDUSA_REGION_ID")
        check(connectTimeoutMs in 250..30_000) { "MEDUSA_CONNECT_TIMEOUT_MS must be between 250 and 30000" }
        check(readTimeoutMs in 250..30_000) { "MEDUSA_READ_TIMEOUT_MS must be between 250 and 30000" }

        val uri = runCatching { URI(rawBaseUrl.trim()) }
            .getOrElse { throw IllegalStateException("MEDUSA_BASE_URL must be an absolute URL", it) }
        val host = uri.host?.removePrefix("[")?.removeSuffix("]")?.lowercase().orEmpty()
        val path = uri.rawPath.orEmpty()

        check(uri.isAbsolute && host.isNotBlank()) { "MEDUSA_BASE_URL must be an absolute URL" }
        check(uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
            "MEDUSA_BASE_URL must be an origin without credentials, query or fragment"
        }
        check(path.isEmpty() || path == "/") { "MEDUSA_BASE_URL must not contain a path" }
        check(uri.scheme.equals("https", ignoreCase = true) ||
            (uri.scheme.equals("http", ignoreCase = true) && host in loopbackHosts)) {
            "MEDUSA_BASE_URL must use HTTPS; HTTP is allowed only for loopback development"
        }

        return rawBaseUrl.trim().trimEnd('/')
    }
}
