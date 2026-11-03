package kz.epharm.shared.observability

import io.sentry.Attachment
import io.sentry.Breadcrumb
import io.sentry.Hint
import io.sentry.JsonSerializer
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import io.sentry.SpanContext
import io.sentry.SpanId
import io.sentry.SpanStatus
import io.sentry.TracesSamplingDecision
import io.sentry.protocol.Mechanism
import io.sentry.protocol.Message
import io.sentry.protocol.Request
import io.sentry.protocol.SentryException
import io.sentry.protocol.SentryId
import io.sentry.protocol.SentrySpan
import io.sentry.protocol.SentryStackFrame
import io.sentry.protocol.SentryStackTrace
import io.sentry.protocol.SentryThread
import io.sentry.protocol.SentryTransaction
import io.sentry.protocol.TransactionInfo
import io.sentry.protocol.User
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.HandlerMapping
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import org.springframework.beans.factory.ObjectProvider
import io.mockk.every
import io.mockk.mockk
import java.io.StringWriter

class SentryPrivacyConfigurationTest {
    private val config = SentryPrivacyConfiguration()
    private val secret = "SYNTHETIC-PRIVATE-000000000001-OTP0000-token"
    private fun json(value: Any) = StringWriter().also { JsonSerializer(SentryOptions()).serialize(value, it) }.toString()
    private fun hint() = Hint().apply {
        set("springResolver:request", MockHttpServletRequest("POST", "/api/people/$secret").apply {
            setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/people/{id}")
        })
        addAttachment(Attachment(secret.toByteArray(), "receipt.txt"))
    }
    private fun trace() = SpanContext(SentryId(), SpanId(), "http.server", null, TracesSamplingDecision(true)).apply {
        status = SpanStatus.INTERNAL_ERROR
        description = secret
        setTag("user", secret)
        setData("url", secret)
    }
    private fun privateFields(event: io.sentry.SentryBaseEvent) {
        event.release = "epharm-backend@v1.2.3"
        event.environment = "acceptance"
        event.request = Request().apply {
            url = "https://example.invalid/api/$secret"
            queryString = "iin=$secret"
            headers = mapOf("X-Posm-Key" to secret, "X-Staff-Session" to secret, "X-Merch-Task-Token" to secret,
                "Authorization" to secret, "Cookie" to secret)
            cookies = secret
            data = mapOf("password" to secret, "iin" to secret)
        }
        event.user = User().apply { id = secret; email = "$secret@example.invalid"; username = secret; ipAddress = secret }
        event.serverName = secret
        event.tags = mapOf("person" to secret)
        event.extras = mapOf("nested" to mapOf("token" to secret))
        event.breadcrumbs = listOf(Breadcrumb(secret).apply { setData("password", secret) })
        event.contexts["person"] = mapOf("iin" to secret)
        event.contexts.setTrace(trace())
    }

    @Test fun `errors remove private payloads while keeping release route exception and source stack`() {
        val frame = SentryStackFrame().apply {
            filename = "ExampleService.kt"; function = "save"; module = "kz.epharm.ExampleService"; lineno = 42
            vars = mapOf("password" to secret); absPath = "/users/$secret/ExampleService.kt"
            contextLine = secret; preContext = listOf(secret); postContext = listOf(secret); unknown = mapOf("raw" to secret)
        }
        val event = SentryEvent().apply {
            privateFields(this)
            transaction = "/api/$secret"
            message = Message().apply { formatted = secret }
            logger = secret
            fingerprints = listOf(secret)
            unknown = mapOf("unexpected" to secret)
            exceptions = listOf(SentryException().apply {
                type = "IllegalStateException"; value = secret; module = "java.lang"
                stacktrace = SentryStackTrace(listOf(frame)).apply { registers = mapOf("x" to secret) }
                mechanism = Mechanism().apply { type = "Spring6ExceptionResolver"; description = secret; data = mapOf("key" to secret) }
            })
            threads = listOf(SentryThread().apply { name = secret; stacktrace = SentryStackTrace(listOf(frame)) })
        }
        val originalId = event.eventId
        val originalTrace = event.contexts.trace!!.traceId
        val hint = hint()
        val result = config.sentryBeforeSend().execute(event, hint)!!
        assertThat(json(result)).doesNotContain(secret).contains("ExampleService.kt", "IllegalStateException", "save")
        assertThat(result.eventId).isEqualTo(originalId)
        assertThat(result.release).isEqualTo("epharm-backend@v1.2.3")
        assertThat(result.environment).isEqualTo("acceptance")
        assertThat(result.transaction).isEqualTo("POST /api/people/{id}")
        assertThat(result.exceptions!!.single().stacktrace!!.frames!!.single().lineno).isEqualTo(42)
        assertThat(result.contexts.trace!!.traceId).isEqualTo(originalTrace)
        assertThat(result.contexts.trace!!.status).isEqualTo(SpanStatus.INTERNAL_ERROR)
        assertThat(hint.attachments).isEmpty()
    }

    @Test fun `transactions remove raw names span payloads and scopes while preserving timeline and trace ids`() {
        val originalTrace = trace()
        val spanId = SpanId()
        val span = SentrySpan(1.0, 2.0, originalTrace.traceId, spanId, originalTrace.spanId,
            "http.client", secret, SpanStatus.OK, "auto.http", mapOf("person" to secret), emptyMap(), mapOf("url" to secret))
        val event = SentryTransaction(secret, 1.0, 3.0, listOf(span), emptyMap(), TransactionInfo("url")).apply {
            privateFields(this)
            contexts.setTrace(originalTrace)
        }
        val result = config.sentryBeforeSendTransaction().execute(event, hint())!!
        assertThat(json(result)).doesNotContain(secret)
        assertThat(result.transaction).isEqualTo("POST /api/people/{id}")
        assertThat(result.eventId).isEqualTo(event.eventId)
        assertThat(result.release).isEqualTo(event.release)
        assertThat(result.environment).isEqualTo(event.environment)
        assertThat(result.startTimestamp).isEqualTo(1.0)
        assertThat(result.timestamp).isEqualTo(3.0)
        assertThat(result.contexts.trace!!.traceId).isEqualTo(originalTrace.traceId)
        assertThat(result.contexts.trace!!.sampled).isTrue()
        assertThat(result.spans.single().spanId).isEqualTo(spanId)
        assertThat(result.spans.single().parentSpanId).isEqualTo(originalTrace.spanId)
        assertThat(result.spans.single().op).isEqualTo("http.client")
        assertThat(result.spans.single().status).isEqualTo(SpanStatus.OK)
    }

    @Test fun `missing server route never falls back to a private URL`() {
        val event = SentryEvent().apply { request = Request().apply { url = "https://example.invalid/$secret" }; transaction = secret }
        assertThat(config.sentryBeforeSend().execute(event, Hint())!!.transaction).isEqualTo("background")
        assertThat(json(event)).doesNotContain(secret)
    }

    @Test fun `unavailable route metadata keeps a redacted error instead of throwing out of the callback`() {
        val mappings = mockk<ObjectProvider<RequestMappingHandlerMapping>>()
        every { mappings.stream() } throws IllegalStateException(secret)
        val event = SentryEvent().apply {
            privateFields(this)
            transaction = "POST /api/$secret"
            exceptions = listOf(SentryException().apply { type = "IllegalStateException"; value = secret })
        }
        val result = config.sentryBeforeSend(mappings).execute(event, Hint())!!
        assertThat(result.transaction).isEqualTo("background")
        assertThat(json(result)).doesNotContain(secret).contains("IllegalStateException")
    }

    @Test fun `a callback failure drops both event types instead of exporting the original payload`() {
        val event = mockk<SentryEvent>()
        every { event.throwable } throws IllegalStateException(secret)
        assertThat(config.sentryBeforeSend().execute(event, Hint())).isNull()
        val transaction = mockk<SentryTransaction>()
        every { transaction.spans } throws IllegalArgumentException(secret)
        assertThat(config.sentryBeforeSendTransaction().execute(transaction, Hint())).isNull()
    }

    @Test fun `expected client errors are dropped but domain 5xx and unexpected failures survive`() {
        for (error in listOf(AppException(ErrorCode.INVALID_CREDENTIALS, secret, HttpStatus.UNAUTHORIZED),
            ResponseStatusException(HttpStatus.BAD_REQUEST, secret), AccessDeniedException(secret))) {
            assertThat(config.sentryBeforeSend().execute(SentryEvent(error), Hint())).isNull()
        }
        for (error in listOf(AppException(ErrorCode.UPSTREAM_UNAVAILABLE, secret, HttpStatus.SERVICE_UNAVAILABLE),
            IllegalStateException(secret))) {
            assertThat(config.sentryBeforeSend().execute(SentryEvent(error), Hint())).isNotNull()
        }
    }
}
