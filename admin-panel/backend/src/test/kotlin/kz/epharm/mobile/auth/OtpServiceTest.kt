package kz.epharm.mobile.auth

import kz.epharm.mobile.auth.repository.MobileOtpRepository
import kz.epharm.mobile.auth.service.OtpService
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Duration
import java.time.Instant

/**
 * Прямой тест OtpService на реальной БД (Testcontainers). НЕ @Transactional — каждый
 * @Transactional-вызов сервиса коммитится отдельно (важно для проверки накопления attempts).
 * Время контролируем через параметр now — без sleep'ов.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class OtpServiceTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("epharm_test")
            .withUsername("epharm")
            .withPassword("epharm_test")
            .apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun props(reg: DynamicPropertyRegistry) {
            reg.add("spring.datasource.url") { postgres.jdbcUrl }
            reg.add("spring.datasource.username") { postgres.username }
            reg.add("spring.datasource.password") { postgres.password }
        }
    }

    @Autowired private lateinit var otpService: OtpService
    @Autowired private lateinit var otpRepository: MobileOtpRepository

    private val phone = "+77011112233"

    @BeforeEach
    fun clean() {
        otpRepository.deleteAll()
    }

    @Test
    fun `request в dev-режиме отдаёт фиксированный код и пишет строку`() {
        val r = otpService.request(phone)
        assertThat(r.devCode).isEqualTo("5445")
        assertThat(otpRepository.findById(phone)).isPresent
        assertThat(otpRepository.findById(phone).get().verificationProvider).isEqualTo("dev")
    }

    @Test
    fun `verify правильным кодом ставит verified_at`() {
        otpService.request(phone)
        otpService.verify(phone, "5445")
        assertThat(otpRepository.findById(phone).get().verifiedAt).isNotNull
    }

    @Test
    fun `verify неверным кодом инкрементирует attempts и бросает OTP_INVALID`() {
        otpService.request(phone)
        assertThatThrownBy { otpService.verify(phone, "000000") }
            .isInstanceOf(AppException::class.java)
            .extracting("code").isEqualTo(ErrorCode.OTP_INVALID)
        // Инкремент должен СОХРАНИТЬСЯ несмотря на throw (noRollbackFor) — иначе брутфорс не считается.
        assertThat(otpRepository.findById(phone).get().attempts).isEqualTo(1)
    }

    @Test
    fun `verify без request бросает OTP_NOT_REQUESTED`() {
        assertThatThrownBy { otpService.verify(phone, "5445") }
            .isInstanceOf(AppException::class.java)
            .extracting("code").isEqualTo(ErrorCode.OTP_NOT_REQUESTED)
    }

    @Test
    fun `истёкший код бросает OTP_EXPIRED`() {
        val t0 = Instant.parse("2026-06-09T10:00:00Z")
        otpService.request(phone, now = t0)
        // ttl=300с → через 301с код истёк.
        assertThatThrownBy { otpService.verify(phone, "5445", now = t0.plus(Duration.ofSeconds(301))) }
            .isInstanceOf(AppException::class.java)
            .extracting("code").isEqualTo(ErrorCode.OTP_EXPIRED)
    }

    @Test
    fun `после max попыток бросает OTP_TOO_MANY_ATTEMPTS`() {
        otpService.request(phone)
        // 5 неверных попыток (max-attempts=5) — каждая инкрементит attempts.
        repeat(5) {
            assertThatThrownBy { otpService.verify(phone, "000000") }
                .extracting("code").isEqualTo(ErrorCode.OTP_INVALID)
        }
        // 6-я — уже отбивается лимитом ДО сравнения кода.
        assertThatThrownBy { otpService.verify(phone, "5445") }
            .isInstanceOf(AppException::class.java)
            .extracting("code").isEqualTo(ErrorCode.OTP_TOO_MANY_ATTEMPTS)
    }

    @Test
    fun `requireVerified ок в окне регистрации`() {
        val t0 = Instant.parse("2026-06-09T10:00:00Z")
        otpService.request(phone, now = t0)
        otpService.verify(phone, "5445", now = t0)
        // В пределах окна (register-window=900с) — не бросает.
        otpService.requireVerified(phone, now = t0.plus(Duration.ofSeconds(300)))
    }

    @Test
    fun `requireVerified вне окна бросает OTP_NOT_VERIFIED`() {
        val t0 = Instant.parse("2026-06-09T10:00:00Z")
        otpService.request(phone, now = t0)
        otpService.verify(phone, "5445", now = t0)
        assertThatThrownBy { otpService.requireVerified(phone, now = t0.plus(Duration.ofSeconds(901))) }
            .isInstanceOf(AppException::class.java)
            .extracting("code").isEqualTo(ErrorCode.OTP_NOT_VERIFIED)
    }

    @Test
    fun `requireVerified без verify бросает OTP_NOT_VERIFIED`() {
        otpService.request(phone)
        assertThatThrownBy { otpService.requireVerified(phone) }
            .isInstanceOf(AppException::class.java)
            .extracting("code").isEqualTo(ErrorCode.OTP_NOT_VERIFIED)
    }

    @Test
    fun `consume удаляет строку`() {
        otpService.request(phone)
        otpService.consume(phone)
        assertThat(otpRepository.findById(phone)).isEmpty
    }

    // ── Кулдаун повторной отправки (анти-спам: SMS платные) ──────────────────

    @Test
    fun `повторный request раньше кулдауна бросает OTP_RESEND_TOO_SOON`() {
        val t0 = Instant.parse("2026-06-09T10:00:00Z")
        otpService.request(phone, now = t0)
        // cooldown=60с (дефолт) → повтор через 30с отбивается.
        assertThatThrownBy { otpService.request(phone, now = t0.plus(Duration.ofSeconds(30))) }
            .isInstanceOf(AppException::class.java)
            .extracting("code").isEqualTo(ErrorCode.OTP_RESEND_TOO_SOON)
    }

    @Test
    fun `повторный request после кулдауна проходит`() {
        val t0 = Instant.parse("2026-06-09T10:00:00Z")
        otpService.request(phone, now = t0)
        val r = otpService.request(phone, now = t0.plus(Duration.ofSeconds(61)))
        assertThat(r.devCode).isEqualTo("5445")
    }

    @Test
    fun `после verify кулдаун не мешает новому циклу входа`() {
        val t0 = Instant.parse("2026-06-09T10:00:00Z")
        otpService.request(phone, now = t0)
        otpService.verify(phone, "5445", now = t0)
        // Строка verified → повторный запрос сразу разрешён (новый вход).
        assertThatCode { otpService.request(phone, now = t0.plus(Duration.ofSeconds(5))) }
            .doesNotThrowAnyException()
    }
}
