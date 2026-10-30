package kz.epharm.training

import com.fasterxml.jackson.databind.ObjectMapper
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.entity.AdminUserEntity
import kz.epharm.auth.repository.AdminUserRepository
import kz.epharm.auth.service.JwtService
import kz.epharm.lms.entity.CourseEntity
import kz.epharm.lms.entity.CourseLessonEntity
import kz.epharm.lms.entity.CourseLessonKind
import kz.epharm.lms.entity.CourseQuizQuestion
import kz.epharm.lms.entity.CourseStatus
import kz.epharm.lms.repository.CourseLessonRepository
import kz.epharm.lms.repository.CourseRepository
import kz.epharm.pharmacies.entity.ChainEntity
import kz.epharm.pharmacies.entity.PharmacyEntity
import kz.epharm.pharmacies.repository.ChainRepository
import kz.epharm.pharmacies.repository.PharmacyRepository
import kz.epharm.pharmacists.entity.PharmacistEntity
import kz.epharm.pharmacists.entity.PharmacistStatus
import kz.epharm.pharmacists.repository.PharmacistRepository
import kz.epharm.training.domain.TrainingAssignmentStatus
import kz.epharm.training.domain.TrainingProgramStatus
import kz.epharm.training.domain.TrainingStageStatus
import kz.epharm.training.domain.TrainingStageType
import kz.epharm.training.entity.TrainingAssignmentEntity
import kz.epharm.training.entity.TrainingAssignmentStageEntity
import kz.epharm.training.entity.TrainingLessonProgressEntity
import kz.epharm.training.entity.TrainingProgramEntity
import kz.epharm.training.entity.TrainingProgramStageEntity
import kz.epharm.training.entity.TrainingProgramVersionEntity
import kz.epharm.training.repository.TrainingAssignmentRepository
import kz.epharm.training.repository.TrainingAssignmentStageRepository
import kz.epharm.training.repository.TrainingLessonProgressRepository
import kz.epharm.training.repository.TrainingProgramRepository
import kz.epharm.training.repository.TrainingProgramStageRepository
import kz.epharm.training.repository.TrainingProgramVersionRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** Real HTTP/service transactions; the control transaction only delays a stage row write. */
@SpringBootTest(properties = ["spring.datasource.hikari.maximum-pool-size=8"])
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class TrainingAttemptConcurrencyIntegrationTest {
    companion object {
        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("training_attempt_concurrency")
            .withUsername("epharm").withPassword("synthetic-local-test-only").apply { start() }

        @JvmStatic @DynamicPropertySource fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
        }
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var transactions: PlatformTransactionManager
    @Autowired lateinit var jwt: JwtService
    @Autowired lateinit var users: AdminUserRepository
    @Autowired lateinit var chains: ChainRepository
    @Autowired lateinit var pharmacies: PharmacyRepository
    @Autowired lateinit var pharmacists: PharmacistRepository
    @Autowired lateinit var courses: CourseRepository
    @Autowired lateinit var lessons: CourseLessonRepository
    @Autowired lateinit var programs: TrainingProgramRepository
    @Autowired lateinit var versions: TrainingProgramVersionRepository
    @Autowired lateinit var definitions: TrainingProgramStageRepository
    @Autowired lateinit var assignments: TrainingAssignmentRepository
    @Autowired lateinit var stages: TrainingAssignmentStageRepository
    @Autowired lateinit var progress: TrainingLessonProgressRepository

    private data class Fixture(
        val assignmentId: UUID,
        val stageId: UUID,
        val quizId: String,
        val materialId: String,
        val bearer: String,
        val adminBearer: String,
        val initialAttempts: Int,
    ) {
        val lessonPath get() = "/api/mobile/training/assignments/$assignmentId/stages/$stageId/lessons/$quizId"
        val lockKey get() = "training-attempt:$assignmentId"
    }
    private data class Response(val status: Int, val body: String)
    private data class SavedProgress(val score: Int, val attempts: Int)
    private data class SavedStage(val score: Int, val attempts: Int)

    @ParameterizedTest
    @CsvSource("true,4", "true,0", "false,4", "false,0")
    fun `concurrent quiz requests retain the best score and count every accepted attempt`(
        existingProgress: Boolean,
        firstCorrect: Int,
    ) {
        val fixture = fixture(existingProgress = existingProgress)
        withGate(stageId = fixture.stageId) { pool, release ->
            val first = pool.submit<Response> { quiz(fixture, firstCorrect) }
            awaitBlockedWriters(1)
            val second = pool.submit<Response> { quiz(fixture, 5) }
            awaitBlockedWriters(2)
            release.countDown()
            assertOk(first.get(15, TimeUnit.SECONDS))
            assertOk(second.get(15, TimeUnit.SECONDS))
        }
        assertThat(savedProgress(fixture)).isEqualTo(SavedProgress(100, fixture.initialAttempts + 2))
        assertThat(savedStage(fixture).score).isEqualTo(100)
        assertThat(jdbc.queryForObject("select status from training_assignments where id=?",
            String::class.java, fixture.assignmentId)).isNotEqualTo("completed")
    }

    @Test
    fun `concurrent lesson completion uses the committed quiz grade for assignment and certificate`() {
        val fixture = fixture(existingProgress = true)
        withGate(stageId = fixture.stageId) { pool, release ->
            val higher = pool.submit<Response> { quiz(fixture, 5) }
            awaitBlockedWriters(1)
            val completion = pool.submit<Response> {
                val response = mvc.perform(patch("/api/mobile/training/assignments/${fixture.assignmentId}/stages/${fixture.stageId}/lessons/${fixture.materialId}")
                    .header("Authorization", fixture.bearer).contentType(MediaType.APPLICATION_JSON)
                    .content("""{"progressPct":100}""")).andReturn().response
                Response(response.status, response.contentAsString)
            }
            awaitBlockedWriters(2)
            release.countDown()
            assertOk(higher.get(15, TimeUnit.SECONDS))
            val completed = completion.get(15, TimeUnit.SECONDS)
            assertOk(completed)
            val assignment = mapper.readTree(completed.body)
            assertThat(assignment["status"].asText()).isEqualTo("completed")
            assertThat(assignment["score"].asInt()).isEqualTo(100)
            assertThat(assignment["certificate"]["score"].asInt()).isEqualTo(100)
        }
        assertThat(savedProgress(fixture)).isEqualTo(SavedProgress(100, 2))
        assertThat(savedStage(fixture).score).isEqualTo(100)
        assertThat(jdbc.queryForObject("select status from training_assignments where id=?",
            String::class.java, fixture.assignmentId)).isEqualTo("completed")
        assertThat(jdbc.queryForObject("select score from training_assignments where id=?",
            Int::class.java, fixture.assignmentId)).isEqualTo(100)
        assertThat(jdbc.queryForObject("select count(*) from training_certificates where assignment_id=?",
            Long::class.java, fixture.assignmentId)).isEqualTo(1L)
        assertThat(jdbc.queryForObject("select score from training_certificates where assignment_id=?",
            Int::class.java, fixture.assignmentId)).isEqualTo(100)
    }

    @Test
    fun `queued quiz rejects a completed assignment without another attempt certificate or reward`() {
        val fixture = fixture(existingProgress = true, finalQuiz = true)
        withGate(stageId = fixture.stageId) { pool, release ->
            val higher = pool.submit<Response> { quiz(fixture, 5) }
            awaitBlockedWriters(1)
            val lower = pool.submit<Response> { quiz(fixture, 4) }
            awaitBlockedWriters(2)
            release.countDown()
            assertOk(higher.get(15, TimeUnit.SECONDS))
            val rejected = lower.get(15, TimeUnit.SECONDS)
            assertThat(rejected.status).describedAs(rejected.body).isEqualTo(409)
        }
        assertThat(savedProgress(fixture)).isEqualTo(SavedProgress(100, 2))
        assertThat(jdbc.queryForObject("select count(*) from training_certificates where assignment_id=?",
            Long::class.java, fixture.assignmentId)).isEqualTo(1L)
        assertThat(jdbc.queryForObject("select count(*) from training_rewards where assignment_id=?",
            Long::class.java, fixture.assignmentId)).isEqualTo(1L)
        assertThat(jdbc.queryForObject("select count(*) from pharmacist_wallet_entries where source='training_bonus' and source_id=?",
            Long::class.java, fixture.assignmentId.toString())).isEqualTo(1L)
    }

    @Test
    fun `an assignment advisory lock does not serialize an unrelated assignment`() {
        val held = fixture(existingProgress = true)
        val independent = fixture(existingProgress = true)
        withGate(advisoryKey = held.lockKey) { pool, release ->
            val waiting = pool.submit<Response> { quiz(held, 5) }
            awaitBlockedWriters(1)
            assertOk(pool.submit<Response> { quiz(independent, 5) }.get(15, TimeUnit.SECONDS))
            assertThat(waiting.isDone).isFalse()
            assertThat(savedProgress(held)).isEqualTo(SavedProgress(80, 1))
            assertThat(savedProgress(independent)).isEqualTo(SavedProgress(100, 2))
            release.countDown()
            assertOk(waiting.get(15, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `invalid quiz rolls back and releases its transaction advisory lock`() {
        val fixture = fixture(existingProgress = true)
        val rejected = quiz(fixture, answers = mapOf("q1" to 0))
        assertThat(rejected.status).describedAs(rejected.body).isEqualTo(400)
        assertThat(savedProgress(fixture)).isEqualTo(SavedProgress(80, 1))
        assertThat(grantedAdvisoryLocks(fixture.lockKey)).isZero()
        assertOk(quiz(fixture, 5))
        assertThat(savedProgress(fixture)).isEqualTo(SavedProgress(100, 2))
        assertThat(grantedAdvisoryLocks(fixture.lockKey)).isZero()
    }

    @Test
    fun `parallel administrative assessments get distinct attempts preserve best and enforce the limit`() {
        val fixture = fixture(existingProgress = false, manualAssessment = true)
        withGate(stageId = fixture.stageId) { pool, release ->
            val first = pool.submit<Response> { assessment(fixture, 80) }
            awaitBlockedWriters(1)
            val second = pool.submit<Response> { assessment(fixture, 100) }
            awaitBlockedWriters(2)
            release.countDown()
            assertOk(first.get(15, TimeUnit.SECONDS))
            assertOk(second.get(15, TimeUnit.SECONDS))
        }
        assertThat(savedStage(fixture)).isEqualTo(SavedStage(100, 2))
        assertThat(jdbc.queryForList("select attempt_no from training_assessment_results where assignment_stage_id=? order by attempt_no",
            Int::class.java, fixture.stageId)).containsExactly(1, 2)
        val rejected = assessment(fixture, 100)
        assertThat(rejected.status).describedAs(rejected.body).isEqualTo(409)
        assertThat(savedStage(fixture)).isEqualTo(SavedStage(100, 2))
    }

    private fun fixture(
        existingProgress: Boolean,
        finalQuiz: Boolean = false,
        manualAssessment: Boolean = false,
    ): Fixture = TransactionTemplate(transactions).execute {
        val suffix = UUID.randomUUID().toString().take(8)
        val personDigits = (UUID.randomUUID().leastSignificantBits and Long.MAX_VALUE)
            .toString().takeLast(10).padStart(10, '0')
        val author = users.saveAndFlush(AdminUserEntity(email = "attempt-$suffix@example.invalid", name = "Synthetic author",
            passwordHash = "synthetic-unused-hash", roleRaw = AdminRole.SYSTEM_ADMIN.name))
        val chain = chains.saveAndFlush(ChainEntity(id = "chain-$suffix", name = "Synthetic chain"))
        val pharmacy = pharmacies.saveAndFlush(PharmacyEntity(id = "pharmacy-$suffix", name = "Synthetic pharmacy",
            chainId = chain.id, chainName = chain.name, city = "Synthetic city", addr = "Synthetic address"))
        val learner = pharmacists.saveAndFlush(PharmacistEntity(id = "learner-$suffix", name = "Synthetic learner",
            iin = "99$personDigits", phone = "+7$personDigits", pharmacyId = pharmacy.id,
            pharmacyName = pharmacy.name, city = pharmacy.city, coursesTotal = 1).also { it.status = PharmacistStatus.active })
        val course = courses.saveAndFlush(CourseEntity(id = "course-$suffix", title = "Synthetic course",
            createdBy = author.id.toString(), lessons = 2, enrolled = 1).also { it.status = CourseStatus.published })
        val quiz = lessons.saveAndFlush(CourseLessonEntity(id = "quiz-$suffix", courseId = course.id,
            title = "Synthetic quiz", order = 0, required = true, quizPassingScore = 80,
            quizQuestions = (1..5).map { CourseQuizQuestion("q$it", "Synthetic question $it", listOf("A", "B"), 0) })
            .also { it.kind = CourseLessonKind.test })
        val material = lessons.saveAndFlush(CourseLessonEntity(id = "material-$suffix", courseId = course.id,
            title = "Required remaining material", order = 1, required = true).also { it.kind = CourseLessonKind.text })
        val program = programs.saveAndFlush(TrainingProgramEntity(name = "Synthetic program", createdBy = author.id)
            .also { it.status = TrainingProgramStatus.published })
        val version = versions.saveAndFlush(TrainingProgramVersionEntity(programId = program.id,
            onlineCourseId = course.id, completionBonus = if (finalQuiz) 150 else 0, createdBy = author.id))
        val definition = definitions.saveAndFlush(TrainingProgramStageEntity(versionId = version.id,
            stageKey = if (manualAssessment) "assessment" else "online", title = "Assessed stage", required = true,
            passingScore = if (manualAssessment) 80 else null, maxAttempts = if (manualAssessment) 2 else null)
            .also { it.type = if (manualAssessment) TrainingStageType.test else TrainingStageType.online_course })
        val assignment = assignments.saveAndFlush(TrainingAssignmentEntity(programVersionId = version.id,
            pharmacistId = learner.id, assignedBy = author.id, progressPct = if (existingProgress) 50 else 0,
            startedAt = Instant.now(), dueAt = Instant.now().plusSeconds(86_400))
            .also { it.status = TrainingAssignmentStatus.waiting_online })
        // Null startedAt makes failed quiz requests write this same stage, so the row gate also covers failures.
        val stage = stages.saveAndFlush(TrainingAssignmentStageEntity(assignmentId = assignment.id,
            programStageId = definition.id, progressPct = if (existingProgress) 50 else 0,
            score = if (existingProgress) 80 else null).also { it.status = TrainingStageStatus.available })
        if (manualAssessment) {
            val remaining = definitions.saveAndFlush(TrainingProgramStageEntity(versionId = version.id,
                stageKey = "remaining", title = "Remaining material", orderNo = 1, required = true))
            stages.saveAndFlush(TrainingAssignmentStageEntity(assignmentId = assignment.id, programStageId = remaining.id))
        }
        if (existingProgress) progress.saveAndFlush(TrainingLessonProgressEntity(assignmentId = assignment.id,
            assignmentStageId = stage.id, lessonId = quiz.id, progressPct = 100, quizScore = 80,
            quizAttempts = 1, completedAt = Instant.now()))
        if (finalQuiz) progress.saveAndFlush(TrainingLessonProgressEntity(assignmentId = assignment.id,
            assignmentStageId = stage.id, lessonId = material.id, progressPct = 100, completedAt = Instant.now()))
        Fixture(assignment.id, stage.id, quiz.id, material.id, "Bearer " + jwt.issuePharmacistToken(learner.id, learner.name, learner.phone),
            "Bearer " + jwt.issueAccessToken(author), if (existingProgress) 1 else 0)
    }!!

    private fun withGate(
        stageId: UUID? = null,
        advisoryKey: String? = null,
        body: (ExecutorService, CountDownLatch) -> Unit,
    ) {
        require((stageId == null) != (advisoryKey == null))
        val pool = Executors.newFixedThreadPool(3)
        val acquired = CountDownLatch(1)
        val release = CountDownLatch(1)
        val control = pool.submit {
            TransactionTemplate(transactions).executeWithoutResult {
                if (stageId != null) jdbc.queryForList("select id from training_assignment_stages where id=? for update", stageId)
                else jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(?, 0))", advisoryKey)
                acquired.countDown()
                check(release.await(15, TimeUnit.SECONDS)) { "Control transaction was not released" }
            }
        }
        try {
            check(acquired.await(15, TimeUnit.SECONDS)) { "Control lock was not acquired" }
            body(pool, release)
        } finally {
            release.countDown()
            try {
                control.get(15, TimeUnit.SECONDS)
            } finally {
                pool.shutdownNow()
                check(pool.awaitTermination(15, TimeUnit.SECONDS)) { "HTTP workers did not finish" }
            }
        }
    }

    private fun awaitBlockedWriters(expected: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var blocked = 0
        while (System.nanoTime() < deadline) {
            // transactionid locks have database=NULL in pg_locks; activity identifies the correct database.
            blocked = jdbc.queryForObject("""select count(*) from pg_stat_activity
                where datname=current_database() and pid<>pg_backend_pid() and wait_event_type='Lock'""",
                Int::class.java)!!
            if (blocked >= expected) return
            Thread.sleep(10)
        }
        assertThat(blocked).describedAs("Blocked HTTP database writers").isGreaterThanOrEqualTo(expected)
    }

    private fun quiz(fixture: Fixture, correct: Int): Response = quiz(fixture,
        (1..5).associate { "q$it" to if (it <= correct) 0 else 1 })

    private fun quiz(fixture: Fixture, answers: Map<String, Int>): Response {
        val response = mvc.perform(post("${fixture.lessonPath}/quiz").header("Authorization", fixture.bearer)
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(mapOf("answers" to answers))))
            .andReturn().response
        return Response(response.status, response.contentAsString)
    }

    private fun assessment(fixture: Fixture, score: Int): Response {
        val response = mvc.perform(patch("/api/admin/training/assignments/${fixture.assignmentId}/stages/${fixture.stageId}/result")
            .header("Authorization", fixture.adminBearer).contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(mapOf("score" to score, "feedback" to "Synthetic assessment"))))
            .andReturn().response
        return Response(response.status, response.contentAsString)
    }

    private fun assertOk(response: Response) {
        assertThat(response.status).describedAs(response.body).isEqualTo(200)
    }

    private fun savedProgress(fixture: Fixture): SavedProgress = jdbc.queryForObject(
        "select quiz_score,quiz_attempts from training_lesson_progress where assignment_stage_id=? and lesson_id=?",
        { rs, _ -> SavedProgress(rs.getInt(1), rs.getInt(2)) }, fixture.stageId, fixture.quizId)!!

    private fun savedStage(fixture: Fixture): SavedStage = jdbc.queryForObject(
        "select score,attempts_used from training_assignment_stages where id=?",
        { rs, _ -> SavedStage(rs.getInt(1), rs.getInt(2)) }, fixture.stageId)!!

    private fun grantedAdvisoryLocks(key: String): Int = jdbc.queryForObject("""select count(*) from pg_locks l
        join pg_stat_activity a on a.pid=l.pid where a.datname=current_database()
        and l.locktype='advisory' and l.granted and l.objsubid=1
        and l.classid=((hashtextextended(?,0) >> 32) & 4294967295)::oid
        and l.objid=(hashtextextended(?,0) & 4294967295)::oid""", Int::class.java, key, key)!!
}
