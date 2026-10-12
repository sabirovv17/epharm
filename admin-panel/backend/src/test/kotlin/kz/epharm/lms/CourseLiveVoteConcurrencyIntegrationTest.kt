package kz.epharm.lms

import kz.epharm.lms.entity.CourseEntity
import kz.epharm.lms.entity.CourseLessonEntity
import kz.epharm.lms.entity.CourseLessonKind
import kz.epharm.lms.entity.CourseLiveAuthorType
import kz.epharm.lms.entity.CourseLiveMessageKind
import kz.epharm.lms.entity.CourseLivePoll
import kz.epharm.lms.entity.CourseLivePollOption
import kz.epharm.lms.dto.UpdateCourseLessonRequest
import kz.epharm.lms.repository.CourseLessonRepository
import kz.epharm.lms.repository.CourseLivePollVoteRepository
import kz.epharm.lms.repository.CourseLiveMessageRepository
import kz.epharm.lms.repository.CourseRepository
import kz.epharm.lms.service.CourseLiveService
import kz.epharm.lms.service.CourseService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class CourseLiveVoteConcurrencyIntegrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("epharm_live_vote_test")
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

    @Autowired private lateinit var courses: CourseRepository
    @Autowired private lateinit var lessons: CourseLessonRepository
    @Autowired private lateinit var votes: CourseLivePollVoteRepository
    @Autowired private lateinit var messages: CourseLiveMessageRepository
    @Autowired private lateinit var live: CourseLiveService
    @Autowired private lateinit var courseService: CourseService

    @Test
    fun `concurrent first votes by one pharmacist create exactly one row without failures`() {
        val course = courses.saveAndFlush(CourseEntity(id = "crs_vote_race", title = "Гонка голосов"))
        val lesson = CourseLessonEntity(
            id = "cls_vote_race",
            courseId = course.id,
            title = "Прямой эфир",
            liveStartAt = Instant.parse("2026-10-15T14:00:00Z"),
            livePolls = listOf(
                CourseLivePoll(
                    id = "poll-1",
                    prompt = "Материал понятен?",
                    active = true,
                    options = listOf(CourseLivePollOption("yes", "Да"), CourseLivePollOption("no", "Нет")),
                ),
            ),
        ).also { it.kind = CourseLessonKind.live }
        lessons.saveAndFlush(lesson)

        val requests = 8
        val ready = CountDownLatch(requests)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(requests)
        try {
            val futures = (1..requests).map {
                executor.submit<Int> {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS))
                    live.vote(lesson.id, "poll-1", "yes", "pharmacist-1").polls.single().totalVotes
                }
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue()
            start.countDown()
            futures.forEach { assertThat(it.get(15, TimeUnit.SECONDS)).isEqualTo(1) }
            assertThat(votes.findAllByLessonId(lesson.id)).hasSize(1)
            assertThat(votes.findAllByLessonId(lesson.id).single().optionId).isEqualTo("yes")
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `concurrent vote and poll edit never leave an orphaned vote`() {
        val course = courses.saveAndFlush(CourseEntity(id = "crs_vote_edit_race", title = "Гонка редактора"))
        val lesson = CourseLessonEntity(
            id = "cls_vote_edit_race",
            courseId = course.id,
            title = "Прямой эфир",
            liveStartAt = Instant.parse("2026-10-15T14:00:00Z"),
            livePolls = listOf(
                CourseLivePoll(
                    id = "poll-1",
                    prompt = "Материал понятен?",
                    active = true,
                    options = listOf(CourseLivePollOption("yes", "Да"), CourseLivePollOption("no", "Нет")),
                ),
            ),
        ).also { it.kind = CourseLessonKind.live }
        lessons.saveAndFlush(lesson)

        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val vote = executor.submit<Boolean> {
                ready.countDown()
                check(start.await(10, TimeUnit.SECONDS))
                runCatching { live.vote(lesson.id, "poll-1", "yes", "pharmacist-1") }.isSuccess
            }
            val edit = executor.submit<Boolean> {
                ready.countDown()
                check(start.await(10, TimeUnit.SECONDS))
                runCatching {
                    courseService.updateLesson(
                        course.id,
                        lesson.id,
                        UpdateCourseLessonRequest(livePolls = emptyList()),
                    )
                }.isSuccess
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue()
            start.countDown()
            val voteSucceeded = vote.get(20, TimeUnit.SECONDS)
            val editSucceeded = edit.get(20, TimeUnit.SECONDS)
            assertThat(voteSucceeded && editSucceeded).isFalse()

            val persistedPolls = lessons.findById(lesson.id).orElseThrow().livePolls
            val persistedVotes = votes.findAllByLessonId(lesson.id)
            if (persistedVotes.isNotEmpty()) {
                assertThat(persistedPolls).hasSize(1)
                assertThat(persistedPolls.single().id).isEqualTo("poll-1")
                assertThat(persistedPolls.single().options.map { it.id }).contains("yes")
                assertThat(persistedVotes.single().optionId).isEqualTo("yes")
            } else {
                assertThat(persistedPolls).isEmpty()
            }
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `concurrent message and type change preserve the interaction`() {
        val course = courses.saveAndFlush(CourseEntity(id = "crs_message_edit_race", title = "Гонка чата"))
        val lesson = lessons.saveAndFlush(
            CourseLessonEntity(
                id = "cls_message_edit_race",
                courseId = course.id,
                title = "Прямой эфир",
                liveStartAt = Instant.parse("2026-10-15T14:00:00Z"),
            ).also { it.kind = CourseLessonKind.live },
        )

        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val message = executor.submit<Boolean> {
                ready.countDown()
                check(start.await(10, TimeUnit.SECONDS))
                runCatching {
                    live.postMessage(
                        lesson.id,
                        "pharmacist-1",
                        "Фармацевт",
                        CourseLiveAuthorType.pharmacist,
                        CourseLiveMessageKind.question,
                        "Вопрос к ведущему",
                    )
                }.isSuccess
            }
            val edit = executor.submit<Boolean> {
                ready.countDown()
                check(start.await(10, TimeUnit.SECONDS))
                runCatching {
                    courseService.updateLesson(
                        course.id,
                        lesson.id,
                        UpdateCourseLessonRequest(kind = CourseLessonKind.text),
                    )
                }.isSuccess
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue()
            start.countDown()
            val messageSucceeded = message.get(20, TimeUnit.SECONDS)
            val editSucceeded = edit.get(20, TimeUnit.SECONDS)
            assertThat(messageSucceeded xor editSucceeded).isTrue()

            val persistedLesson = lessons.findById(lesson.id).orElseThrow()
            val hasMessage = messages.existsByLessonId(lesson.id)
            if (hasMessage) {
                assertThat(persistedLesson.kind).isEqualTo(CourseLessonKind.live)
            } else {
                assertThat(persistedLesson.kind).isEqualTo(CourseLessonKind.text)
            }
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }
}
