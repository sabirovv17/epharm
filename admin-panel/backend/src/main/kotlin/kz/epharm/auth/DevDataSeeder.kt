package kz.epharm.auth

import kz.epharm.shared.IinUtil
import kz.epharm.shared.PhoneUtil
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.domain.AdminUserStatus
import kz.epharm.auth.entity.AdminUserEntity
import kz.epharm.auth.repository.AdminUserRepository
import kz.epharm.banners.entity.BannerEntity
import kz.epharm.banners.entity.BannerStatus
import kz.epharm.banners.repository.BannerRepository
import kz.epharm.catalog.entity.ProductEntity
import kz.epharm.catalog.repository.ProductRepository
import kz.epharm.finance.entity.PayoutBatchEntity
import kz.epharm.finance.entity.PayoutBatchStatus
import kz.epharm.finance.entity.PayoutItemEntity
import kz.epharm.finance.repository.PayoutBatchRepository
import kz.epharm.finance.repository.PayoutItemRepository
import kz.epharm.lms.entity.CourseEntity
import kz.epharm.lms.entity.CourseStatus
import kz.epharm.lms.repository.CourseRepository
import kz.epharm.screens.entity.PlaylistEntity
import kz.epharm.screens.entity.PlaylistStatus
import kz.epharm.screens.entity.SlideEntity
import kz.epharm.screens.entity.SlideKind
import kz.epharm.screens.repository.PlaylistRepository
import kz.epharm.screens.repository.SlideRepository
import kz.epharm.ai_exam.entity.ExamQuestionEntity
import kz.epharm.ai_exam.entity.ExamQuestionKind
import kz.epharm.ai_exam.repository.ExamQuestionRepository
import kz.epharm.pharmacies.entity.ChainEntity
import kz.epharm.pharmacies.entity.PharmacyEntity
import kz.epharm.pharmacies.entity.PharmacyGroup
import kz.epharm.pharmacies.RealPharmacySeeder
import kz.epharm.pharmacies.repository.ChainRepository
import kz.epharm.pharmacies.repository.PharmacyRepository
import kz.epharm.pharmacists.entity.PharmacistEntity
import kz.epharm.pharmacists.entity.PharmacistStatus
import kz.epharm.pharmacists.entity.PharmacistTier
import kz.epharm.pharmacists.repository.PharmacistRepository
import kz.epharm.promo.entity.PromoEntity
import kz.epharm.promo.entity.PromoStatus
import kz.epharm.promo.repository.PromoRepository
import kz.epharm.receipts.entity.PendingBonusEntity
import kz.epharm.receipts.entity.PendingBonusStatus
import kz.epharm.receipts.entity.ReceiptEntity
import kz.epharm.receipts.entity.ReceiptStatus
import kz.epharm.receipts.repository.PendingBonusRepository
import kz.epharm.receipts.repository.ReceiptRepository
import kz.epharm.rules.entity.RuleAbTest
import kz.epharm.rules.entity.RuleCard
import kz.epharm.rules.entity.RuleComparisonRow
import kz.epharm.rules.entity.RuleEntity
import kz.epharm.rules.entity.RuleStatus
import kz.epharm.rules.entity.RuleTrigger
import kz.epharm.rules.entity.RuleType
import kz.epharm.rules.repository.RuleRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.security.crypto.password.PasswordEncoder
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Сидер dev-окружения: 3 admin-аккаунта + 13 продуктов + 6 правил.
 * Идемпотентный: при повторном bootRun уже существующие записи не пересоздаются.
 * Только в profile=dev. В prod / test данных нет.
 *
 * Источник фикстур — admin-panel/references/data.jsx (canonical UX-spec).
 */
@Configuration
@Profile("dev")
class DevDataSeeder {

    private val log = LoggerFactory.getLogger(DevDataSeeder::class.java)

    /**
     * Один ApplicationRunner — гарантия что products засеются раньше rules
     * (FK rules.recommend → products.id). При раздельных `@Bean ApplicationRunner`
     * Spring выполняет их в произвольном порядке.
     */
    @Bean
    fun seedAll(
        adminUserRepository: AdminUserRepository,
        passwordEncoder: PasswordEncoder,
        productRepository: ProductRepository,
        ruleRepository: RuleRepository,
        promoRepository: PromoRepository,
        chainRepository: ChainRepository,
        pharmacyRepository: PharmacyRepository,
        pharmacistRepository: PharmacistRepository,
        payoutBatchRepository: PayoutBatchRepository,
        payoutItemRepository: PayoutItemRepository,
        courseRepository: CourseRepository,
        playlistRepository: PlaylistRepository,
        slideRepository: SlideRepository,
        examQuestionRepository: ExamQuestionRepository,
        pendingBonusRepository: PendingBonusRepository,
        receiptRepository: ReceiptRepository,
        bannerRepository: BannerRepository,
    ): ApplicationRunner = ApplicationRunner {
        seedAdminsImpl(adminUserRepository, passwordEncoder)
        seedProductsImpl(productRepository)
        seedRulesImpl(ruleRepository)
        seedPromosImpl(promoRepository)
        // Реальный реестр аптек (≈523 из витрины inkar.kz, см. RealPharmacySeeder) вместо
        // демо-генерации. Идемпотентно. seedChainsImpl/seedPharmaciesImpl больше не вызываются.
        RealPharmacySeeder.seed(chainRepository, pharmacyRepository)
        seedPharmacistsImpl(pharmacistRepository, pharmacyRepository)
        seedPayoutsImpl(payoutBatchRepository, payoutItemRepository, pharmacistRepository)
        seedCoursesImpl(courseRepository)
        seedScreensImpl(playlistRepository, slideRepository)
        seedExamQuestionsImpl(examQuestionRepository)
        seedReceiptsImpl(pendingBonusRepository, receiptRepository, pharmacistRepository)
        seedBannersImpl(bannerRepository)
    }

    /**
     * Сброс изменяемых доменов к seed-базису. Используется dev-only reset-эндпоинтом
     * (DevController) для детерминированного E2E-состояния: каждый прогон стартует
     * с идентичной фикстуры, мутации прошлых прогонов (созданные промо, заархивированные
     * правила, новые аптеки) не копятся.
     *
     * Админы НЕ удаляются — иначе сломается login-фикстура E2E. Удаление идёт в
     * FK-безопасном порядке (зависимые → родительские). После очистки повторно
     * вызываются те же seedXxxImpl (счётчики == 0 → сидируют заново).
     */
    fun resetAndReseed(
        adminUserRepository: AdminUserRepository,
        passwordEncoder: PasswordEncoder,
        productRepository: ProductRepository,
        ruleRepository: RuleRepository,
        promoRepository: PromoRepository,
        chainRepository: ChainRepository,
        pharmacyRepository: PharmacyRepository,
        pharmacistRepository: PharmacistRepository,
        payoutBatchRepository: PayoutBatchRepository,
        payoutItemRepository: PayoutItemRepository,
        courseRepository: CourseRepository,
        playlistRepository: PlaylistRepository,
        slideRepository: SlideRepository,
        examQuestionRepository: ExamQuestionRepository,
        pendingBonusRepository: PendingBonusRepository,
        receiptRepository: ReceiptRepository,
        bannerRepository: BannerRepository,
    ) {
        // 1. Очистка в FK-безопасном порядке (зависимые сначала).
        receiptRepository.deleteAll() // FK → pending_bonuses
        pendingBonusRepository.deleteAll()
        bannerRepository.deleteAll() // баннеры ни от чего не зависят
        payoutItemRepository.deleteAll()
        payoutBatchRepository.deleteAll()
        pharmacistRepository.deleteAll()
        pharmacyRepository.deleteAll()
        chainRepository.deleteAll()
        slideRepository.deleteAll()
        playlistRepository.deleteAll()
        ruleRepository.deleteAll()
        promoRepository.deleteAll()
        courseRepository.deleteAll()
        examQuestionRepository.deleteAll()
        productRepository.deleteAll()

        // 2. Повторный сид (счётчики == 0 → создаются заново). Админы идемпотентны.
        seedAdminsImpl(adminUserRepository, passwordEncoder)
        seedProductsImpl(productRepository)
        seedRulesImpl(ruleRepository)
        seedPromosImpl(promoRepository)
        // Реальный реестр аптек (≈523 из витрины inkar.kz, см. RealPharmacySeeder) вместо
        // демо-генерации. Идемпотентно. seedChainsImpl/seedPharmaciesImpl больше не вызываются.
        RealPharmacySeeder.seed(chainRepository, pharmacyRepository)
        seedPharmacistsImpl(pharmacistRepository, pharmacyRepository)
        seedPayoutsImpl(payoutBatchRepository, payoutItemRepository, pharmacistRepository)
        seedCoursesImpl(courseRepository)
        seedScreensImpl(playlistRepository, slideRepository)
        seedExamQuestionsImpl(examQuestionRepository)
        seedReceiptsImpl(pendingBonusRepository, receiptRepository, pharmacistRepository)
        seedBannersImpl(bannerRepository)
        log.info("DevDataSeeder.resetAndReseed: dev-данные сброшены к seed-базису")
    }

    /**
     * Демо-баннеры главного экрана (п.5): 3 активных карточки. Лента мобилки берёт
     * status=active ORDER BY position; админка управляет ими в разделе «Баннеры».
     */
    private fun seedBannersImpl(bannerRepository: BannerRepository) {
        if (bannerRepository.count() > 0L) {
            log.info("Banners already seeded — skip")
            return
        }
        bannerRepository.saveAll(
            listOf(
                BannerEntity(
                    id = "bn_welcome",
                    title = "Добро пожаловать в Epharm",
                    subtitle = "Бонусы за рекомендации любимых брендов",
                    imageUrl = "https://images.unsplash.com/photo-1587854692152-cbe660dbde88?w=1200",
                    detailMd = "## Как это работает\n\nРекомендуйте товары на кассе — получайте бонусы. " +
                        "Загружайте чеки в приложении и следите за балансом.",
                    position = 0,
                ).also { it.status = BannerStatus.active },
                BannerEntity(
                    id = "bn_aqua",
                    title = "Майский марафон Аквамарис",
                    subtitle = "Повышенный бонус за каждую упаковку",
                    imageUrl = "https://images.unsplash.com/photo-1576602976047-174e57a47881?w=1200",
                    detailMd = "## Майский марафон\n\nС 1 по 31 мая — повышенный бонус за рекомендацию линейки " +
                        "Аквамарис. Подробности у регионального менеджера.",
                    position = 1,
                ).also { it.status = BannerStatus.active },
                BannerEntity(
                    id = "bn_lms",
                    title = "Новые курсы в обучении",
                    subtitle = "Прокачайте знания и получите бонусы",
                    imageUrl = "https://images.unsplash.com/photo-1532938911079-1b06ac7ceec7?w=1200",
                    detailMd = "## Обучение\n\nДоступны новые курсы по назальным средствам и cross-sell. " +
                        "Прохождение курса начисляет бонусы на ваш баланс.",
                    position = 2,
                ).also { it.status = BannerStatus.active },
            ),
        )
        log.info("Seeded 3 demo banners")
    }

    /**
     * Демо-данные сверки чеков (ТЗ §3.5): несколько pending_bonuses от POSM + чеки
     * во всех 4 ветках (auto-approved / pending-модерация / flagged-фрод / rejected),
     * чтобы очередь сверки в админке была наполнена живыми записями.
     */
    private fun seedReceiptsImpl(
        pendingBonusRepository: PendingBonusRepository,
        receiptRepository: ReceiptRepository,
        pharmacistRepository: PharmacistRepository,
    ) {
        if (receiptRepository.count() > 0L || pendingBonusRepository.count() > 0L) {
            log.info("Receipts/pending bonuses present — skipping seed")
            return
        }
        val pharmacists = pharmacistRepository.findAll().take(6)
        if (pharmacists.isEmpty()) return
        val now = Instant.now()

        data class Demo(
            val sku: String, val product: String, val expected: Long, val bonus: Long,
            val status: ReceiptStatus, val auto: Boolean, val flag: String?, val parsedDelta: Long,
            val byLog: Boolean, val byExcel: Boolean,
        )
        // parsedDelta — отклонение суммы из источника от ожидаемой (для демонстрации ±2%).
        // byLog/byExcel — подтверждение источниками сверки (две галочки → авто-одобрение,
        // одна → ручная модерация, ноль → очередь/ждёт). Это и есть столбцы «Логи»/«Эксель».
        val demos = listOf(
            // Две галочки (лог + Excel) → авто-одобрение.
            Demo("p_aql_norm_s", "Аквалор Норм спрей", 1_620, 520, ReceiptStatus.approved, true, null, 0, byLog = true, byExcel = true),
            Demo("p_pinos", "Пиносол спрей", 1_820, 450, ReceiptStatus.approved, true, null, 10, byLog = true, byExcel = true),
            // Одна галочка (только лог) → ручная модерация менеджером.
            Demo("p_aqm_norm_s", "Аквамарис Норм", 1_480, 380, ReceiptStatus.moderation_required, false, null, 60, byLog = true, byExcel = false),
            // Ноль галочек → очередь, ждём источники / ручная проверка (самый редкий случай).
            Demo("p_pinos", "Пиносол спрей", 1_820, 450, ReceiptStatus.pending, false, null, 0, byLog = false, byExcel = false),
            // Анти-фрод.
            Demo("p_aql_norm_s", "Аквалор Норм спрей", 1_620, 520, ReceiptStatus.flagged, false, "duplicate_receipt", 0, byLog = true, byExcel = false),
            Demo("p_aqm_norm_s", "Аквамарис Норм", 1_480, 380, ReceiptStatus.flagged, false, "wrong_pharmacy", 0, byLog = false, byExcel = true),
            Demo("p_pinos", "Пиносол спрей", 1_820, 450, ReceiptStatus.rejected, false, "forged", 900, byLog = false, byExcel = false),
        )

        var n = 0
        demos.forEachIndexed { i, d ->
            val ph = pharmacists[i % pharmacists.size]
            val posmAt = now.minusSeconds((600 + i * 300).toLong())
            val pb = pendingBonusRepository.save(
                PendingBonusEntity(
                    id = "pb_demo_$i",
                    pharmacistId = ph.id, pharmacistName = ph.name,
                    pharmacyId = ph.pharmacyId ?: "", pharmacyName = ph.pharmacyName ?: "",
                    sku = d.sku, productName = d.product,
                    expectedAmount = d.expected, bonus = d.bonus,
                    ruleId = "r_demo_$i", createdAt = posmAt,
                ).also { it.status = if (d.status == ReceiptStatus.approved) PendingBonusStatus.matched else PendingBonusStatus.awaiting_receipt },
            )
            val credited = if (d.status == ReceiptStatus.approved) d.bonus else 0L
            receiptRepository.save(
                ReceiptEntity(
                    id = "rcp_demo_$i",
                    pharmacistId = ph.id, pharmacistName = ph.name,
                    pharmacyId = ph.pharmacyId ?: "", pharmacyName = ph.pharmacyName ?: "",
                    photoUrl = null,
                    fiscalId = "fp-demo-$i",
                    parsedSku = d.sku,
                    parsedAmount = d.expected + d.parsedDelta,
                    parsedCashier = "Кассир №${1 + i % 3}",
                    parsedAt = posmAt.plusSeconds(600),
                    pendingBonusId = pb.id,
                    bonusCredited = credited,
                    reviewer = if (d.status == ReceiptStatus.approved && d.auto) "auto" else null,
                    reviewedAt = if (d.status == ReceiptStatus.approved) posmAt.plusSeconds(620) else null,
                    confirmedByLog = d.byLog,
                    confirmedByExcel = d.byExcel,
                ).also { it.status = d.status; it.autoApproved = d.auto; it.flagReason = d.flag },
            )
            n++
        }
        log.info("Seeded {} demo receipts + pending bonuses", n)
    }

    private fun seedExamQuestionsImpl(examQuestionRepository: ExamQuestionRepository) {
        if (examQuestionRepository.count() > 0L) {
            log.info("Exam questions already seeded — skip")
            return
        }
        data class Q(val id: String, val prompt: String, val kind: ExamQuestionKind, val category: String, val keywords: List<String>, val difficulty: Int)
        val questions = listOf(
            Q("q_aqua_use", "В каких случаях рекомендуете Аквалор Норм спрей?", ExamQuestionKind.factual, "Назальные средства", listOf("насморк", "промывание", "гигиена", "изотонический"), 1),
            Q("q_aqua_vs", "Чем Аквамарис отличается от обычного физраствора?", ExamQuestionKind.comparative, "Назальные средства", listOf("микроэлементы", "морская вода", "стерильный"), 2),
            Q("q_probio", "Клиент берёт антибиотик. Что предложите дополнительно и почему?", ExamQuestionKind.scenario, "ЖКТ", listOf("пробиотик", "микрофлора", "дисбактериоз"), 3),
            Q("q_cross_eth", "Как предложить замену, не нарушая права выбора клиента?", ExamQuestionKind.scenario, "Продажи", listOf("аналог", "врач", "согласие", "альтернатива"), 3),
            Q("q_derma", "Назовите показания к применению дерматокосметики линейки.", ExamQuestionKind.factual, "Косметика", listOf("сухость", "барьер", "увлажнение"), 2),
        )
        examQuestionRepository.saveAll(
            questions.map {
                ExamQuestionEntity(
                    id = it.id, prompt = it.prompt, category = it.category,
                    keywords = it.keywords, difficulty = it.difficulty, createdBy = "seed",
                ).also { e -> e.kind = it.kind }
            },
        )
        log.info("Seeded {} exam questions", questions.size)
    }

    private fun seedScreensImpl(
        playlistRepository: PlaylistRepository,
        slideRepository: SlideRepository,
    ) {
        if (playlistRepository.count() > 0L) {
            log.info("Playlists already seeded — skip")
            return
        }
        playlistRepository.saveAll(
            listOf(
                PlaylistEntity(id = "pl_morning", name = "Утренняя ротация", slidesCount = 4, durationSec = 80, pharmacies = 24).also { it.status = PlaylistStatus.active },
                PlaylistEntity(id = "pl_promo", name = "Промо Аквалор (май)", slidesCount = 3, durationSec = 60, pharmacies = 12).also { it.status = PlaylistStatus.active },
                PlaylistEntity(id = "pl_draft", name = "Дерма-ролики (черновик)", slidesCount = 2, durationSec = 40, pharmacies = 0).also { it.status = PlaylistStatus.draft },
            ),
        )
        slideRepository.saveAll(
            listOf(
                SlideEntity(id = "sl_aqua", title = "Аквалор — линейка", durationSec = 20, mediaUrl = "s3://epharm-screens/aqua.mp4", playlistId = "pl_promo", position = 0).also { it.kind = SlideKind.video },
                SlideEntity(id = "sl_probio", title = "Пробиотики зимой", durationSec = 15, mediaUrl = "s3://epharm-screens/probio.jpg", playlistId = "pl_morning", position = 0).also { it.kind = SlideKind.image },
                SlideEntity(id = "sl_loyal", title = "Программа лояльности", durationSec = 25, mediaUrl = "s3://epharm-screens/loyal.mp4", playlistId = null, position = 0).also { it.kind = SlideKind.video },
            ),
        )
        log.info("Seeded 3 playlists + 3 slides")
    }

    private fun seedCoursesImpl(courseRepository: CourseRepository) {
        if (courseRepository.count() > 0L) {
            log.info("Courses already seeded — skip")
            return
        }
        data class C(
            val id: String, val title: String, val status: CourseStatus, val category: String,
            val lessons: Int, val durationMin: Int, val enrolled: Int, val completed: Int, val bonus: Int,
        )
        val courses = listOf(
            C("crs_aqua", "Линейка Аквалор: показания и замены", CourseStatus.published, "Назальные средства", 6, 45, 312, 188, 1500),
            C("crs_probio", "Пробиотики: как объяснить клиенту", CourseStatus.published, "ЖКТ", 4, 30, 240, 121, 1200),
            C("crs_cross", "Cross-sell на кассе без навязывания", CourseStatus.published, "Продажи", 5, 38, 198, 76, 2000),
            C("crs_derma", "Дерматокосметика: разбор брендов", CourseStatus.draft, "Косметика", 8, 70, 0, 0, 2500),
            C("crs_law", "Законодательство по выкладке", CourseStatus.draft, "Регуляторика", 3, 25, 0, 0, 800),
        )
        courseRepository.saveAll(
            courses.map {
                CourseEntity(
                    id = it.id, title = it.title, category = it.category, lessons = it.lessons,
                    durationMin = it.durationMin, enrolled = it.enrolled, completed = it.completed,
                    bonus = it.bonus, createdBy = "seed",
                ).also { e -> e.status = it.status }
            },
        )
        log.info("Seeded {} LMS courses", courses.size)
    }

    private fun seedAdminsImpl(
        adminUserRepository: AdminUserRepository,
        passwordEncoder: PasswordEncoder,
    ) {
        val accounts = listOf(
            DevAccount("damir@jadran.com",     "damir2026",     "Дамир Нурланов",   AdminRole.BRAND_MANAGER, "Jadran"),
            DevAccount("aigerim@inkar.kz",     "aigerim2026",   "Айгерим Сарсенова", AdminRole.CATEGORY_LEAD, "Inkar / Ledex"),
            DevAccount("bauyrzhan@inkar.kz",   "bauyrzhan2026", "Бауыржан Тлеуов",  AdminRole.HQ_HEAD,       "Inkar"),
        )
        var created = 0
        accounts.forEach { acc ->
            if (adminUserRepository.findByEmailIgnoreCase(acc.email).isEmpty) {
                val now = Instant.now()
                val entity = AdminUserEntity(
                    id = UUID.randomUUID(),
                    email = acc.email,
                    passwordHash = passwordEncoder.encode(acc.password),
                    name = acc.name,
                    company = acc.company,
                    createdAt = now,
                    updatedAt = now,
                ).also {
                    it.role = acc.role
                    it.status = AdminUserStatus.ACTIVE
                }
                adminUserRepository.save(entity)
                created++
                log.info("Seeded admin {} ({})", acc.email, acc.role)
            }
        }
        if (created == 0) log.info("Dev admins already present — skipping seed")
    }

    private fun seedProductsImpl(productRepository: ProductRepository) {
        val products = listOf(
            // Назальные/морская вода — главная категория правил Jadran
            ProductSeed("p_aqm_norm_s", "Аквамарис Норм спрей 30мл",     "Jadran-Galenski", "Jadran",    "Морская вода",   1480),
            ProductSeed("p_aqm_norm_d", "Аквамарис Норм капли 10мл",     "Jadran-Galenski", "Jadran",    "Морская вода",    980),
            ProductSeed("p_aqm_strong", "Аквамарис Стронг 30мл",         "Jadran-Galenski", "Jadran",    "Морская вода",   1820),
            ProductSeed("p_aqm_baby",   "Аквамарис Беби спрей 50мл",     "Jadran-Galenski", "Jadran",    "Морская вода",   2240),
            ProductSeed("p_aql_norm_s", "Аквалор Норм спрей 50мл",       "Aurena Labs",     "Aurena",    "Морская вода",   1620),
            ProductSeed("p_aql_baby_s", "Аквалор Беби спрей 50мл",       "Aurena Labs",     "Aurena",    "Морская вода",   2380),
            ProductSeed("p_rino_aqua",  "Риномарис аква 30мл",           "GlaxoSmithKline", "GSK",       "Морская вода",   1240),
            // Сосудосуживающие — типичный триггер cross-sell с Pinosol
            ProductSeed("p_otri_0_05",  "Отривин Бэби 0.05% 10мл",       "Novartis",        "Novartis",  "Ксилометазолин", 1380),
            ProductSeed("p_nazi_0_1",   "Називин 0.1% 10мл",             "Bayer",           "Bayer",     "Оксиметазолин",  1720),
            ProductSeed("p_iliad",      "Илиадин 0.05% спрей 10мл",      "Polpharma",       "Polpharma", "Оксиметазолин",  1480),
            // Восстановление слизистой / горло
            ProductSeed("p_pinos",      "Пиносол спрей назальный 10мл",  "Jadran-Galenski", "Jadran",    "Эфирные масла",  1820),
            ProductSeed("p_septolete",  "Септолете нео леденцы",         "KRKA",            "KRKA",      "Цетилпиридиний", 1640),
            ProductSeed("p_strepsils",  "Стрепсилс ментол №24",          "Reckitt",         "Reckitt",   "Амилметакрезол", 2120),
        )
        var created = 0
        products.forEach { p ->
            if (!productRepository.existsById(p.id)) {
                productRepository.save(
                    ProductEntity(
                        id = p.id, name = p.name, brand = p.brand, vendor = p.vendor,
                        mnn = p.mnn, price = p.price,
                    ),
                )
                created++
            }
        }
        // Объёмы для демо богатой карточки (Фаза 2) — показываются в строке «покупатель попросил».
        productRepository.findById("p_aql_norm_s").ifPresent { it.volume = "50 мл"; productRepository.save(it) }
        productRepository.findById("p_aqm_norm_s").ifPresent { it.volume = "30 мл"; productRepository.save(it) }

        if (created > 0) log.info("Seeded {} products", created)
        else log.info("Dev products already present — skipping seed")
    }

    private fun seedRulesImpl(ruleRepository: RuleRepository) {
        val rules = listOf(
            // ── Substitution ─────────────────────────────────────────────
            RuleSeed(
                id = "r_001", type = RuleType.substitution, status = RuleStatus.active,
                trigger = RuleTrigger(kind = "product", value = "p_aql_norm_s"),
                recommend = "p_aqm_norm_s", bonus = 520, pharmacies = 247,
                script = "«Аквамарис изотоничен и подходит для ежедневной гигиены. Сейчас на него действует бонус для аптеки — 520 ₸ за упаковку.»",
                advantages = listOf("Изотонический раствор (0.9%)", "Не вызывает привыкания", "Бонус фармацевту 520 ₸"),
                impressions = 847, accepts = 312, convRate = "36.80", revenue = 461_640, payout = 162_240,
            ),
            RuleSeed(
                id = "r_002", type = RuleType.substitution, status = RuleStatus.active,
                trigger = RuleTrigger(kind = "product", value = "p_aql_baby_s"),
                recommend = "p_aqm_baby", bonus = 680, pharmacies = 198,
                script = "«Беби-серия Аквамарис специально разработана для детей от 3 месяцев. С 1 мая бонус 680 ₸ за упаковку.»",
                advantages = listOf("Безопасно с 3 месяцев", "Анатомическая насадка-ограничитель", "Бонус 680 ₸"),
                abTest = RuleAbTest("B", 50),
                impressions = 524, accepts = 188, convRate = "35.90", revenue = 421_120, payout = 127_840,
            ),
            RuleSeed(
                id = "r_003", type = RuleType.substitution, status = RuleStatus.active,
                trigger = RuleTrigger(
                    kind = "mnn", value = "Морская вода",
                    exclude = listOf("p_aqm_norm_s", "p_aqm_baby", "p_aqm_norm_d", "p_aqm_strong"),
                ),
                recommend = "p_aqm_norm_s", bonus = 420, pharmacies = 312,
                script = "«Любой препарат на основе морской воды можно заменить на Аквамарис — действующее вещество идентично, а сегодня действует акция.»",
                advantages = listOf("Любая МНН-группа «морская вода»", "Маржа аптеки выше на 18%", "Бонус 420 ₸"),
                impressions = 1240, accepts = 421, convRate = "33.90", revenue = 623_080, payout = 176_820,
            ),
            RuleSeed(
                id = "r_004", type = RuleType.substitution, status = RuleStatus.paused,
                trigger = RuleTrigger(kind = "product", value = "p_rino_aqua"),
                recommend = "p_aqm_norm_d", bonus = 380, pharmacies = 64,
                script = "«Альтернатива в каплях — Аквамарис Норм 10 мл. Бонус 380 ₸.»",
                advantages = listOf("Капельная форма", "Бонус 380 ₸"),
                impressions = 218, accepts = 52, convRate = "23.90", revenue = 50_960, payout = 19_760,
            ),
            // ── Cross-sell ───────────────────────────────────────────────
            RuleSeed(
                id = "r_101", type = RuleType.crosssell, status = RuleStatus.active,
                trigger = RuleTrigger(kind = "mnn", value = "Ксилометазолин|Оксиметазолин"),
                recommend = "p_pinos", bonus = 320, pharmacies = 184,
                script = "«К сосудосуживающему рекомендуйте Пиносол — натуральные эфирные масла, восстанавливает слизистую после капель. Бонус 320 ₸.»",
                advantages = listOf("Натуральный состав", "Восстанавливает слизистую", "Совместим с любыми каплями", "Бонус 320 ₸"),
                impressions = 612, accepts = 174, convRate = "28.40", revenue = 316_680, payout = 55_680,
            ),
            RuleSeed(
                id = "r_102", type = RuleType.crosssell, status = RuleStatus.active,
                trigger = RuleTrigger(
                    kind = "product_any",
                    value = listOf("p_aqm_norm_s", "p_aqm_strong", "p_aqm_baby"),
                ),
                recommend = "p_pinos", bonus = 280, pharmacies = 286,
                script = "«К Аквамарису предложите Пиносол — закрывают разные задачи: гигиена + восстановление. Аптека получает оба бонуса.»",
                advantages = listOf("Дополняющий продукт", "Двойной бонус", "Бонус 280 ₸"),
                impressions = 1124, accepts = 248, convRate = "22.10", revenue = 451_360, payout = 69_440,
            ),
        )

        var created = 0
        rules.forEach { r ->
            if (!ruleRepository.existsById(r.id)) {
                val entity = RuleEntity(
                    id = r.id,
                    trigger = r.trigger,
                    recommend = r.recommend,
                    bonus = r.bonus,
                    script = r.script,
                    advantages = r.advantages,
                    abTest = r.abTest,
                    pharmacies = r.pharmacies,
                    impressions = r.impressions,
                    accepts = r.accepts,
                    convRate = BigDecimal(r.convRate),
                    revenue = r.revenue,
                    payout = r.payout,
                    createdBy = "dev-seeder",
                ).also {
                    it.type = r.type
                    it.status = r.status
                }
                ruleRepository.save(entity)
                created++
            }
        }
        if (created > 0) log.info("Seeded {} rules", created)
        else log.info("Dev rules already present — skipping seed")

        // Демо богатой карточки (Фаза 2): card на r_001 — чтобы live /recommend по p_aql_norm_s
        // показал полную карточку (сравнение, партнёр, цель). Идемпотентно по boot.
        ruleRepository.findById("r_001").ifPresent {
            it.card = RuleCard(
                partnerLabel = "ПАРТНЁР EPHARM",
                comparison = listOf(
                    RuleComparisonRow("Состав", "изотонический раствор", "✓ морская вода + микроэлементы", true),
                    RuleComparisonRow("Объём", "50 мл", "30 мл", false),
                    RuleComparisonRow("Применение", "гигиена носа", "✓ гигиена + увлажнение", true),
                ),
                goalLabel = "замен Аквамарис в мае",
                goalTarget = 10,
                goalBonus = 2000,
            )
            ruleRepository.save(it)
        }
    }

    private fun seedPromosImpl(promoRepository: PromoRepository) {
        val promos = listOf(
            PromoSeed("pr_001", "Майский марафон Аквамарис",       PromoStatus.active,
                brand = "Jadran-Galenski", period = "01.05 — 31.05",
                pharmacies = 312, budget = 3_000_000, spent = 1_842_300,
                kpi = "14 820 рекомендаций", cover = "#16C97A"),
            PromoSeed("pr_002", "Пиносол × Аквамарис: кросс",      PromoStatus.active,
                brand = "Jadran-Galenski", period = "15.05 — 15.06",
                pharmacies = 286, budget = 1_200_000, spent = 418_440,
                kpi = "2 240 cross-sell", cover = "#3DCDA2"),
            PromoSeed("pr_003", "Конкурс «Лучший фармацевт»",     PromoStatus.active,
                brand = "Jadran-Galenski", period = "01.05 — 31.05",
                pharmacies = 507, budget = 600_000, spent = 200_000,
                kpi = "48 участников", cover = "#F4B73A"),
            PromoSeed("pr_004", "Аквамарис Беби — летний сезон",   PromoStatus.draft,
                brand = "Jadran-Galenski", period = "01.06 — 31.08",
                pharmacies = 0, budget = 4_500_000, spent = 0,
                kpi = "—", cover = "#5560FB"),
            PromoSeed("pr_005", "Стронг-серия H1",                 PromoStatus.paused,
                brand = "Jadran-Galenski", period = "01.04 — 30.04",
                pharmacies = 142, budget = 1_800_000, spent = 1_624_000,
                kpi = "8 940 рекомендаций", cover = "#2A2BE2"),
        )

        var created = 0
        promos.forEach { p ->
            if (!promoRepository.existsById(p.id)) {
                val entity = PromoEntity(
                    id = p.id,
                    title = p.title,
                    brand = p.brand,
                    period = p.period,
                    pharmacies = p.pharmacies,
                    budget = p.budget,
                    spent = p.spent,
                    kpi = p.kpi,
                    cover = p.cover,
                    createdBy = "dev-seeder",
                ).also { it.status = p.status }
                promoRepository.save(entity)
                created++
            }
        }
        if (created > 0) log.info("Seeded {} promos", created)
        else log.info("Dev promos already present — skipping seed")
    }

    private fun seedChainsImpl(chainRepository: ChainRepository) {
        val chains = listOf(
            ChainSeed("europharma", "Europharma",    "#2A2BE2", 142, PharmacyGroup.pilot),
            ChainSeed("sadyhan",    "Садыхан",       "#16C97A", 88,  PharmacyGroup.rolled),
            ChainSeed("zerde",      "Зерде",         "#F4B73A", 76,  PharmacyGroup.pilot),
            ChainSeed("biosfera",   "Биосфера",      "#8B5CF6", 64,  PharmacyGroup.control),
            ChainSeed("kruglosut",  "Круглосуточная","#E5484D", 48,  PharmacyGroup.rolled),
            ChainSeed("farmis",     "Фармис",        "#0F8F55", 42,  PharmacyGroup.pilot),
            ChainSeed("apteka_36",  "Аптека 36",     "#5560FB", 38,  PharmacyGroup.control),
            ChainSeed("avicenna",   "Avicenna",      "#0F1424", 9,   PharmacyGroup.pilot),
        )
        var created = 0
        chains.forEach { c ->
            if (!chainRepository.existsById(c.id)) {
                chainRepository.save(
                    ChainEntity(id = c.id, name = c.name, color = c.color, points = c.points)
                        .also { it.group = c.group },
                )
                created++
            }
        }
        if (created > 0) log.info("Seeded {} chains", created)
    }

    private fun seedPharmaciesImpl(
        pharmacyRepository: PharmacyRepository,
        chainRepository: ChainRepository,
    ) {
        if (pharmacyRepository.count() > 0) {
            log.info("Pharmacies present — skipping seed")
            return
        }
        val cities = listOf("Алматы", "Астана", "Шымкент", "Караганда", "Актобе", "Атырау", "Усть-Каменогорск", "Павлодар")
        val districts = listOf("Бостандыкский", "Ауэзовский", "Алмалинский", "Медеуский", "Турксибский", "Жетысуский", "Сарыаркинский")
        val streets = listOf("Абая", "Толе би", "Сейфуллина", "Достык", "Назарбаева")
        val chains = chainRepository.findAll()
        var created = 0
        chains.forEach { chain ->
            for (i in 0 until 8) {
                val city = cities[(i + chain.points) % cities.size]
                val entity = PharmacyEntity(
                    id = "${chain.id}_$i",
                    name = "${chain.name} №${100 + i * 3}",
                    chainId = chain.id,
                    chainName = chain.name,
                    city = city,
                    district = districts[i % districts.size],
                    addr = "ул. ${streets[i % streets.size]}, ${10 + i * 7}",
                    pharmacists = 2 + (i % 4),
                    receipts30d = 200 + (i * 53) + chain.points * 4,
                    gmv30d = ((200 + i * 53 + chain.points * 4) * (3200 + i * 50)).toLong(),
                    liftPct = if (chain.group == PharmacyGroup.control) java.math.BigDecimal.ZERO
                             else java.math.BigDecimal(8 + (i % 18)),
                    rulesAccepted = if (chain.group == PharmacyGroup.control) 0 else 22 + (i * 4) % 60,
                    active = i % 7 != 0,
                ).also { it.group = chain.group }
                pharmacyRepository.save(entity)
                created++
            }
        }
        log.info("Seeded {} pharmacies across {} chains", created, chains.size)
    }

    private fun seedPharmacistsImpl(
        pharmacistRepository: PharmacistRepository,
        pharmacyRepository: PharmacyRepository,
    ) {
        if (pharmacistRepository.count() > 0) {
            log.info("Pharmacists present — skipping seed")
            return
        }
        val firstM = listOf("Алмас", "Бауыржан", "Даурен", "Ерлан", "Жасулан", "Канат", "Мурат", "Нурлан", "Рустам", "Тимур")
        val firstF = listOf("Айгерим", "Алия", "Балжан", "Гульнара", "Динара", "Жанар", "Куралай", "Мадина", "Сауле", "Толкын")
        val lastN  = listOf("Абдрахманова", "Бекенов", "Жумабаев", "Касенова", "Мухтарова", "Нурланов", "Оразов", "Сатпаева", "Турсунов", "Ыбраев")
        val pharmacies = pharmacyRepository.findAll()
        var created = 0
        for (i in 0 until 48) {
            val female = i % 5 != 0
            val fn = (if (female) firstF else firstM)[i % 10]
            val baseLn = lastN[i % 10]
            val ln = if (female && !baseLn.endsWith("а")) baseLn + "а" else baseLn
            val ph = pharmacies[i % pharmacies.size]
            val earned = 28_000L + ((i * 4271L) % 230_000L)
            val balance = earned - ((i * 1700L) % earned)
            // ИИН — детерминированный из индекса И валидный (формат + дата + контрольная сумма),
            // чтобы demo-данные проходили ту же проверку, что и реальный ввод (IinUtil).
            val iin = validSeedIin(i)
            // Телефоны храним в канонич. E.164 (PhoneUtil) — чтобы мобильный вход по номеру
            // (findByPhone после нормализации) находил seeded-записи независимо от форматирования.
            // i==0 — зарезервированный активный фармацевт для mobile-входа: phone +7 700 000 0001.
            val phone = if (i == 0) {
                "+77000000001"
            } else {
                PhoneUtil.normalize(
                    "+7 (7${10 + (i % 89)}) ${(100 + (i % 900)).toString().padStart(3, '0')}-${(10 + i % 89).toString().padStart(2, '0')}-${(20 + i % 79).toString().padStart(2, '0')}",
                )
            }
            val status = when {
                i == 0 -> PharmacistStatus.active // стабильный тестовый mobile-аккаунт
                i % 17 == 0 -> PharmacistStatus.blocked
                i % 11 == 0 -> PharmacistStatus.pending
                else -> PharmacistStatus.active
            }
            val tier = listOf(PharmacistTier.Silver, PharmacistTier.Gold, PharmacistTier.Platinum)[i % 3]
            val entity = PharmacistEntity(
                id = "u_$i",
                name = "$fn $ln",
                iin = iin,
                phone = phone,
                pharmacyId = ph.id,
                pharmacyName = ph.name,
                city = ph.city,
                receipts30d = 22 + (i * 7) % 240,
                rulesAccepted30d = 4 + (i * 3) % 60,
                earned30d = earned,
                balance = balance,
                coursesDone = i % 7,
                coursesTotal = 9,
                joinedAt = java.time.LocalDate.of(2026, 1 + (i % 5), 1 + (i * 3) % 28),
            ).also {
                it.tier = tier
                it.status = status
            }
            pharmacistRepository.save(entity)
            created++
        }
        log.info("Seeded {} pharmacists", created)
    }

    private fun seedPayoutsImpl(
        batchRepository: PayoutBatchRepository,
        itemRepository: PayoutItemRepository,
        pharmacistRepository: PharmacistRepository,
    ) {
        if (batchRepository.count() > 0) {
            log.info("Payout batches present — skipping seed")
            return
        }
        val pharmacists = pharmacistRepository.findAll()
        if (pharmacists.isEmpty()) {
            log.warn("No pharmacists — cannot seed payouts")
            return
        }

        // 4 батча: 1 pending + 3 approved (как в references)
        val batches = listOf(
            BatchSeed("pb_2026_05_15", "1–15 мая 2026", PayoutBatchStatus.pending,
                pharmacists = 312, amount = 4_180_400, items = 312, approvedAt = null),
            BatchSeed("pb_2026_04_30", "16–30 апреля 2026", PayoutBatchStatus.approved,
                pharmacists = 287, amount = 3_924_200, items = 287,
                approvedAt = Instant.parse("2026-05-02T10:00:00Z")),
            BatchSeed("pb_2026_04_15", "1–15 апреля 2026", PayoutBatchStatus.approved,
                pharmacists = 268, amount = 3_641_080, items = 268,
                approvedAt = Instant.parse("2026-04-17T10:00:00Z")),
            BatchSeed("pb_2026_03_30", "16–31 марта 2026", PayoutBatchStatus.approved,
                pharmacists = 254, amount = 3_488_240, items = 254,
                approvedAt = Instant.parse("2026-04-02T10:00:00Z")),
        )

        batches.forEach { b ->
            val entity = PayoutBatchEntity(
                id = b.id, period = b.period,
                pharmacists = b.pharmacists, amount = b.amount, items = b.items,
                reviewerId = null,
                reviewerName = if (b.status == PayoutBatchStatus.approved) "Айгерим Сарсенова" else null,
                approvedAt = b.approvedAt,
            ).also { it.status = b.status }
            batchRepository.save(entity)
        }

        // Items только для первого pending батча — 18 строк
        val firstBatch = batches.first()
        pharmacists.take(18).forEachIndexed { i, ph ->
            val item = PayoutItemEntity(
                id = "pi_${firstBatch.id}_$i",
                batchId = firstBatch.id,
                pharmacistId = ph.id,
                pharmacistName = ph.name,
                pharmacy = ph.pharmacyName ?: "",
                city = ph.city,
                receipts = 22 + (i * 7) % 240,
                rules = 4 + (i * 3) % 60,
                amount = 12_000L + ((i * 4271L) % 230_000L),
                flag = if (i == 3 || i == 11) "Высокий бонус — требует ревью" else null,
            )
            itemRepository.save(item)
        }
        log.info("Seeded {} payout batches + 18 items в первом", batches.size)
    }

    // ── Holders ─────────────────────────────────────────────────────────

    private data class DevAccount(
        val email: String,
        val password: String,
        val name: String,
        val role: AdminRole,
        val company: String,
    )

    private data class ProductSeed(
        val id: String,
        val name: String,
        val brand: String,
        val vendor: String,
        val mnn: String,
        val price: Int,
    )

    private data class BatchSeed(
        val id: String,
        val period: String,
        val status: PayoutBatchStatus,
        val pharmacists: Int,
        val amount: Long,
        val items: Int,
        val approvedAt: Instant?,
    )

    private data class ChainSeed(
        val id: String,
        val name: String,
        val color: String,
        val points: Int,
        val group: PharmacyGroup,
    )

    private data class PromoSeed(
        val id: String,
        val title: String,
        val status: PromoStatus,
        val brand: String,
        val period: String,
        val pharmacies: Int,
        val budget: Long,
        val spent: Long,
        val kpi: String,
        val cover: String,
    )

    private data class RuleSeed(
        val id: String,
        val type: RuleType,
        val status: RuleStatus,
        val trigger: RuleTrigger,
        val recommend: String,
        val bonus: Int,
        val pharmacies: Int,
        val script: String,
        val advantages: List<String>,
        val abTest: RuleAbTest? = null,
        val impressions: Int = 0,
        val accepts: Int = 0,
        val convRate: String = "0.00",
        val revenue: Long = 0,
        val payout: Long = 0,
    )
}

/**
 * Детерминированный ВАЛИДНЫЙ ИИН для seed по индексу: дата 1995-01-01, век/пол=3 (XX, муж),
 * порядковый = индекс, контрольная цифра подбирается так, чтобы пройти [IinUtil].
 */
private fun validSeedIin(index: Int): String {
    var seq = index
    while (true) {
        val base = "9501013" + String.format("%04d", seq % 10000) // 11 цифр (дата+пол+порядковый)
        for (d in 0..9) {
            val candidate = base + d
            if (IinUtil.isValid(candidate)) return candidate
        }
        seq++ // редкий случай контрольной=10 — берём следующий порядковый
    }
}
