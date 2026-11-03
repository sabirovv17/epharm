package kz.epharm.shared.observability

import io.sentry.Hint
import io.sentry.ITransportFactory
import io.sentry.SentryEnvelope
import io.sentry.SentryItemType
import io.sentry.spring.boot.jakarta.SentryAutoConfiguration
import io.sentry.spring.boot.jakarta.SentryProperties
import io.sentry.transport.ITransport
import io.sentry.transport.RateLimiter
import io.sentry.protocol.SentryTransaction
import io.sentry.protocol.TransactionInfo
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import kz.epharm.shared.error.GlobalExceptionHandler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.core.io.ClassPathResource
import org.springframework.http.HttpStatus
import org.springframework.security.access.AccessDeniedException
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

class SentryMvcCaptureTest {
    private val secret = "SYNTHETIC-PRIVATE-000000000001-OTP0000-token"

    @RestController
    class ProbeController {
        @PostMapping("/observability-fixture/{id}")
        fun probe(@PathVariable id: String, @RequestParam mode: String): String = when (mode) {
            "unexpected" -> throw IllegalStateException("SYNTHETIC-PRIVATE-000000000001-OTP0000-token")
            "unauthorized" -> throw AppException(ErrorCode.INVALID_CREDENTIALS, "test-only", HttpStatus.UNAUTHORIZED)
            "forbidden" -> throw AccessDeniedException("test-only")
            "upstream" -> throw AppException(ErrorCode.UPSTREAM_UNAVAILABLE, "test-only", HttpStatus.SERVICE_UNAVAILABLE)
            else -> throw AppException(ErrorCode.VALIDATION_FAILED, "test-only", HttpStatus.BAD_REQUEST)
        }
    }

    private class RecordingTransport : ITransport {
        val envelopes = mutableListOf<SentryEnvelope>()
        override fun send(envelope: SentryEnvelope, hint: Hint) { envelopes.add(envelope) }
        override fun flush(timeoutMillis: Long) = Unit
        override fun getRateLimiter(): RateLimiter? = null
        override fun close() = Unit
        override fun close(isRestarting: Boolean) = Unit
    }

    @Test fun `real SDK and MVC resolver capture handled 500 once without PII and ignore expected 4xx`() {
        val transport = RecordingTransport()
        WebApplicationContextRunner()
            .withInitializer { context ->
                YamlPropertySourceLoader().load("application", ClassPathResource("application.yml"))
                    .forEach { context.environment.propertySources.addLast(it) }
            }
            .withConfiguration(AutoConfigurations.of(WebMvcAutoConfiguration::class.java, SentryAutoConfiguration::class.java))
            .withUserConfiguration(SentryPrivacyConfiguration::class.java, GlobalExceptionHandler::class.java, ProbeController::class.java)
            .withBean(ITransportFactory::class.java, { ITransportFactory { _, _ -> transport } })
            .withPropertyValues("SENTRY_DSN=https://public@ingest.invalid/1", "APP_ENVIRONMENT=acceptance",
                "RELEASE_ID=fixture", "SENTRY_TRACES_SAMPLE_RATE=0.05")
            .run { context ->
                assertThat(context).hasNotFailed()
                val options = context.getBean(SentryProperties::class.java)
                assertThat(options.beforeSend).isNotNull()
                assertThat(options.beforeSendTransaction).isNotNull()
                assertThat(options.exceptionResolverOrder).isEqualTo(-1)
                assertThat(options.maxRequestBodySize.name).isEqualTo("NONE")
                assertThat(options.isSendDefaultPii).isFalse()
                assertThat(options.tracesSampleRate).isEqualTo(0.05)
                // The SDK finishes HTTP traces after the request context is gone.
                for ((name, expected) in listOf("POST /observability-fixture/{id}" to "POST /observability-fixture/{id}",
                    "POST /observability-fixture/private-person-id" to "background")) {
                    val transaction = SentryTransaction(name, 1.0, 2.0, emptyList(), emptyMap(), TransactionInfo("url"))
                    assertThat(options.beforeSendTransaction!!.execute(transaction, Hint())!!.transaction).isEqualTo(expected)
                }
                val filter = context.getBean("sentrySpringFilter", FilterRegistrationBean::class.java).filter
                val mvc = MockMvcBuilders.webAppContextSetup(context).addFilters<DefaultMockMvcBuilder>(filter).build()
                fun request(mode: String) = post("/observability-fixture/private-person-id")
                    .param("mode", mode).queryParam("iin", secret).queryParam("token", secret)
                    .header("X-Posm-Key", secret).header("X-Staff-Session", secret).header("X-Merch-Task-Token", secret)
                    .header("Authorization", "Bearer $secret").header("Cookie", "session=$secret")
                    .contentType("application/json").content("""{"password":"$secret","phone":"+70000000001"}""")
                mvc.perform(request("unexpected")).andExpect(status().isInternalServerError)
                val events = transport.envelopes.flatMap { it.items }.filter { it.header.type == SentryItemType.Event }
                assertThat(events).hasSize(1)
                val payload = events.single().data.toString(Charsets.UTF_8)
                assertThat(payload).doesNotContain(secret, "+70000000001", "private-person-id", "X-Posm-Key", "X-Staff-Session", "X-Merch-Task-Token")
                val event = events.single().getEvent(options.serializer)!!
                assertThat(event.release).isEqualTo("epharm-backend@fixture")
                assertThat(event.environment).isEqualTo("acceptance")
                assertThat(event.transaction).isEqualTo("POST /observability-fixture/{id}")
                assertThat(event.exceptions).anySatisfy { exception ->
                    assertThat(exception.type).isEqualTo("IllegalStateException")
                    assertThat(exception.value).isNull()
                    assertThat(exception.stacktrace!!.frames).anySatisfy { frame ->
                        assertThat(frame.filename).isEqualTo("SentryMvcCaptureTest.kt")
                        assertThat(frame.function).isEqualTo("probe")
                        assertThat(frame.lineno).isGreaterThan(0)
                    }
                }
                for ((mode, expectedStatus) in listOf("unauthorized" to 401, "forbidden" to 403, "validation" to 400))
                    mvc.perform(request(mode)).andExpect(status().`is`(expectedStatus))
                assertThat(transport.envelopes.flatMap { it.items }.count { it.header.type == SentryItemType.Event }).isEqualTo(1)
                mvc.perform(request("upstream")).andExpect(status().isServiceUnavailable)
                assertThat(transport.envelopes.flatMap { it.items }.count { it.header.type == SentryItemType.Event }).isEqualTo(2)
            }
    }
}
