package kz.epharm.lms.service

import kz.epharm.lms.entity.CourseLiveProvider
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

internal object LiveStreamUrlPolicy {
    fun safeUrl(raw: String?, provider: CourseLiveProvider): String? {
        val value = raw?.trim()?.takeIf(String::isNotBlank) ?: return null
        val uri = try {
            URI(value)
        } catch (_: URISyntaxException) {
            return null
        }
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.rawUserInfo != null || uri.port !in listOf(-1, 443)) {
            return null
        }
        val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return null
        if (host.length > 253 || !host.contains('.') || host.contains(':') || host.matches(Regex("[0-9.]+"))) {
            return null
        }
        if (host.endsWith(".local") || host.endsWith(".localhost") || host.endsWith(".internal")) {
            return null
        }
        if (host.split('.').any { !it.matches(Regex("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")) }) {
            return null
        }
        if (host.substringAfterLast('.').length < 2 || host.substringAfterLast('.').all(Char::isDigit)) {
            return null
        }
        val allowed = when (provider) {
            CourseLiveProvider.youtube -> hostIs(host, "youtube.com") || host == "youtu.be"
            CourseLiveProvider.vimeo -> hostIs(host, "vimeo.com")
            CourseLiveProvider.teams -> hostIs(host, "teams.microsoft.com") || hostIs(host, "teams.live.com")
            CourseLiveProvider.zoom -> hostIs(host, "zoom.us")
            CourseLiveProvider.custom -> true
        }
        return value.takeIf { allowed }
    }

    private fun hostIs(host: String, domain: String): Boolean =
        host == domain || host.endsWith(".$domain")
}
