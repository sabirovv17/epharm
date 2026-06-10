package kz.epharm.shared

import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.domain.AdminUserStatus
import kz.epharm.auth.entity.AdminUserEntity
import kz.epharm.auth.repository.AdminUserRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.core.annotation.Order
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component

/**
 * Прод-инициализация (только профиль prod):
 *  1) fail-fast: не стартуем на дефолтных dev-секретах (JWT_SECRET / POSM_DEVICE_KEY) —
 *     иначе кто угодно может подделать токены/прикинуться кассой;
 *  2) bootstrap первого admin-аккаунта HQ-консоли из env, если таблица пуста —
 *     иначе в прод-консоль вообще не войти (DevDataSeeder только под profile=dev).
 *
 * dev/test это НЕ затрагивает: бин существует только при profile=prod.
 */
@Component
@Profile("prod")
@Order(0)
class ProdBootstrap(
    private val adminUserRepository: AdminUserRepository,
    private val passwordEncoder: PasswordEncoder,
    @Value("\${app.jwt.secret}") private val jwtSecret: String,
    @Value("\${app.posm.device-key}") private val posmDeviceKey: String,
    @Value("\${app.bootstrap.admin-email:}") private val bootstrapEmail: String,
    @Value("\${app.bootstrap.admin-password:}") private val bootstrapPassword: String,
    @Value("\${app.bootstrap.admin-name:HQ Admin}") private val bootstrapName: String,
    @Value("\${app.bootstrap.admin-company:Inkar}") private val bootstrapCompany: String,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(ProdBootstrap::class.java)

    override fun run(args: ApplicationArguments) {
        // 1) Дефолтные секреты в проде — это дыра. Падаем на старте с понятным сообщением.
        require(jwtSecret != DEV_JWT_DEFAULT) {
            "JWT_SECRET не задан (используется dev-дефолт). Сгенерируй уникальный " +
                "(openssl rand -base64 48) и задай в .env.prod."
        }
        require(posmDeviceKey != DEV_POSM_DEFAULT) {
            "POSM_DEVICE_KEY не задан (используется dev-дефолт). Задай уникальный в .env.prod."
        }

        // 2) Bootstrap первого админа, если консоль ещё без учёток.
        if (adminUserRepository.count() > 0L) return
        if (bootstrapEmail.isBlank() || bootstrapPassword.isBlank()) {
            log.warn(
                "admin_users пуста, а ADMIN_BOOTSTRAP_EMAIL/ADMIN_BOOTSTRAP_PASSWORD не заданы — " +
                    "в HQ-консоль войти НЕЛЬЗЯ. Задай их в .env.prod и перезапусти.",
            )
            return
        }
        val admin = AdminUserEntity(
            email = bootstrapEmail.trim(),
            passwordHash = passwordEncoder.encode(bootstrapPassword),
            name = bootstrapName,
            company = bootstrapCompany,
        ).also {
            it.role = AdminRole.HQ_HEAD
            it.status = AdminUserStatus.ACTIVE
        }
        adminUserRepository.save(admin)
        log.info("Bootstrap: создан первый admin-аккаунт {} (роль HQ_HEAD)", admin.email)
    }

    companion object {
        // Должны точно совпадать с дефолтами в application.yml.
        private const val DEV_JWT_DEFAULT =
            "dev-secret-change-me-please-very-long-key-for-hs256-algo-min-256-bits-required"
        private const val DEV_POSM_DEFAULT = "dev-posm-key"
    }
}
