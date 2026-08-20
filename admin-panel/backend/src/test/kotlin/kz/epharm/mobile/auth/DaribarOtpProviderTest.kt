package kz.epharm.mobile.auth

import com.fasterxml.jackson.databind.ObjectMapper
import kz.epharm.mobile.auth.service.DaribarOtpProvider
import kz.epharm.mobile.auth.service.OtpVerificationResult
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient

class DaribarOtpProviderTest {
    private val objectMapper = ObjectMapper().findAndRegisterModules()

    private fun provider(builder: RestClient.Builder) = DaribarOtpProvider(
        rest = builder.baseUrl("https://gateway.test").build(),
        objectMapper = objectMapper,
    )

    @Test
    fun `request отправляет номер без плюса и тип auth без локального кода`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("https://gateway.test/api/v2/sms"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.phone").value("77011112233"))
            .andExpect(jsonPath("$.sms_type").value("auth"))
            .andExpect(jsonPath("$.validation_code").doesNotExist())
            .andRespond(withSuccess("""{"status":"success","errorTraceID":"trace-send"}""", MediaType.APPLICATION_JSON))

        assertThatCode { provider(builder).requestOtp("+77011112233", null) }.doesNotThrowAnyException()
        server.verify()
    }

    @Test
    fun `request status error превращает в SMS_SEND_FAILED`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("https://gateway.test/api/v2/sms"))
            .andRespond(withSuccess("""{"status":"error","code":"G500","errorTraceID":"trace"}""", MediaType.APPLICATION_JSON))

        assertThatThrownBy { provider(builder).requestOtp("+77011112233", null) }
            .isInstanceOf(AppException::class.java)
            .extracting("code").isEqualTo(ErrorCode.SMS_SEND_FAILED)
        server.verify()
    }

    @Test
    fun `request HTTP 5xx превращает в SMS_SEND_FAILED`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("https://gateway.test/api/v2/sms")).andRespond(withServerError())

        assertThatThrownBy { provider(builder).requestOtp("+77011112233", null) }
            .isInstanceOf(AppException::class.java)
            .extracting("code").isEqualTo(ErrorCode.SMS_SEND_FAILED)
        server.verify()
    }

    @Test
    fun `verify отправляет validation_code и принимает только success с access token`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("https://gateway.test/api/v2/auth"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.phone").value("77011112233"))
            .andExpect(jsonPath("$.validation_code").value("123456"))
            .andExpect(jsonPath("$.sms_type").doesNotExist())
            .andRespond(
                withSuccess(
                    """{"status":"success","result":{"access_token":"external-token","refresh_token":"refresh"}}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        assertThat(provider(builder).verifyOtp("+77011112233", "123456"))
            .isEqualTo(OtpVerificationResult.VERIFIED)
        server.verify()
    }

    @Test
    fun `verify HTTP 400 с неверным кодом возвращает INVALID`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("https://gateway.test/api/v2/auth"))
            .andRespond(
                withStatus(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("""{"status":"error","code":"G400001","error":"invalid validation code"}"""),
            )

        assertThat(provider(builder).verifyOtp("+77011112233", "000000"))
            .isEqualTo(OtpVerificationResult.INVALID)
        server.verify()
    }

    @Test
    fun `verify HTTP 200 со status error не считается успешной авторизацией`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("https://gateway.test/api/v2/auth"))
            .andRespond(
                withSuccess(
                    """{"status":"error","code":"G400001","error":"invalid validation code"}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        assertThat(provider(builder).verifyOtp("+77011112233", "000000"))
            .isEqualTo(OtpVerificationResult.INVALID)
        server.verify()
    }

    @Test
    fun `verify expired от gateway возвращает EXPIRED`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("https://gateway.test/api/v2/auth"))
            .andRespond(
                withStatus(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("""{"status":"error","code":"G400000","error":"validation code expired"}"""),
            )

        assertThat(provider(builder).verifyOtp("+77011112233", "000000"))
            .isEqualTo(OtpVerificationResult.EXPIRED)
        server.verify()
    }

    @Test
    fun `verify HTTP 5xx не считается неверным кодом`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("https://gateway.test/api/v2/auth")).andRespond(withServerError())

        assertThatThrownBy { provider(builder).verifyOtp("+77011112233", "123456") }
            .isInstanceOf(AppException::class.java)
            .extracting("code").isEqualTo(ErrorCode.OTP_PROVIDER_UNAVAILABLE)
        server.verify()
    }

    @Test
    fun `verify success без токена считается повреждённым ответом`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("https://gateway.test/api/v2/auth"))
            .andRespond(withSuccess("""{"status":"success","result":{}}""", MediaType.APPLICATION_JSON))

        assertThatThrownBy { provider(builder).verifyOtp("+77011112233", "123456") }
            .isInstanceOf(AppException::class.java)
            .extracting("code").isEqualTo(ErrorCode.OTP_PROVIDER_UNAVAILABLE)
        server.verify()
    }

    @Test
    fun `gateway не вызывается для номера не из 11 цифр`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()

        assertThatThrownBy { provider(builder).requestOtp("+123", null) }
            .isInstanceOf(AppException::class.java)
            .extracting("code").isEqualTo(ErrorCode.VALIDATION_FAILED)
        server.verify()
    }
}
