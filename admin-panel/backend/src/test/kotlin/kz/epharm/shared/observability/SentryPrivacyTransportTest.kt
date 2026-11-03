package kz.epharm.shared.observability

import io.sentry.Hint
import io.sentry.ITransportFactory
import io.sentry.PropagationContext
import io.sentry.RequestDetails
import io.sentry.Scope
import io.sentry.SentryClient
import io.sentry.SentryEnvelope
import io.sentry.SentryEnvelopeHeader
import io.sentry.SentryEnvelopeItem
import io.sentry.SentryEvent
import io.sentry.SentryItemType
import io.sentry.SentryOptions
import io.sentry.SpanContext
import io.sentry.SpanId
import io.sentry.TracesSamplingDecision
import io.sentry.protocol.SentryId
import io.sentry.protocol.SentryTransaction
import io.sentry.protocol.TransactionInfo
import io.sentry.protocol.User
import io.sentry.transport.ITransport
import io.sentry.transport.RateLimiter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.Date

class SentryPrivacyTransportTest {
    private class RecordingTransport(options: SentryOptions) : ITransport {
        val deliveries = mutableListOf<Pair<SentryEnvelope, Hint>>()
        val flushes = mutableListOf<Long>()
        val restarts = mutableListOf<Boolean>()
        var closes = 0
        val limiter = RateLimiter(options)
        override fun send(envelope: SentryEnvelope, hint: Hint) { deliveries.add(envelope to hint) }
        override fun flush(timeoutMillis: Long) { flushes.add(timeoutMillis) }
        override fun getRateLimiter() = limiter
        override fun isHealthy() = false
        override fun close() { closes++ }
        override fun close(isRestarting: Boolean) { restarts.add(isRestarting) }
    }

    @Test fun `whole SDK envelopes redact incoming baggage and scope users for errors and transactions`() {
        val options = SentryOptions().apply {
            dsn = "https://public@ingest.invalid/1"
            isSendDefaultPii = false
            release = "epharm-backend@fixture"
            environment = "acceptance"
            beforeSend = SentryPrivacyConfiguration().sentryBeforeSend()
            beforeSendTransaction = SentryPrivacyConfiguration().sentryBeforeSendTransaction()
        }
        val transport = RecordingTransport(options)
        options.setTransportFactory(SentryPrivacyTransportFactory(ITransportFactory { _, _ -> transport }))
        val traceId = SentryId("11111111111111111111111111111111")
        val propagation = PropagationContext.fromHeaders(options.logger,
            "$traceId-2222222222222222-1",
            "sentry-trace_id=$traceId,sentry-public_key=public,sentry-user_id=000000000001," +
                "sentry-token=synthetic-secret-token,sentry-transaction=synthetic-private-transaction," +
                "other-token=synthetic-third-party-token", options)
        // This untrusted metadata bypasses beforeSend in SDK 8.55 unless the envelope is sanitized.
        assertThat(propagation.traceContext()!!.userId).isEqualTo("000000000001")
        assertThat(propagation.traceContext()!!.unknown).containsEntry("token", "synthetic-secret-token")
        val scope = Scope(options).apply {
            propagationContext = propagation
            user = User().apply { id = "000000000001"; email = "synthetic@example.invalid" }
        }
        val trace = SpanContext(traceId, SpanId(), "http.server", null, TracesSamplingDecision(true))
        val event = SentryEvent(IllegalStateException("synthetic-secret-token")).apply { contexts.setTrace(trace) }
        val transaction = SentryTransaction("synthetic-private-transaction", 1.0, 2.0, emptyList(), emptyMap(), TransactionInfo("url"))
            .apply { contexts.setTrace(trace) }
        val client = SentryClient(options)
        try {
            client.captureEvent(event, scope, Hint())
            client.captureTransaction(transaction, propagation.traceContext(), scope, Hint(), null)
            assertThat(transport.deliveries).hasSize(2)
            transport.deliveries.forEach { (envelope, _) ->
                val serialized = ByteArrayOutputStream().also { options.serializer.serialize(envelope, it) }.toString(Charsets.UTF_8)
                assertThat(serialized).doesNotContain("000000000001", "synthetic-secret-token", "synthetic-private-transaction",
                    "synthetic-third-party-token", "synthetic@example.invalid")
                assertThat(envelope.header.traceContext).isNull()
                val item = envelope.items.single()
                val payload = if (item.header.type == SentryItemType.Event) item.getEvent(options.serializer)!!
                    else item.getTransaction(options.serializer)!!
                assertThat(payload.contexts.trace!!.traceId).isEqualTo(traceId)
                assertThat(payload.release).isEqualTo("epharm-backend@fixture")
                assertThat(payload.environment).isEqualTo("acceptance")
            }
        } finally { client.close() }
    }

    @Test fun `transport preserves SDK payload identity metadata and lifecycle for both send overloads`() {
        val options = SentryOptions()
        val delegate = RecordingTransport(options)
        val transport = SentryPrivacyTransportFactory(ITransportFactory { _, _ -> delegate })
            .create(options, RequestDetails("https://ingest.invalid", emptyMap()))
        val header = SentryEnvelopeHeader(SentryId(), options.sdkVersion).apply {
            sentAt = Date(1234L)
            unknown = mapOf("private" to "synthetic-secret-token")
        }
        val items = listOf(SentryEnvelopeItem.fromEvent(options.serializer, SentryEvent()))
        val original = SentryEnvelope(header, items)
        val hint = Hint()
        transport.send(original, hint)
        transport.send(original)
        assertThat(delegate.deliveries).hasSize(2)
        delegate.deliveries.forEach { (sent, _) ->
            assertThat(sent.items).isSameAs(items)
            assertThat(sent.header.eventId).isEqualTo(header.eventId)
            assertThat(sent.header.sdkVersion).isSameAs(header.sdkVersion)
            assertThat(sent.header.sentAt).isEqualTo(header.sentAt)
            assertThat(sent.header.unknown).isNull()
        }
        assertThat(delegate.deliveries.first().second).isSameAs(hint)
        transport.flush(123L)
        assertThat(transport.rateLimiter).isSameAs(delegate.limiter)
        assertThat(transport.isHealthy).isFalse()
        transport.close()
        transport.close(true)
        assertThat(delegate.flushes).containsExactly(123L)
        assertThat(delegate.closes).isEqualTo(1)
        assertThat(delegate.restarts).containsExactly(true)
    }
}
