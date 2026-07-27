package kz.epharm.mobile.auth

import kz.epharm.mobile.auth.service.P1smsSender
import kz.epharm.mobile.auth.service.LoggingSmsSender
import kz.epharm.mobile.auth.service.SmsSenderConfig
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.http.HttpMethod
import org.springframework.web.client.RestClient

/**
 * Юнит-тест боевого SMS-провайдера p1sms (без сети — MockRestServiceServer).
 * Проверяем контракт из apiInstruction.pdf: тело запроса (apiKey, канал, телефон из 11 цифр
 * БЕЗ «+», код в тексте) и обработку ответов (success / отказ / 5xx → SMS_SEND_FAILED).
 */
class P1smsSenderTest {

    // ВАЖНО: baseUrl задаём на builder ДО bindTo-моков и НЕ трогаем requestFactory —
    // MockRestServiceServer работает через собственную фабрику, её нельзя перетирать.
    private fun sender(builder: RestClient.Builder, channel: String = "char", senderName: String = "EPHARM") =
        P1smsSender(
            rest = builder.baseUrl("https://sms.test").build(),
            apiKey = "test-key",
            channel = channel,
            sender = senderName,
            textTemplate = "Код входа ePharm: {code}",
        )

    @Test
    fun `отправляет корректное тело - канал, телефон без плюса, код в тексте, sender`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("https://sms.test/apiSms/create"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.apiKey").value("test-key"))
            .andExpect(jsonPath("$.sms[0].channel").value("char"))
            .andExpect(jsonPath("$.sms[0].phone").value("77011112233"))
            .andExpect(jsonPath("$.sms[0].sender").value("EPHARM"))
            .andExpect(jsonPath("$.sms[0].text").value("Код входа ePharm: 123456"))
            .andRespond(
                withSuccess(
                    """{"status":"success","data":[{"id":1,"status":"sent","phone":"77011112233"}]}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        assertThatCode { sender(builder).sendOtp("+77011112233", "123456") }.doesNotThrowAnyException()
        server.verify()
    }

    @Test
    fun `digit-канал без sender - поле sender не отправляется`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("https://sms.test/apiSms/create"))
            .andExpect(jsonPath("$.sms[0].channel").value("digit"))
            .andExpect(jsonPath("$.sms[0].sender").doesNotExist())
            .andRespond(withSuccess("""{"status":"success","data":[]}""", MediaType.APPLICATION_JSON))

        assertThatCode { sender(builder, channel = "digit", senderName = "").sendOtp("+77011112233", "555444") }
            .doesNotThrowAnyException()
        server.verify()
    }

    @Test
    fun `провайдер отклонил (status не success) - SMS_SEND_FAILED 502`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("https://sms.test/apiSms/create"))
            .andRespond(withSuccess("""{"status":"error"}""", MediaType.APPLICATION_JSON))

        assertThatThrownBy { sender(builder).sendOtp("+77011112233", "123456") }
            .isInstanceOf(AppException::class.java)
            .satisfies({ e ->
                e as AppException
                assert(e.code == ErrorCode.SMS_SEND_FAILED)
                assert(e.status == HttpStatus.BAD_GATEWAY)
            })
        server.verify()
    }

    @Test
    fun `HTTP 5xx от провайдера - SMS_SEND_FAILED 502`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("https://sms.test/apiSms/create")).andRespond(withServerError())

        assertThatThrownBy { sender(builder).sendOtp("+77011112233", "123456") }
            .isInstanceOf(AppException::class.java)
            .extracting("code").isEqualTo(ErrorCode.SMS_SEND_FAILED)
        server.verify()
    }

    @Test
    fun `production без API key завершается с ошибкой конфигурации`() {
        assertThatThrownBy {
            senderConfig(apiKey = "", devMode = false)
        }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("P1SMS_API_KEY")
    }

    @Test
    fun `dev режим без API key использует безопасную заглушку`() {
        assertThat(senderConfig(apiKey = "", devMode = true)).isInstanceOf(LoggingSmsSender::class.java)
    }

    private fun senderConfig(apiKey: String, devMode: Boolean) = SmsSenderConfig().smsSender(
        baseUrl = "https://sms.test",
        apiKey = apiKey,
        devMode = devMode,
        channel = "digit",
        sender = "",
        textTemplate = "Код входа ePharm: {code}",
        timeoutMs = 10_000,
    )
}
