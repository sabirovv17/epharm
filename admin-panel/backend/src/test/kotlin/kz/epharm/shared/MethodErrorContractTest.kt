package kz.epharm.shared

import kz.epharm.shared.error.ErrorCode
import kz.epharm.shared.error.GlobalExceptionHandler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.web.HttpRequestMethodNotSupportedException

class MethodErrorContractTest {
    @Test fun `unsupported method is a bounded client error with supported verbs`() {
        val response = GlobalExceptionHandler().handleMethodNotAllowed(
            HttpRequestMethodNotSupportedException("POST", listOf("GET", "PUT")))
        assertThat(response.statusCode.value()).isEqualTo(405)
        assertThat(response.headers.allow).containsExactlyInAnyOrder(HttpMethod.GET, HttpMethod.PUT)
        assertThat(response.body!!.code).isEqualTo(ErrorCode.VALIDATION_FAILED)
        assertThat(response.body!!.message).doesNotContain("Exception", "stack", "POST")
    }
}
