package kz.epharm.shared.observability

import io.sentry.Hint
import io.sentry.SentryBaseEvent
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.SentryOptions
import io.sentry.SpanContext
import io.sentry.protocol.SentrySpan
import io.sentry.protocol.SentryStackTrace
import io.sentry.protocol.SentryTransaction
import io.sentry.protocol.TransactionInfo
import jakarta.servlet.http.HttpServletRequest
import kz.epharm.shared.error.AppException
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.ErrorResponse
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.web.servlet.HandlerMapping
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping

/** Diagnostic stacks and timings are useful; receipt, identity and credential payloads are not. */
@Configuration(proxyBeanMethods = false)
class SentryPrivacyConfiguration {
    @Bean
    fun sentryBeforeSend(mappings: ObjectProvider<RequestMappingHandlerMapping>? = null) = SentryOptions.BeforeSendCallback { event, hint ->
        try {
            val error = event.throwable
            val expected = error is AppException && error.status.is4xxClientError ||
                error is ErrorResponse && error.statusCode.is4xxClientError ||
                error is AccessDeniedException || error is DataIntegrityViolationException
            if (expected) null else {
                sanitizeCommon(event, event, hint)
                if (error is AppException) event.setTag("error.code", error.code.name)
                event.transaction = route(hint, event.transaction, mappings)
                event.message = null
                event.logger = null
                event.fingerprints = null
                event.unknown = null
                event.exceptions?.forEach { exception ->
                    exception.value = null
                    exception.unknown = null
                    exception.mechanism?.apply { description = null; helpLink = null; data = null; meta = null; unknown = null }
                    cleanStack(exception.stacktrace)
                }
                event.threads?.forEach { thread ->
                    thread.name = null
                    thread.unknown = null
                    cleanStack(thread.stacktrace)
                }
                if (event.exceptions?.any { it.mechanism?.type == "Spring6ExceptionResolver" } == true) event.level = SentryLevel.ERROR
                event
            }
        } catch (_: Exception) {
            null // Never let an SDK callback failure export the original, unsanitized payload.
        }
    }

    @Bean
    fun sentryBeforeSendTransaction(mappings: ObjectProvider<RequestMappingHandlerMapping>? = null) = SentryOptions.BeforeSendTransactionCallback { event, hint ->
        try {
            // Transaction/span descriptions are immutable in SDK 8.55; copy only technical fields.
            val spans = event.spans.map { span -> SentrySpan(span.startTimestamp, span.timestamp, span.traceId,
                span.spanId, span.parentSpanId, span.op, null, span.status, span.origin, emptyMap(), emptyMap(), emptyMap()) }
            val route = route(hint, event.transaction, mappings)
            SentryTransaction(route, event.startTimestamp, event.timestamp, spans, emptyMap(), TransactionInfo(if (route == "background") "custom" else "route"))
                .also { sanitizeCommon(event, it, hint) }
        } catch (_: Exception) {
            null // Never let an SDK callback failure export the original, unsanitized payload.
        }
    }

    private fun sanitizeCommon(source: SentryBaseEvent, target: SentryBaseEvent, hint: Hint) {
        val trace = source.contexts.trace?.let { original ->
            SpanContext(original.traceId, original.spanId, original.operation, original.parentSpanId, original.samplingDecision)
                .apply { status = original.status; origin = original.origin }
        }
        target.eventId = source.eventId
        target.release = source.release
        target.environment = source.environment
        target.dist = source.dist
        target.platform = source.platform
        target.sdk = source.sdk
        target.debugMeta = source.debugMeta
        target.request = null
        target.user = null
        target.serverName = null
        target.tags = emptyMap()
        target.extras = emptyMap()
        target.breadcrumbs = emptyList()
        target.contexts.clear()
        if (trace != null) target.contexts.setTrace(trace)
        hint.clearAttachments()
        hint.screenshot = null
        hint.viewHierarchy = null
        hint.threadDump = null
    }

    private fun cleanStack(stack: SentryStackTrace?) {
        stack?.apply {
            registers = null
            unknown = null
            frames?.forEach { frame ->
                frame.vars = null
                frame.absPath = null
                frame.contextLine = null
                frame.preContext = null
                frame.postContext = null
                frame.unknown = null
            }
        }
    }

    private fun route(hint: Hint, original: String?, mappings: ObjectProvider<RequestMappingHandlerMapping>?): String {
        val request = hint.get("springResolver:request") as? HttpServletRequest
            ?: (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request
        // Only a server-authored route template; never fall back to raw URI/query/transaction names.
        val pattern = request?.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) as? String
        val methods = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")
        val method = request?.method?.takeIf { it in methods }
        if (pattern != null && method != null) return "$method $pattern"
        // A tracing callback can run after RequestContextHolder is cleared. Only retain names
        // which exactly match a registered template; raw paths and unknown routes stay private.
        val originalMethod = original?.substringBefore(' ')
        val originalPattern = original?.substringAfter(' ', "")
        if (originalMethod !in methods) return "background"
        val registered = runCatching {
            mappings?.stream()?.use { candidates -> candidates.anyMatch { mapping ->
                mapping.handlerMethods.keys.any { originalPattern in it.patternValues }
            } } == true
        }.getOrDefault(false)
        if (originalMethod in methods && registered) return original!!
        return "background"
    }
}
