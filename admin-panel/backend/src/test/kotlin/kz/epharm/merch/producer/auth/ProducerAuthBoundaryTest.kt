package kz.epharm.merch.producer.auth

import io.mockk.*
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.entity.AdminUserEntity
import kz.epharm.auth.service.JwtService
import kz.epharm.mobile.auth.service.OtpProvider
import kz.epharm.posm.staff.StaffPhoneLock
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.time.Instant
import java.util.UUID
import io.jsonwebtoken.Jwts
import java.util.Date
import javax.crypto.spec.SecretKeySpec

class ProducerAuthBoundaryTest {
    private val secret="synthetic-producer-test-secret-at-least-thirty-two-bytes"
    @Test fun `producer JWT purpose never parses as administrator or mobile even for the same subject`() {
        val codec=ProducerTokenCodec(secret);val legacy=JwtService(secret,15)
        val principal=ProducerPrincipal(UUID.randomUUID(),UUID.randomUUID());val now=Instant.now()
        val producer=codec.issue(principal,now,now.plusSeconds(3600))
        assertThat(codec.parse(producer)).isEqualTo(principal)
        assertThat(legacy.parseAccessToken(producer)).isNull()
        assertThat(codec.parse(legacy.issueAccessToken(AdminUserEntity(id=principal.customerId,email="synthetic@example.test",name="Synthetic",roleRaw=AdminRole.HQ_HEAD.name)))).isNull()
        assertThat(codec.parse(legacy.issuePharmacistToken(principal.customerId.toString(),"Synthetic","+77000000001"))).isNull()
        assertThat(codec.parse(codec.issue(principal,now.minusSeconds(1000),now.minusSeconds(1)))).isNull()
    }
    @Test fun `disabled producer authentication performs no database or SMS action`() {
        val jdbc=mockk<JdbcTemplate>();val otp=mockk<OtpProvider>();val lock=mockk<StaffPhoneLock>();val codec=mockk<ProducerTokenCodec>()
        val service=ProducerAuthService(jdbc,otp,lock,codec,false)
        assertThatThrownBy {service.requestOtp(ProducerSmsRequest("+77000000001"),"127.0.0.1")}
            .isInstanceOfSatisfying(AppException::class.java){assertThat(it.code).isEqualTo(ErrorCode.NOT_FOUND)}
        verify {listOf(jdbc,otp,lock,codec) wasNot Called}
        val sessions=mockk<ProducerAuthService>()
        ProducerAuthenticationFilter(sessions,false).doFilter(MockHttpServletRequest("GET","/api/producer/auth/me"),MockHttpServletResponse(),MockFilterChain())
        verify {sessions wasNot Called}
    }
    @Test fun `signed malformed producer claims fail closed without becoming a server error`() {
        val token=Jwts.builder().claim("typ","merch_producer").claim("sid",UUID.randomUUID().toString())
            .issuer("https://epharm.inkar.kz/producer").audience().add("epharm-merch-producer").and()
            .issuedAt(Date()).expiration(Date.from(Instant.now().plusSeconds(900)))
            .signWith(SecretKeySpec(secret.toByteArray(),"HmacSHA256"),Jwts.SIG.HS256).compact()
        assertThat(ProducerTokenCodec(secret).parse(token)).isNull()
    }
}
