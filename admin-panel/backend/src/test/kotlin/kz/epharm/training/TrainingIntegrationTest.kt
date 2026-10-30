package kz.epharm.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.domain.AdminUserStatus
import kz.epharm.auth.dto.LoginRequest
import kz.epharm.auth.dto.LoginResponse
import kz.epharm.auth.entity.AdminUserEntity
import kz.epharm.auth.repository.AdminUserRepository
import kz.epharm.auth.service.JwtService
import kz.epharm.lms.entity.CourseEntity
import kz.epharm.lms.entity.CourseLessonEntity
import kz.epharm.lms.entity.CourseLessonKind
import kz.epharm.lms.entity.CourseLiveStatus
import kz.epharm.lms.entity.CourseQuizQuestion
import kz.epharm.lms.entity.CourseStatus
import kz.epharm.lms.repository.CourseLessonRepository
import kz.epharm.lms.repository.CourseRepository
import kz.epharm.pharmacies.entity.ChainEntity
import kz.epharm.pharmacies.entity.PharmacyEntity
import kz.epharm.pharmacies.entity.PharmacyGroup
import kz.epharm.pharmacies.repository.ChainRepository
import kz.epharm.pharmacies.repository.PharmacyRepository
import kz.epharm.pharmacists.entity.PharmacistEntity
import kz.epharm.pharmacists.entity.PharmacistStatus
import kz.epharm.pharmacists.repository.PharmacistRepository
import kz.epharm.training.repository.TrainingCertificateRepository
import kz.epharm.training.repository.TrainingAssessmentResultRepository
import kz.epharm.training.repository.TrainingAssignmentStageRepository
import kz.epharm.training.repository.TrainingAssignmentRepository
import kz.epharm.training.repository.TrainingRewardRepository
import kz.epharm.training.repository.TrainingLessonProgressRepository
import kz.epharm.training.domain.TrainingStageStatus
import kz.epharm.training.domain.TrainingAssignmentStatus
import kz.epharm.training.entity.TrainingLessonProgressEntity
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.time.Duration
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Transactional
class TrainingIntegrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("epharm_training_test")
            .withUsername("epharm")
            .withPassword("epharm_test")
            .apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
        }
    }

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var passwordEncoder: PasswordEncoder
    @Autowired private lateinit var jwtService: JwtService
    @Autowired private lateinit var adminUserRepository: AdminUserRepository
    @Autowired private lateinit var chainRepository: ChainRepository
    @Autowired private lateinit var pharmacyRepository: PharmacyRepository
    @Autowired private lateinit var pharmacistRepository: PharmacistRepository
    @Autowired private lateinit var courseRepository: CourseRepository
    @Autowired private lateinit var courseLessonRepository: CourseLessonRepository
    @Autowired private lateinit var certificateRepository: TrainingCertificateRepository
    @Autowired private lateinit var assessmentResultRepository: TrainingAssessmentResultRepository
    @Autowired private lateinit var rewardRepository: TrainingRewardRepository
    @Autowired private lateinit var lessonProgressRepository: TrainingLessonProgressRepository
    @Autowired private lateinit var assignmentStageRepository: TrainingAssignmentStageRepository
    @Autowired private lateinit var assignmentRepository: TrainingAssignmentRepository

    private lateinit var adminBearer: String
    private lateinit var pharmacistBearer: String

    @BeforeEach
    fun seed() {
        val admin = adminUserRepository.save(
            AdminUserEntity(
                email = "training@inkar.kz",
                passwordHash = passwordEncoder.encode("strong-pass"),
                name = "Training Manager",
                company = "Inkar",
            ).also {
                it.role = AdminRole.TRAINING_MANAGER
                it.status = AdminUserStatus.ACTIVE
            },
        )
        chainRepository.save(ChainEntity(id = "chain_training", name = "Training chain", color = "#123456").also {
            it.group = PharmacyGroup.pilot
        })
        pharmacyRepository.save(
            PharmacyEntity(
                id = "pharmacy_training",
                name = "Аптека обучения",
                chainId = "chain_training",
                chainName = "Training chain",
                city = "Алматы",
                addr = "Ауэзова 134",
            ).also { it.group = PharmacyGroup.pilot },
        )
        pharmacistRepository.save(
            PharmacistEntity(
                id = "ph_training",
                name = "Айжан Фармацевт",
                iin = "900101400001",
                phone = "+77070000001",
                pharmacyId = "pharmacy_training",
                pharmacyName = "Аптека обучения",
                city = "Алматы",
            ).also { it.status = PharmacistStatus.active },
        )
        courseRepository.save(
            CourseEntity(
                id = "crs_training",
                title = "Основы продукта",
                description = "Безопасная рекомендация категории",
                lessons = 1,
                durationMin = 8,
            ).also {
                it.status = CourseStatus.published
            },
        )
        courseLessonRepository.save(
            CourseLessonEntity(
                id = "cls_training_intro",
                courseId = "crs_training",
                title = "Введение",
                description = "Основные понятия",
                content = "Текст учебного материала",
                videoUrl = "https://epharm.inkar.kz/s3/training-intro.mp4",
                durationMin = 8,
                order = 0,
            ).also { it.kind = CourseLessonKind.video },
        )
        adminBearer = "Bearer " + login().tokens.accessToken
        pharmacistBearer = "Bearer " + jwtService.issuePharmacistToken(
            "ph_training",
            "Айжан Фармацевт",
            "+77070000001",
        )
    }

    @Test
    fun `pharmacist quiz hides answers scores attempts and unlocks course only after pass`() {
        courseLessonRepository.deleteAll()
        courseLessonRepository.save(
            CourseLessonEntity(
                id = "cls_training_quiz",
                courseId = "crs_training",
                title = "Итоговый тест",
                required = true,
                quizPassingScore = 80,
                quizQuestions = listOf(
                    CourseQuizQuestion("q-1", "Когда принимать?", listOf("До еды", "После еды"), 1, "По инструкции"),
                    CourseQuizQuestion("q-2", "Сколько таблеток?", listOf("Одну", "Две"), 0, "Одна таблетка"),
                ),
                order = 0,
            ).also { it.kind = CourseLessonKind.quiz },
        )
        val programId = createPublishedProgram()["id"].asText()
        val assigned = mockMvc.perform(
            post("/api/admin/training/assignments")
                .header("Authorization", adminBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"programId":"$programId","pharmacistIds":["ph_training"],"format":"online","duplicatePolicy":"skip"}""",
                ),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val assignmentId = objectMapper.readTree(assigned)["assignments"][0]["id"].asText()
        val started = mockMvc.perform(
            post("/api/mobile/training/assignments/$assignmentId/start")
                .header("Authorization", pharmacistBearer),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.stages[0].course.lessons[0].quizQuestions.length()").value(2))
            .andExpect(jsonPath("$.stages[0].course.lessons[0].quizQuestions[0].correctOption").doesNotExist())
            .andReturn().response.contentAsString
        val startedJson = objectMapper.readTree(started)
        val onlineStage = startedJson["stages"].first { it["type"].asText() == "online_course" }
        val stageId = onlineStage["id"].asText()
        val lessonId = onlineStage["course"]["lessons"][0]["id"].asText()

        mockMvc.perform(
            patch("/api/mobile/training/assignments/$assignmentId/stages/$stageId/lessons/$lessonId")
                .header("Authorization", pharmacistBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"progressPct":100}"""),
        ).andExpect(status().isBadRequest)

        mockMvc.perform(
            post("/api/mobile/training/assignments/$assignmentId/stages/$stageId/lessons/$lessonId/quiz")
                .header("Authorization", pharmacistBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"answers":{"q-1":0,"q-2":1}}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.score").value(0))
            .andExpect(jsonPath("$.passed").value(false))
            .andExpect(jsonPath("$.attempt").value(1))
            .andExpect(jsonPath("$.questions").isArray)
            .andExpect(jsonPath("$.questions").isEmpty)
            .andExpect(jsonPath("$.assignment.stages[0].course.lessons[0].quizQuestions[0].correctOption").doesNotExist())
            .andExpect(jsonPath("$.assignment.stages[0].course.lessons[0].quizQuestions[0].explanation").value(""))
            .andExpect(jsonPath("$.assignment.status").value("waiting_online"))

        mockMvc.perform(
            post("/api/mobile/training/assignments/$assignmentId/stages/$stageId/lessons/$lessonId/quiz")
                .header("Authorization", pharmacistBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"answers":{"q-1":1,"q-2":0}}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.score").value(100))
            .andExpect(jsonPath("$.passed").value(true))
            .andExpect(jsonPath("$.attempt").value(2))
            .andExpect(jsonPath("$.questions").isArray)
            .andExpect(jsonPath("$.questions").isEmpty)
            .andExpect(jsonPath("$.assignment.status").value("waiting_test"))
            .andExpect(jsonPath("$.assignment.stages[0].course.lessons[0].quizScore").value(100))
            .andExpect(jsonPath("$.assignment.stages[0].course.lessons[0].quizAttempts").value(2))
    }

    @Test
    fun `quiz submission requires previous lessons and does not round a failure into a pass`() {
        val quiz = courseLessonRepository.save(
            CourseLessonEntity(
                id = "cls_threshold_quiz",
                courseId = "crs_training",
                title = "Итоговый тест",
                required = true,
                quizPassingScore = 67,
                quizQuestions = (1..3).map { index ->
                    CourseQuizQuestion("q-$index", "Вопрос $index", listOf("А", "Б"), 0, "Ответ")
                },
                order = 1,
            ).also { it.kind = CourseLessonKind.test },
        )
        val programId = createPublishedProgram()["id"].asText()
        val assignment = objectMapper.readTree(
            mockMvc.perform(
                post("/api/admin/training/assignments")
                    .header("Authorization", adminBearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"programId":"$programId","pharmacistIds":["ph_training"],"format":"online"}"""),
            ).andExpect(status().isOk).andReturn().response.contentAsString,
        )["assignments"][0]
        val assignmentId = assignment["id"].asText()
        val stageId = assignment["stages"][0]["id"].asText()
        val lessonPath = "/api/mobile/training/assignments/$assignmentId/stages/$stageId/lessons"
        val quizPath = "$lessonPath/${quiz.id}/quiz"

        mockMvc.perform(
            post(quizPath).header("Authorization", pharmacistBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"answers":{"q-1":1,"q-2":1,"q-3":1}}"""),
        ).andExpect(status().isConflict)
        org.assertj.core.api.Assertions.assertThat(
            lessonProgressRepository.findByAssignmentStageIdAndLessonId(UUID.fromString(stageId), quiz.id),
        ).isNull()

        val intro = courseLessonRepository.findById("cls_training_intro").orElseThrow()
        intro.durationMin = 0
        courseLessonRepository.save(intro)
        mockMvc.perform(
            patch("$lessonPath/${intro.id}").header("Authorization", pharmacistBearer)
                .contentType(MediaType.APPLICATION_JSON).content("""{"progressPct":100}"""),
        ).andExpect(status().isOk)

        mockMvc.perform(
            post(quizPath).header("Authorization", pharmacistBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"answers":{"q-1":0,"q-2":0,"q-3":1}}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.score").value(67))
            .andExpect(jsonPath("$.passed").value(false))
            .andExpect(jsonPath("$.attempt").value(1))
            .andExpect(jsonPath("$.questions").isArray)
            .andExpect(jsonPath("$.questions").isEmpty)
            .andExpect(jsonPath("$.assignment.stages[0].status").value("in_progress"))
            .andExpect(jsonPath("$.assignment.certificate").doesNotExist())
        mockMvc.perform(
            patch("$lessonPath/${quiz.id}").header("Authorization", pharmacistBearer)
                .contentType(MediaType.APPLICATION_JSON).content("""{"progressPct":100}"""),
        ).andExpect(status().isBadRequest)
        val failedProgress = lessonProgressRepository.findByAssignmentStageIdAndLessonId(UUID.fromString(stageId), quiz.id)!!
        assertNull(failedProgress.completedAt)
        assertEquals(1, failedProgress.quizAttempts)
        assertEquals(TrainingStageStatus.in_progress, assignmentStageRepository.findById(UUID.fromString(stageId)).orElseThrow().status)
        assertNull(certificateRepository.findByAssignmentId(UUID.fromString(assignmentId)))
        assertNull(rewardRepository.findByAssignmentId(UUID.fromString(assignmentId)))
        org.assertj.core.api.Assertions.assertThat(quiz.quizQuestions.first().correctOption).isEqualTo(0)
        org.assertj.core.api.Assertions.assertThat(quiz.quizQuestions.first().explanation).isEqualTo("Ответ")
    }

    @Test
    fun `quiz accepts two thirds at threshold 66 and exact four fifths at threshold 80 without changing displayed scores`() {
        for ((threshold, total, correct) in listOf(Triple(66, 3, 2), Triple(80, 5, 4))) {
            val courseId = "crs_exact_threshold_$threshold"
            courseRepository.save(CourseEntity(id = courseId, title = "Synthetic threshold $threshold", lessons = 1)
                .also { it.status = CourseStatus.published })
            val lesson = courseLessonRepository.save(CourseLessonEntity(
                id = "cls_exact_threshold_$threshold", courseId = courseId, title = "Synthetic assessed lesson",
                quizPassingScore = threshold, required = true,
                quizQuestions = (1..total).map { index ->
                    CourseQuizQuestion("q-$index", "Question $index", listOf("A", "B"), 0, "Private explanation")
                },
            ).also { it.kind = CourseLessonKind.test })
            val program = objectMapper.readTree(mockMvc.perform(post("/api/admin/training/programs")
                .header("Authorization", adminBearer).contentType(MediaType.APPLICATION_JSON).content("""
                    {"name":"Synthetic exact threshold $threshold","allowedFormats":["online"],"status":"published",
                     "onlineCourseId":"$courseId","completionBonus":0,"stages":[
                     {"key":"course","type":"online_course","title":"Assessed course","order":0,"applicableFormats":["online"]}]}
                """.trimIndent())).andExpect(status().isOk).andReturn().response.contentAsString)
            val programId = program["id"].asText()
            val assignment = objectMapper.readTree(mockMvc.perform(post("/api/admin/training/assignments")
                .header("Authorization", adminBearer).contentType(MediaType.APPLICATION_JSON)
                .content("""{"programId":"$programId","pharmacistIds":["ph_training"],"format":"online"}"""))
                .andExpect(status().isOk).andReturn().response.contentAsString)["assignments"][0]
            val assignmentId = assignment["id"].asText()
            val stageId = assignment["stages"][0]["id"].asText()
            val answers = (1..total).associate { "q-$it" to if (it <= correct) 0 else 1 }
            val displayedScore = if (threshold == 66) 67 else 80
            mockMvc.perform(post("/api/mobile/training/assignments/$assignmentId/stages/$stageId/lessons/${lesson.id}/quiz")
                .header("Authorization", pharmacistBearer).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("answers" to answers))))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.passed").value(true))
                .andExpect(jsonPath("$.score").value(displayedScore))
                .andExpect(jsonPath("$.attempt").value(1))
                .andExpect(jsonPath("$.questions").isArray)
                .andExpect(jsonPath("$.questions").isEmpty)
                .andExpect(jsonPath("$.assignment.status").value("completed"))
                .andExpect(jsonPath("$.assignment.certificate.score").value(displayedScore))
            assertEquals(displayedScore, certificateRepository.findByAssignmentId(UUID.fromString(assignmentId))!!.score)
        }
    }

    @Test
    fun `first successful quiz replaces failed high score after threshold change and later retries retain only successful best`() {
        for (bestSuccessfulScore in listOf(80, 100)) {
            val courseId = "crs_changed_threshold_$bestSuccessfulScore"
            courseRepository.save(CourseEntity(id = courseId, title = "Synthetic changed threshold", lessons = 2)
                .also { it.status = CourseStatus.published })
            val quiz = courseLessonRepository.save(CourseLessonEntity(
                id = "cls_changed_threshold_$bestSuccessfulScore", courseId = courseId, title = "Assessed lesson",
                order = 0, quizPassingScore = 90,
                quizQuestions = (1..15).map { index ->
                    CourseQuizQuestion("q-$index", "Question $index", listOf("A", "B"), 0, "Private explanation")
                },
            ).also { it.kind = CourseLessonKind.test })
            val material = courseLessonRepository.save(CourseLessonEntity(
                id = "cls_changed_material_$bestSuccessfulScore", courseId = courseId,
                title = "Remaining required material", order = 1, content = "Read after the test",
            ).also { it.kind = CourseLessonKind.text })
            val program = objectMapper.readTree(mockMvc.perform(post("/api/admin/training/programs")
                .header("Authorization", adminBearer).contentType(MediaType.APPLICATION_JSON).content("""
                    {"name":"Synthetic changed threshold $bestSuccessfulScore","allowedFormats":["online"],"status":"published",
                     "onlineCourseId":"$courseId","completionBonus":0,"stages":[
                     {"key":"course","type":"online_course","title":"Assessed course","order":0,"applicableFormats":["online"]}]}
                """.trimIndent())).andExpect(status().isOk).andReturn().response.contentAsString)
            val programId = program["id"].asText()
            val assignment = objectMapper.readTree(mockMvc.perform(post("/api/admin/training/assignments")
                .header("Authorization", adminBearer).contentType(MediaType.APPLICATION_JSON)
                .content("""{"programId":"$programId","pharmacistIds":["ph_training"],"format":"online"}"""))
                .andExpect(status().isOk).andReturn().response.contentAsString)["assignments"][0]
            val assignmentId = assignment["id"].asText()
            val stageId = assignment["stages"][0]["id"].asText()
            val lessonPath = "/api/mobile/training/assignments/$assignmentId/stages/$stageId/lessons"
            fun submit(correct: Int) = mockMvc.perform(post("$lessonPath/${quiz.id}/quiz")
                .header("Authorization", pharmacistBearer).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("answers" to (1..15).associate {
                    "q-$it" to if (it <= correct) 0 else 1
                }))))

            submit(13).andExpect(status().isOk)
                .andExpect(jsonPath("$.passed").value(false))
                .andExpect(jsonPath("$.score").value(87))
            mockMvc.perform(patch("/api/admin/lms/courses/$courseId/lessons/${quiz.id}")
                .header("Authorization", adminBearer).contentType(MediaType.APPLICATION_JSON)
                .content("""{"quizPassingScore":80}"""))
                .andExpect(status().isOk)
            submit(12).andExpect(status().isOk)
                .andExpect(jsonPath("$.passed").value(true))
                .andExpect(jsonPath("$.score").value(80))
                .andExpect(jsonPath("$.assignment.stages[0].course.lessons[0].quizScore").value(80))
                .andExpect(jsonPath("$.assignment.stages[0].score").value(80))
            if (bestSuccessfulScore == 100) {
                submit(15).andExpect(status().isOk)
                    .andExpect(jsonPath("$.assignment.stages[0].score").value(100))
                submit(12).andExpect(status().isOk)
                    .andExpect(jsonPath("$.score").value(80))
                    .andExpect(jsonPath("$.assignment.stages[0].course.lessons[0].quizScore").value(100))
                    .andExpect(jsonPath("$.assignment.stages[0].score").value(100))
                submit(0).andExpect(status().isOk)
                    .andExpect(jsonPath("$.passed").value(false))
                    .andExpect(jsonPath("$.score").value(0))
                    .andExpect(jsonPath("$.assignment.stages[0].course.lessons[0].quizScore").value(100))
            }
            mockMvc.perform(patch("$lessonPath/${material.id}")
                .header("Authorization", pharmacistBearer).contentType(MediaType.APPLICATION_JSON)
                .content("""{"progressPct":100}"""))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.score").value(bestSuccessfulScore))
                .andExpect(jsonPath("$.certificate.score").value(bestSuccessfulScore))
            assertEquals(bestSuccessfulScore, certificateRepository.findByAssignmentId(UUID.fromString(assignmentId))!!.score)
        }
    }

    @Test
    fun `certificate averages required test results once and preserves passed grade after a failed retry`() {
        courseLessonRepository.deleteAll()
        listOf("first", "second", "optional").forEachIndexed { index, key ->
            courseLessonRepository.save(
                CourseLessonEntity(
                    id = "cls_$key", courseId = "crs_training", title = key,
                    required = key != "optional", order = index, quizPassingScore = 80,
                    quizQuestions = (1..5).map { question ->
                        CourseQuizQuestion("q-$question", "Вопрос $question", listOf("А", "Б"), 0, "Ответ")
                    },
                ).also { it.kind = CourseLessonKind.test },
            )
        }
        val programId = createPublishedProgram()["id"].asText()
        val assignment = objectMapper.readTree(
            mockMvc.perform(
                post("/api/admin/training/assignments").header("Authorization", adminBearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"programId":"$programId","pharmacistIds":["ph_training"],"format":"online"}"""),
            ).andExpect(status().isOk).andReturn().response.contentAsString,
        )["assignments"][0]
        val assignmentId = assignment["id"].asText()
        val stageId = assignment["stages"][0]["id"].asText()
        val lessonPath = "/api/mobile/training/assignments/$assignmentId/stages/$stageId/lessons"
        val passingAnswers = """{"answers":{"q-1":0,"q-2":0,"q-3":0,"q-4":0,"q-5":1}}"""
        mockMvc.perform(
            post("$lessonPath/cls_first/quiz").header("Authorization", pharmacistBearer)
                .contentType(MediaType.APPLICATION_JSON).content(passingAnswers),
        ).andExpect(status().isOk).andExpect(jsonPath("$.score").value(80))
        mockMvc.perform(
            post("$lessonPath/cls_first/quiz").header("Authorization", pharmacistBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"answers":{"q-1":1,"q-2":1,"q-3":1,"q-4":1,"q-5":1}}"""),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.passed").value(false))
            .andExpect(jsonPath("$.assignment.stages[0].course.lessons[0].quizScore").value(80))
            .andExpect(jsonPath("$.questions").isArray)
            .andExpect(jsonPath("$.questions").isEmpty)
        val afterCourse = objectMapper.readTree(
            mockMvc.perform(
                post("$lessonPath/cls_second/quiz").header("Authorization", pharmacistBearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"answers":{"q-1":0,"q-2":0,"q-3":0,"q-4":0,"q-5":0}}"""),
            ).andExpect(status().isOk)
                .andExpect(jsonPath("$.assignment.stages[0].score").value(90))
                .andExpect(jsonPath("$.assignment.status").value("waiting_test"))
                .andExpect(jsonPath("$.assignment.certificate").doesNotExist())
                .andReturn().response.contentAsString,
        )["assignment"]
        val finalStageId = afterCourse["stages"].first { it["type"].asText() == "test" }["id"].asText()
        val completed = objectMapper.readTree(
            mockMvc.perform(
                patch("/api/admin/training/assignments/$assignmentId/stages/$finalStageId/result")
                    .header("Authorization", adminBearer).contentType(MediaType.APPLICATION_JSON)
                    .content("""{"score":80}"""),
            ).andExpect(status().isOk)
                .andExpect(jsonPath("$.progressPct").value(100))
                .andExpect(jsonPath("$.score").value(87))
                .andExpect(jsonPath("$.certificate.score").value(87))
                .andReturn().response.contentAsString,
        )
        val token = completed["certificate"]["verificationToken"].asText()
        mockMvc.perform(get("/api/public/training/certificates/$token"))
            .andExpect(status().isOk).andExpect(jsonPath("$.score").value(87))
        val pdf = mockMvc.perform(get("/api/public/training/certificates/$token/pdf"))
            .andExpect(status().isOk).andReturn().response.contentAsByteArray
        org.apache.pdfbox.Loader.loadPDF(pdf).use { document ->
            org.assertj.core.api.Assertions.assertThat(org.apache.pdfbox.text.PDFTextStripper().getText(document))
                .contains("87%").doesNotContain("100%")
        }
    }

    @Test
    fun `live lesson cannot complete early and cancelled assignment cannot access room`() {
        courseLessonRepository.deleteAll()
        courseLessonRepository.save(
            CourseLessonEntity(
                id = "cls_training_live",
                courseId = "crs_training",
                title = "Прямой эфир",
                liveStatusRaw = CourseLiveStatus.scheduled.name,
                liveStartAt = Instant.parse("2026-10-15T14:00:00Z"),
                order = 0,
            ).also { it.kind = CourseLessonKind.live },
        )
        val programId = createPublishedProgram()["id"].asText()
        val assigned = mockMvc.perform(
            post("/api/admin/training/assignments")
                .header("Authorization", adminBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"programId":"$programId","pharmacistIds":["ph_training"],"format":"online","duplicatePolicy":"skip"}"""),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val assignmentId = objectMapper.readTree(assigned)["assignments"][0]["id"].asText()
        val started = mockMvc.perform(
            post("/api/mobile/training/assignments/$assignmentId/start")
                .header("Authorization", pharmacistBearer),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val onlineStage = objectMapper.readTree(started)["stages"].first { it["type"].asText() == "online_course" }
        val stageId = onlineStage["id"].asText()
        val lessonPath = "/api/mobile/training/assignments/$assignmentId/stages/$stageId/lessons/cls_training_live"

        mockMvc.perform(get("$lessonPath/live").header("Authorization", pharmacistBearer))
            .andExpect(status().isOk)
        mockMvc.perform(
            patch(lessonPath).header("Authorization", pharmacistBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"progressPct":100}"""),
        ).andExpect(status().isConflict)
        mockMvc.perform(
            patch(lessonPath).header("Authorization", pharmacistBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"progressPct":50}"""),
        ).andExpect(status().isOk)
        val stageUuid = UUID.fromString(stageId)
        assert(lessonProgressRepository.findByAssignmentStageIdAndLessonId(stageUuid, "cls_training_live")?.progressPct == 50)

        val liveLesson = courseLessonRepository.findById("cls_training_live").orElseThrow()
        liveLesson.liveStatus = CourseLiveStatus.ended
        courseLessonRepository.save(liveLesson)
        mockMvc.perform(
            patch(lessonPath).header("Authorization", pharmacistBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"progressPct":100}"""),
        ).andExpect(status().isOk)

        val assignment = assignmentRepository.findById(UUID.fromString(assignmentId)).orElseThrow()
        assignment.status = TrainingAssignmentStatus.cancelled
        assignmentRepository.save(assignment)
        mockMvc.perform(get("$lessonPath/live").header("Authorization", pharmacistBearer))
            .andExpect(status().isConflict)
        mockMvc.perform(
            post("$lessonPath/live/messages").header("Authorization", pharmacistBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"kind":"comment","body":"Не должно сохраниться"}"""),
        ).andExpect(status().isConflict)
    }

    @Test
    fun `program assignment mobile progress creates certificate and reward exactly once`() {
        val program = createPublishedProgram()
        val programId = program["id"].asText()

        val assignmentBody = """
            {
              "programId":"$programId",
              "pharmacistIds":["ph_training"],
              "format":"online",
              "duplicatePolicy":"skip"
            }
        """.trimIndent()
        val assignmentResponse = mockMvc.perform(
            post("/api/admin/training/assignments")
                .header("Authorization", adminBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(assignmentBody),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.created").value(1))
            .andExpect(jsonPath("$.skipped").value(0))
            .andReturn().response.contentAsString
        val assignment = objectMapper.readTree(assignmentResponse)["assignments"][0]
        val assignmentId = assignment["id"].asText()
        assert(assignment["stages"].size() == 2)

        mockMvc.perform(
            get("/api/admin/training/assignments/page")
                .param("programId", programId)
                .param("format", "online")
                .param("q", "Айжан")
                .param("page", "0")
                .param("size", "1")
                .header("Authorization", adminBearer),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(1))
            .andExpect(jsonPath("$.totalPages").value(1))

        mockMvc.perform(
            post("/api/admin/training/assignments")
                .header("Authorization", adminBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(assignmentBody),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.created").value(0))
            .andExpect(jsonPath("$.skipped").value(1))

        val started = mockMvc.perform(
            post("/api/mobile/training/assignments/$assignmentId/start")
                .header("Authorization", pharmacistBearer),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("waiting_online"))
            .andExpect(jsonPath("$.stages[0].course.title").value("Основы продукта"))
            .andExpect(jsonPath("$.stages[0].course.lessons[0].title").value("Введение"))
            .andExpect(jsonPath("$.stages[0].course.lessons[0].kind").value("video"))
            .andExpect(
                jsonPath("$.stages[0].course.lessons[0].videoUrl")
                    .value("https://epharm.inkar.kz/s3/training-intro.mp4"),
            )
            .andReturn().response.contentAsString
        val startedJson = objectMapper.readTree(started)
        val onlineStage = startedJson["stages"].first { it["type"].asText() == "online_course" }
        val lessonId = onlineStage["course"]["lessons"][0]["id"].asText()

        mockMvc.perform(
            patch("/api/mobile/training/assignments/$assignmentId/stages/${onlineStage["id"].asText()}")
                .header("Authorization", pharmacistBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"progressPct":100}"""),
        )
            .andExpect(status().isConflict)

        mockMvc.perform(
            patch("/api/mobile/training/assignments/$assignmentId/stages/${onlineStage["id"].asText()}/lessons/$lessonId")
                .header("Authorization", pharmacistBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"progressPct":0,"positionSeconds":0}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("waiting_online"))

        val savedProgress = lessonProgressRepository.findByAssignmentStageIdAndLessonId(
            java.util.UUID.fromString(onlineStage["id"].asText()),
            lessonId,
        )!!
        savedProgress.startedAt = Instant.now().minus(Duration.ofMinutes(9))
        lessonProgressRepository.save(savedProgress)

        val afterOnline = mockMvc.perform(
            patch("/api/mobile/training/assignments/$assignmentId/stages/${onlineStage["id"].asText()}/lessons/$lessonId")
                .header("Authorization", pharmacistBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"progressPct":100,"positionSeconds":480}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("waiting_test"))
            .andExpect(jsonPath("$.stages[0].course.lessons[0].progressPct").value(100))
            .andExpect(jsonPath("$.stages[0].course.lessons[0].completedAt").isNotEmpty)
            .andReturn().response.contentAsString
        val testStage = objectMapper.readTree(afterOnline)["stages"].first { it["type"].asText() == "test" }

        mockMvc.perform(
            patch("/api/mobile/training/assignments/$assignmentId/stages/${testStage["id"].asText()}")
                .header("Authorization", pharmacistBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"progressPct":100}"""),
        )
            .andExpect(status().isForbidden)

        val completedResponse = mockMvc.perform(
            patch("/api/admin/training/assignments/$assignmentId/stages/${testStage["id"].asText()}/result")
                .header("Authorization", adminBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"score":92,"feedback":"Тест успешно сдан"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("completed"))
            .andExpect(jsonPath("$.progressPct").value(100))
            .andExpect(jsonPath("$.certificate.number").isNotEmpty)
            .andExpect(jsonPath("$.certificate.pdfUrl").value(org.hamcrest.Matchers.endsWith("/pdf")))
            .andExpect(jsonPath("$.reward.amount").value(750))
            .andReturn().response.contentAsString
        val completed = objectMapper.readTree(completedResponse)
        val certificateToken = completed["certificate"]["verificationToken"].asText()

        mockMvc.perform(
            get("/api/admin/training/assignments/$assignmentId/results")
                .header("Authorization", adminBearer),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].attempt").value(1))
            .andExpect(jsonPath("$[0].score").value(92))
            .andExpect(jsonPath("$[0].passed").value(true))

        val overviewResponse = mockMvc.perform(get("/api/mobile/training").header("Authorization", pharmacistBearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.completed").value(1))
            .andExpect(jsonPath("$.certificates.length()").value(1))
            .andExpect(jsonPath("$.notifications.length()").value(2))
            .andExpect(jsonPath("$.notifications[0].read").value(false))
            .andReturn().response.contentAsString
        val notificationId = objectMapper.readTree(overviewResponse)["notifications"][0]["id"].asText()

        mockMvc.perform(
            patch("/api/mobile/training/notifications/$notificationId/read")
                .header("Authorization", pharmacistBearer),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.read").value(true))
            .andExpect(jsonPath("$.readAt").isNotEmpty)

        mockMvc.perform(
            get("/api/admin/training/pharmacists/ph_training/profile")
                .header("Authorization", adminBearer),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.pharmacistName").value("Айжан Фармацевт"))
            .andExpect(jsonPath("$.totalAssignments").value(1))
            .andExpect(jsonPath("$.completedAssignments").value(1))
            .andExpect(jsonPath("$.totalRewards").value(750))
            .andExpect(jsonPath("$.certificates.length()").value(1))

        mockMvc.perform(get("/api/public/training/certificates/$certificateToken"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.number").isNotEmpty)
            .andExpect(jsonPath("$.pharmacistName").value("Айжан Фармацевт"))
            .andExpect(jsonPath("$.programName").value("Безопасная рекомендация продукта"))
            .andExpect(jsonPath("$.valid").value(true))

        val pdf = mockMvc.perform(get("/api/public/training/certificates/$certificateToken/pdf"))
            .andExpect(status().isOk)
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentType(MediaType.APPLICATION_PDF))
            .andReturn().response.contentAsByteArray
        assert(pdf.size > 5_000)
        assert(String(pdf.take(4).toByteArray()) == "%PDF")

        assert(certificateRepository.count() == 1L)
        assert(assessmentResultRepository.count() == 1L)
        assert(rewardRepository.count() == 1L)
        assert(pharmacistRepository.findById("ph_training").orElseThrow().balance == 750L)
    }

    @Test
    fun `pharmacist cannot access another assignment and admin endpoints reject pharmacist token`() {
        mockMvc.perform(get("/api/admin/training/programs").header("Authorization", pharmacistBearer))
            .andExpect(status().isForbidden)
        mockMvc.perform(get("/api/mobile/training").header("Authorization", adminBearer))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `preference history and filtered export are available to training manager`() {
        mockMvc.perform(
            patch("/api/admin/training/pharmacists/ph_training/preference")
                .header("Authorization", adminBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"defaultFormat":"hybrid","reason":"Нужна очная практика"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.pharmacistName").value("Айжан Фармацевт"))
            .andExpect(jsonPath("$.defaultFormat").value("hybrid"))
            .andExpect(jsonPath("$.current").value(true))

        mockMvc.perform(get("/api/admin/training/preferences").header("Authorization", adminBearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].defaultFormat").value("hybrid"))

        mockMvc.perform(
            patch("/api/admin/training/pharmacists/ph_training/preference")
                .header("Authorization", adminBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"defaultFormat":"online","reason":"Перевод на дистанционный формат"}"""),
        ).andExpect(status().isOk)

        mockMvc.perform(
            get("/api/admin/training/pharmacists/ph_training/preference-history")
                .header("Authorization", adminBearer),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].defaultFormat").value("online"))
            .andExpect(jsonPath("$[1].validTo").isNotEmpty)

        val csv = mockMvc.perform(
            get("/api/admin/training/assignments/export.csv")
                .param("format", "online")
                .header("Authorization", adminBearer),
        )
            .andExpect(status().isOk)
            .andReturn().response.contentAsString
        assert(csv.contains("Назначения обучения"))
        assert(csv.contains("Автор"))
    }

    @Test
    fun `each format receives only its configured route and status change keeps version`() {
        val createBody = """
            {
              "name":"Маршруты по формату",
              "description":"Исходное описание",
              "coverUrl":"https://cdn.example.org/training/cover.jpg",
              "language":"ru",
              "startsAt":"2026-08-10T08:00:00Z",
              "endsAt":"2026-08-20T08:00:00Z",
              "allowedFormats":["online","hybrid","offline"],
              "status":"draft",
              "onlineCourseId":"crs_training",
              "stages":[
                {"key":"materials","type":"material","title":"Материалы","order":0,
                 "applicableFormats":["online","hybrid"],"contentUrl":"https://example.org/material"},
                {"key":"seminar","type":"offline_event","title":"Семинар","order":1,
                 "applicableFormats":["hybrid","offline"]},
                {"key":"test","type":"test","title":"Тест","order":2,
                 "passingScore":80,"maxAttempts":2,"applicableFormats":["online","hybrid","offline"]}
              ]
            }
        """.trimIndent()
        val created = objectMapper.readTree(
            mockMvc.perform(
                post("/api/admin/training/programs")
                    .header("Authorization", adminBearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBody),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.stages[0].contentUrl").value("https://example.org/material"))
                .andReturn().response.contentAsString,
        )
        val programId = created["id"].asText()

        mockMvc.perform(
            patch("/api/admin/training/programs/$programId")
                .header("Authorization", adminBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"status":"published"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.version").value(1))

        mockMvc.perform(
            patch("/api/admin/training/programs/$programId")
                .header("Authorization", adminBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"description":"Обновлённое описание","language":"kk","tags":["INKAR","кардиология"],"clearCoverUrl":true,"clearStartsAt":true,"clearEndsAt":true}""",
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.version").value(1))
            .andExpect(jsonPath("$.description").value("Обновлённое описание"))
            .andExpect(jsonPath("$.language").value("kk"))
            .andExpect(jsonPath("$.coverUrl").doesNotExist())
            .andExpect(jsonPath("$.startsAt").doesNotExist())
            .andExpect(jsonPath("$.endsAt").doesNotExist())

        mockMvc.perform(
            patch("/api/admin/training/programs/$programId")
                .header("Authorization", adminBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"clearOnlineCourseId":true}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.version").value(2))
            .andExpect(jsonPath("$.onlineCourseId").doesNotExist())

        val assignment = objectMapper.readTree(
            mockMvc.perform(
                post("/api/admin/training/assignments")
                    .header("Authorization", adminBearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"programId":"$programId","pharmacistIds":["ph_training"],"format":"online"}""",
                    ),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.assignments[0].stages.length()").value(2))
                .andExpect(jsonPath("$.assignments[0].stages[0].type").value("material"))
                .andExpect(jsonPath("$.assignments[0].stages[1].type").value("test"))
                .andReturn().response.contentAsString,
        )["assignments"][0]
        val assignmentId = assignment["id"].asText()

        mockMvc.perform(
            patch("/api/admin/training/assignments/$assignmentId/format")
                .header("Authorization", adminBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"format":"offline","reason":"Нужна очная практика"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.format").value("offline"))
            .andExpect(jsonPath("$.stages.length()").value(2))
            .andExpect(jsonPath("$.stages[0].type").value("offline_event"))
            .andExpect(jsonPath("$.stages[1].type").value("test"))

        mockMvc.perform(
            get("/api/admin/training/assignments/$assignmentId/format-history")
                .header("Authorization", adminBearer),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].oldFormat").value("online"))
            .andExpect(jsonPath("$[0].newFormat").value("offline"))
    }

    @Test
    fun `online course stage requires linked course or content url`() {
        mockMvc.perform(
            post("/api/admin/training/programs")
                .header("Authorization", adminBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name":"Некорректная программа",
                      "allowedFormats":["online"],
                      "stages":[
                        {"key":"course","type":"online_course","title":"Онлайн-курс","order":0,
                         "applicableFormats":["online"]}
                      ]
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isBadRequest)
            .andExpect(
                jsonPath("$.message").value(
                    "Этап course: привяжите онлайн-курс или укажите ссылку на материал",
                ),
            )
    }

    @Test
    fun `completed legacy online course without lesson rows retains visible completion`() {
        courseLessonRepository.save(CourseLessonEntity(
            id = "cls_legacy_quiz", courseId = "crs_training", title = "Старый тест", order = 1,
            quizQuestions = listOf(CourseQuizQuestion("q-1", "Вопрос", listOf("А", "Б"), 0, "")),
        ).also { it.kind = CourseLessonKind.test })
        val programId = createPublishedProgram()["id"].asText()
        val assignment = objectMapper.readTree(
            mockMvc.perform(
                post("/api/admin/training/assignments")
                    .header("Authorization", adminBearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"programId":"$programId","pharmacistIds":["ph_training"],"format":"online"}"""),
            ).andExpect(status().isOk).andReturn().response.contentAsString,
        )["assignments"][0]
        val assignmentId = assignment["id"].asText()
        val onlineStageId = UUID.fromString(assignment["stages"][0]["id"].asText())
        val onlineStage = assignmentStageRepository.findById(onlineStageId).orElseThrow()
        onlineStage.status = TrainingStageStatus.completed
        onlineStage.progressPct = 100
        onlineStage.completedAt = Instant.now()
        assignmentStageRepository.saveAndFlush(onlineStage)

        mockMvc.perform(
            get("/api/mobile/training/assignments/$assignmentId")
                .header("Authorization", pharmacistBearer),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.stages[0].course.lessons[0].progressPct").value(100))
            .andExpect(jsonPath("$.stages[0].course.lessons[0].completedAt").isNotEmpty)

        lessonProgressRepository.save(TrainingLessonProgressEntity(
            assignmentId = UUID.fromString(assignmentId), assignmentStageId = onlineStageId,
            lessonId = "cls_legacy_quiz", progressPct = 100, completedAt = Instant.now(), quizScore = null,
        ))
        val finalStageId = UUID.fromString(assignment["stages"].first { it["type"].asText() == "test" }["id"].asText())
        val finalStage = assignmentStageRepository.findById(finalStageId).orElseThrow()
        finalStage.status = TrainingStageStatus.available
        assignmentStageRepository.save(finalStage)
        mockMvc.perform(
            patch("/api/admin/training/assignments/$assignmentId/stages/$finalStageId/result")
                .header("Authorization", adminBearer).contentType(MediaType.APPLICATION_JSON)
                .content("""{"score":90}"""),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("completed"))
            .andExpect(jsonPath("$.score").doesNotExist())
            .andExpect(jsonPath("$.certificate.score").doesNotExist())
    }

    @Test
    fun `legacy url only online course can still be completed`() {
        val program = objectMapper.readTree(
            mockMvc.perform(
                post("/api/admin/training/programs")
                    .header("Authorization", adminBearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"name":"Ссылка на обучение","allowedFormats":["online"],"status":"published", "stages":[{"key":"course","type":"online_course","title":"Курс по ссылке","order":0,"applicableFormats":["online"],"contentUrl":"https://example.org/course"}]}""",
                    ),
            ).andExpect(status().isOk).andReturn().response.contentAsString,
        )
        val assignment = objectMapper.readTree(
            mockMvc.perform(
                post("/api/admin/training/assignments")
                    .header("Authorization", adminBearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"programId":"${program["id"].asText()}","pharmacistIds":["ph_training"],"format":"online"}"""),
            ).andExpect(status().isOk).andReturn().response.contentAsString,
        )["assignments"][0]
        val assignmentId = assignment["id"].asText()
        val stageId = assignment["stages"][0]["id"].asText()
        mockMvc.perform(
            post("/api/mobile/training/assignments/$assignmentId/start")
                .header("Authorization", pharmacistBearer),
        ).andExpect(status().isOk)
        mockMvc.perform(
            patch("/api/mobile/training/assignments/$assignmentId/stages/$stageId")
                .header("Authorization", pharmacistBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"progressPct":100}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("completed"))
            .andExpect(jsonPath("$.stages[0].progressPct").value(100))
            .andExpect(jsonPath("$.certificate.score").doesNotExist())
    }


    @Test
    fun `hybrid route requires both online and offline stages`() {
        mockMvc.perform(
            post("/api/admin/training/programs")
                .header("Authorization", adminBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name":"Неполный гибридный маршрут",
                      "allowedFormats":["hybrid"],
                      "status":"draft",
                      "stages":[
                        {"key":"materials","type":"material","title":"Материалы","order":0,
                         "applicableFormats":["hybrid"],"contentUrl":"https://example.org/material"}
                      ]
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.message").value("Гибридный маршрут должен содержать обязательное очное мероприятие"))
    }

    @Test
    fun `pharmacist selects matching event and checks in with protected qr`() {
        val program = createPublishedProgram()
        val programId = program["id"].asText()
        val startsAt = Instant.now().plusSeconds(3_600)
        val endsAt = startsAt.plusSeconds(3_600)
        val registrationDeadline = Instant.now().plusSeconds(1_800)

        val eventResponse = mockMvc.perform(
            post("/api/admin/training/events")
                .header("Authorization", adminBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "programId":"$programId",
                      "title":"Практика по продукту",
                      "startsAt":"$startsAt",
                      "endsAt":"$endsAt",
                      "registrationDeadline":"$registrationDeadline",
                      "region":"Алматы",
                      "city":"Алматы",
                      "address":"Ауэзова 134",
                      "capacity":10,
                      "status":"registration"
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.occupied").value(0))
            .andExpect(jsonPath("$.qrToken").doesNotExist())
            .andReturn().response.contentAsString
        val eventId = objectMapper.readTree(eventResponse)["id"].asText()
        val movedStartsAt = startsAt.plusSeconds(600)
        val movedEndsAt = endsAt.plusSeconds(600)

        mockMvc.perform(
            patch("/api/admin/training/events/$eventId")
                .header("Authorization", adminBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"startsAt":"$movedStartsAt","endsAt":"$movedEndsAt","address":"Тимирязева 42","capacity":12,"clearRegistrationDeadline":true}""",
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.startsAt").value(movedStartsAt.toString()))
            .andExpect(jsonPath("$.address").value("Тимирязева 42"))
            .andExpect(jsonPath("$.capacity").value(12))
            .andExpect(jsonPath("$.registrationDeadline").doesNotExist())

        val assignmentResponse = mockMvc.perform(
            post("/api/admin/training/assignments")
                .header("Authorization", adminBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"programId":"$programId","pharmacistIds":["ph_training"],"format":"hybrid"}""",
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.assignments[0].event").doesNotExist())
            .andReturn().response.contentAsString
        val assignmentId = objectMapper.readTree(assignmentResponse)["assignments"][0]["id"].asText()

        mockMvc.perform(
            get("/api/mobile/training/assignments/$assignmentId/events")
                .header("Authorization", pharmacistBearer),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value(eventId))
            .andExpect(jsonPath("$[0].qrToken").doesNotExist())

        mockMvc.perform(
            post("/api/mobile/training/assignments/$assignmentId/events/$eventId")
                .header("Authorization", pharmacistBearer),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.event.id").value(eventId))

        val qrResponse = mockMvc.perform(
            get("/api/admin/training/events/$eventId/qr")
                .header("Authorization", adminBearer),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.eventId").value(eventId))
            .andExpect(jsonPath("$.payload").value(org.hamcrest.Matchers.startsWith("epharm://training/check-in/")))
            .andReturn().response.contentAsString
        val qrToken = objectMapper.readTree(qrResponse)["token"].asText()
        val checkInCode = objectMapper.readTree(qrResponse)["checkInCode"].asText()

        mockMvc.perform(
            post("/api/mobile/training/events/check-in/$qrToken")
                .header("Authorization", pharmacistBearer),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.event.id").value(eventId))
            .andExpect(jsonPath("$.stages[?(@.type == 'offline_event')].status").value("completed"))

        mockMvc.perform(
            get("/api/admin/training/events/$eventId/participants")
                .header("Authorization", adminBearer),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].pharmacistId").value("ph_training"))
            .andExpect(jsonPath("$[0].status").value("attended"))
            .andExpect(jsonPath("$[0].checkMethod").value("qr"))

        mockMvc.perform(
            post("/api/mobile/training/events/check-in-code/$checkInCode")
                .header("Authorization", pharmacistBearer),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.event.id").value(eventId))

        mockMvc.perform(
            get("/api/admin/training/events/$eventId/participants")
                .header("Authorization", adminBearer),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].checkMethod").value("qr"))

        val participantResponse = mockMvc.perform(
            get("/api/admin/training/events/$eventId/participants")
                .header("Authorization", adminBearer),
        ).andReturn().response.contentAsString
        val participantId = objectMapper.readTree(participantResponse)[0]["id"].asText()

        mockMvc.perform(
            patch("/api/admin/training/events/$eventId/participants/$participantId")
                .header("Authorization", adminBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"status":"late","method":"manual","comment":"Опоздание на 10 минут"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("late"))
            .andExpect(jsonPath("$.checkedInAt").isNotEmpty)

        mockMvc.perform(
            patch("/api/admin/training/events/$eventId/participants/$participantId")
                .header("Authorization", adminBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"status":"excused","method":"manual","comment":"Больничный"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("excused"))
            .andExpect(jsonPath("$.checkedInAt").doesNotExist())

        mockMvc.perform(
            get("/api/mobile/training/assignments/$assignmentId")
                .header("Authorization", pharmacistBearer),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("waiting_online"))
            .andExpect(jsonPath("$.event").doesNotExist())
    }

    @Test
    fun `pharmacist can check in by six digit code and retry remains idempotent`() {
        val programId = createPublishedProgram()["id"].asText()
        val startsAt = Instant.now().plusSeconds(3_600)
        val endsAt = startsAt.plusSeconds(3_600)
        val eventId = objectMapper.readTree(
            mockMvc.perform(
                post("/api/admin/training/events")
                    .header("Authorization", adminBearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"programId":"$programId","title":"Практикум по коду","startsAt":"$startsAt","endsAt":"$endsAt","city":"Алматы","capacity":10,"status":"registration"}""",
                    ),
            ).andExpect(status().isOk).andReturn().response.contentAsString,
        )["id"].asText()
        val assignmentId = objectMapper.readTree(
            mockMvc.perform(
                post("/api/admin/training/assignments")
                    .header("Authorization", adminBearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"programId":"$programId","pharmacistIds":["ph_training"],"format":"hybrid"}"""),
            ).andExpect(status().isOk).andReturn().response.contentAsString,
        )["assignments"][0]["id"].asText()
        mockMvc.perform(
            post("/api/mobile/training/assignments/$assignmentId/events/$eventId")
                .header("Authorization", pharmacistBearer),
        ).andExpect(status().isOk)

        val qr = objectMapper.readTree(
            mockMvc.perform(
                get("/api/admin/training/events/$eventId/qr")
                    .header("Authorization", adminBearer),
            ).andExpect(status().isOk).andReturn().response.contentAsString,
        )
        val code = qr["checkInCode"].asText()
        assert(code.matches(Regex("[0-9]{6}")))
        assert(code != "000000")

        repeat(2) {
            mockMvc.perform(
                post("/api/mobile/training/events/check-in-code/$code")
                    .header("Authorization", pharmacistBearer),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.event.id").value(eventId))
        }
        mockMvc.perform(
            get("/api/admin/training/events/$eventId/participants")
                .header("Authorization", adminBearer),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].status").value("attended"))
            .andExpect(jsonPath("$[0].checkMethod").value("code"))
    }


    @Test
    fun `cancelling event releases unfinished assignment and notifies pharmacist`() {
        val programId = createPublishedProgram()["id"].asText()
        val startsAt = Instant.now().plusSeconds(7_200)
        val endsAt = startsAt.plusSeconds(3_600)
        val eventId = objectMapper.readTree(
            mockMvc.perform(
                post("/api/admin/training/events")
                    .header("Authorization", adminBearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "programId":"$programId",
                          "title":"Отменяемая практика",
                          "startsAt":"$startsAt",
                          "endsAt":"$endsAt",
                          "city":"Алматы",
                          "capacity":10,
                          "status":"registration"
                        }
                        """.trimIndent(),
                    ),
            ).andExpect(status().isOk).andReturn().response.contentAsString,
        )["id"].asText()

        val assignmentId = objectMapper.readTree(
            mockMvc.perform(
                post("/api/admin/training/assignments")
                    .header("Authorization", adminBearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"programId":"$programId","pharmacistIds":["ph_training"],"format":"hybrid","eventId":"$eventId"}""",
                    ),
            ).andExpect(status().isOk).andReturn().response.contentAsString,
        )["assignments"][0]["id"].asText()

        mockMvc.perform(
            patch("/api/admin/training/events/$eventId")
                .header("Authorization", adminBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"status":"cancelled"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("cancelled"))

        mockMvc.perform(
            get("/api/mobile/training/assignments/$assignmentId")
                .header("Authorization", pharmacistBearer),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("waiting_online"))
            .andExpect(jsonPath("$.event").doesNotExist())

        mockMvc.perform(
            get("/api/admin/training/events/$eventId/participants")
                .header("Authorization", adminBearer),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].status").value("cancelled"))

        mockMvc.perform(get("/api/mobile/training").header("Authorization", pharmacistBearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.notifications[?(@.eventType == 'training_event_cancelled')].title")
                .value("Мероприятие отменено"))
    }

    @Test
    fun `test and AI stages retain best successful result across lower pass and failure while preserving every attempt`() {
        for (type in listOf("test", "ai_exam")) {
            val assignment = createAssessmentRoute(type)
            val assignmentId = assignment["id"].asText()
            val assessedId = assignment["stages"].first { it["key"].asText() == "assessed" }["id"].asText()
            val otherId = assignment["stages"].first { it["key"].asText() == "other" }["id"].asText()
            val materialId = assignment["stages"].first { it["key"].asText() == "material" }["id"].asText()
            val first = recordResult(assignmentId, assessedId, 90, true)
            val completedAt = first["stages"].first { it["id"].asText() == assessedId }["completedAt"].asText()
            val lower = recordResult(assignmentId, assessedId, 80, true)
            assertEquals(90, lower["stages"].first { it["id"].asText() == assessedId }["score"].asInt())
            recordResult(assignmentId, otherId, 30, false)
            val afterFailure = recordResult(assignmentId, assessedId, 100, false)
            val preserved = afterFailure["stages"].first { it["id"].asText() == assessedId }
            assertEquals("completed", preserved["status"].asText())
            assertEquals(90, preserved["score"].asInt())
            assertEquals(completedAt, preserved["completedAt"].asText())
            assertEquals("retake_required", afterFailure["status"].asText()) // Other test still failed.
            assertTrue(afterFailure["certificate"] == null || afterFailure["certificate"].isNull)
            val history = assessmentResultRepository.findAllByAssignmentStageIdOrderByAttemptNoDesc(UUID.fromString(assessedId))
                .sortedBy { it.attemptNo }
            assertEquals(listOf(1, 2, 3), history.map { it.attemptNo })
            assertEquals(listOf(90, 80, 100), history.map { it.score })
            assertEquals(listOf(true, true, false), history.map { it.passed })
            assertTrue(history.all { it.sourceType.name == type })

            val recovered = recordResult(assignmentId, otherId, 80, true)
            assertTrue(recovered["status"].asText() !in setOf("completed", "retake_required"))
            mockMvc.perform(patch("/api/mobile/training/assignments/$assignmentId/stages/$materialId")
                .header("Authorization", pharmacistBearer).contentType(MediaType.APPLICATION_JSON)
                .content("""{"progressPct":100}"""))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.score").value(85))
                .andExpect(jsonPath("$.certificate.score").value(85))
            assertEquals(85, certificateRepository.findByAssignmentId(UUID.fromString(assignmentId))!!.score)
        }
    }

    @Test
    fun `failed scored retry with successful history does not require retake when no other stage failed`() {
        val assignment = createAssessmentRoute("test")
        val assignmentId = assignment["id"].asText()
        val stageId = assignment["stages"][0]["id"].asText()
        recordResult(assignmentId, stageId, 90, true)
        val result = recordResult(assignmentId, stageId, 20, false)
        assertEquals("completed", result["stages"][0]["status"].asText())
        assertEquals(90, result["stages"][0]["score"].asInt())
        assertTrue(result["status"].asText() !in setOf("completed", "retake_required"))
        assertNull(certificateRepository.findByAssignmentId(UUID.fromString(assignmentId)))
    }

    @Test
    fun `manual review retains latest explicit decision instead of a previous successful grade`() {
        val assignment = createAssessmentRoute("manual_review")
        val assignmentId = assignment["id"].asText()
        val stageId = assignment["stages"][0]["id"].asText()
        recordResult(assignmentId, stageId, 90, true)
        val result = recordResult(assignmentId, stageId, 100, false)
        assertEquals("failed", result["stages"][0]["status"].asText())
        assertEquals(100, result["stages"][0]["score"].asInt())
        assertTrue(result["stages"][0]["completedAt"] == null || result["stages"][0]["completedAt"].isNull)
        assertEquals("retake_required", result["status"].asText())
        assertEquals(listOf(false, true), assessmentResultRepository
            .findAllByAssignmentStageIdOrderByAttemptNoDesc(UUID.fromString(stageId)).map { it.passed })
        assertNull(certificateRepository.findByAssignmentId(UUID.fromString(assignmentId)))
    }

    @Test
    fun `legacy completed stage without successful assessment history does not invent a retained grade`() {
        val assignment = createAssessmentRoute("test")
        val assignmentId = assignment["id"].asText()
        val stageId = UUID.fromString(assignment["stages"][0]["id"].asText())
        val legacy = assignmentStageRepository.findById(stageId).orElseThrow()
        legacy.status = TrainingStageStatus.completed
        legacy.score = 100
        legacy.attemptsUsed = 1
        legacy.completedAt = Instant.now()
        assignmentStageRepository.saveAndFlush(legacy)
        val result = recordResult(assignmentId, stageId.toString(), 40, false)
        assertEquals("failed", result["stages"][0]["status"].asText())
        assertEquals(40, result["stages"][0]["score"].asInt())
        val history = assessmentResultRepository.findAllByAssignmentStageIdOrderByAttemptNoDesc(stageId)
        assertEquals(1, history.size)
        assertEquals(2, history.single().attemptNo)
        assertEquals(false, history.single().passed)
        assertNull(certificateRepository.findByAssignmentId(UUID.fromString(assignmentId)))
    }

    private fun createAssessmentRoute(type: String): JsonNode {
        val program = objectMapper.readTree(mockMvc.perform(post("/api/admin/training/programs")
            .header("Authorization", adminBearer).contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"Synthetic best result $type","allowedFormats":["online"],"status":"published","completionBonus":0,
                 "stages":[
                   {"key":"assessed","type":"$type","title":"Assessed stage","order":0,"passingScore":80,"maxAttempts":4},
                   {"key":"other","type":"test","title":"Other required test","order":1,"passingScore":80,"maxAttempts":4},
                   {"key":"material","type":"material","title":"Remaining material","order":2,"contentUrl":"https://example.org/material"}
                 ]}
            """.trimIndent())).andExpect(status().isOk).andReturn().response.contentAsString)
        val programId = program["id"].asText()
        return objectMapper.readTree(mockMvc.perform(post("/api/admin/training/assignments")
            .header("Authorization", adminBearer).contentType(MediaType.APPLICATION_JSON)
            .content("""{"programId":"$programId","pharmacistIds":["ph_training"],"format":"online"}"""))
            .andExpect(status().isOk).andReturn().response.contentAsString)["assignments"][0]
    }

    private fun recordResult(assignmentId: String, stageId: String, score: Int, passed: Boolean): JsonNode =
        objectMapper.readTree(mockMvc.perform(patch("/api/admin/training/assignments/$assignmentId/stages/$stageId/result")
            .header("Authorization", adminBearer).contentType(MediaType.APPLICATION_JSON)
            .content("""{"score":$score,"passed":$passed,"feedback":"Synthetic attempt"}"""))
            .andExpect(status().isOk).andReturn().response.contentAsString)

    private fun createPublishedProgram(): JsonNode {
        val body = """
            {
              "name":"Безопасная рекомендация продукта",
              "shortDescription":"Онлайн-курс и итоговый тест",
              "allowedFormats":["online","hybrid"],
              "status":"published",
              "onlineCourseId":"crs_training",
              "passingScore":80,
              "maxAttempts":3,
              "completionBonus":750
            }
        """.trimIndent()
        val response = mockMvc.perform(
            post("/api/admin/training/programs")
                .header("Authorization", adminBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.version").value(1))
            .andExpect(jsonPath("$.stages.length()").value(3))
            .andReturn().response.contentAsString
        return objectMapper.readTree(response)
    }

    private fun login(): LoginResponse {
        val response = mockMvc.perform(
            post("/api/admin/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(LoginRequest("training@inkar.kz", "strong-pass"))),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, LoginResponse::class.java)
    }
}
