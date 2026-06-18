package kz.epharm.auth

import kz.epharm.ai_exam.repository.ExamQuestionRepository
import kz.epharm.auth.repository.AdminUserRepository
import kz.epharm.banners.repository.BannerRepository
import kz.epharm.catalog.repository.ProductRepository
import kz.epharm.finance.repository.PayoutBatchRepository
import kz.epharm.finance.repository.PayoutItemRepository
import kz.epharm.lms.repository.CourseRepository
import kz.epharm.pharmacies.repository.ChainRepository
import kz.epharm.pharmacies.repository.PharmacyRepository
import kz.epharm.pharmacists.repository.PharmacistRepository
import kz.epharm.promo.repository.PromoRepository
import kz.epharm.receipts.repository.PendingBonusRepository
import kz.epharm.receipts.repository.ReceiptRepository
import kz.epharm.rules.repository.RuleRepository
import kz.epharm.screens.repository.PlaylistRepository
import kz.epharm.screens.repository.SlideRepository
import org.springframework.context.annotation.Profile
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Dev-only утилиты. Существует ТОЛЬКО в profile=dev (в prod бин не создаётся,
 * путь отдаёт 404). Используется E2E (Playwright globalSetup) для детерминированного
 * состояния: POST /api/admin/dev/reset возвращает БД к seed-базису перед прогоном,
 * чтобы мутации прошлых прогонов не делали тесты с count-ассертами flaky.
 *
 * Путь permitAll в SecurityConfig — авторизация не нужна (а в prod эндпоинта нет).
 */
@RestController
@RequestMapping("/api/admin/dev")
@Profile("dev")
class DevController(
    private val seeder: DevDataSeeder,
    private val adminUserRepository: AdminUserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val productRepository: ProductRepository,
    private val ruleRepository: RuleRepository,
    private val promoRepository: PromoRepository,
    private val chainRepository: ChainRepository,
    private val pharmacyRepository: PharmacyRepository,
    private val pharmacistRepository: PharmacistRepository,
    private val payoutBatchRepository: PayoutBatchRepository,
    private val payoutItemRepository: PayoutItemRepository,
    private val courseRepository: CourseRepository,
    private val playlistRepository: PlaylistRepository,
    private val slideRepository: SlideRepository,
    private val examQuestionRepository: ExamQuestionRepository,
    private val pendingBonusRepository: PendingBonusRepository,
    private val receiptRepository: ReceiptRepository,
    private val bannerRepository: BannerRepository,
) {

    @PostMapping("/reset")
    @Transactional
    fun reset(): Map<String, Any> {
        seeder.resetAndReseed(
            adminUserRepository = adminUserRepository,
            passwordEncoder = passwordEncoder,
            productRepository = productRepository,
            ruleRepository = ruleRepository,
            promoRepository = promoRepository,
            chainRepository = chainRepository,
            pharmacyRepository = pharmacyRepository,
            pharmacistRepository = pharmacistRepository,
            payoutBatchRepository = payoutBatchRepository,
            payoutItemRepository = payoutItemRepository,
            courseRepository = courseRepository,
            playlistRepository = playlistRepository,
            slideRepository = slideRepository,
            examQuestionRepository = examQuestionRepository,
            pendingBonusRepository = pendingBonusRepository,
            receiptRepository = receiptRepository,
            bannerRepository = bannerRepository,
        )
        return mapOf(
            "status" to "reseeded",
            "products" to productRepository.count(),
            "rules" to ruleRepository.count(),
            "promos" to promoRepository.count(),
            "pharmacies" to pharmacyRepository.count(),
            "receipts" to receiptRepository.count(),
        )
    }
}
