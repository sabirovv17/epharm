package kz.epharm.lms

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kz.epharm.lms.entity.CourseLessonEntity
import kz.epharm.lms.entity.CourseLessonKind
import kz.epharm.lms.entity.CourseLiveAuthorType
import kz.epharm.lms.entity.CourseLiveMessageEntity
import kz.epharm.lms.entity.CourseLiveMessageKind
import kz.epharm.lms.entity.CourseLivePoll
import kz.epharm.lms.entity.CourseLivePollOption
import kz.epharm.lms.entity.CourseLivePollVoteEntity
import kz.epharm.lms.entity.CourseLiveProvider
import kz.epharm.lms.entity.CourseLiveReactionEntity
import kz.epharm.lms.entity.CourseLiveStatus
import kz.epharm.lms.repository.CourseLessonRepository
import kz.epharm.lms.repository.CourseLiveMessageRepository
import kz.epharm.lms.repository.CourseLivePollVoteRepository
import kz.epharm.lms.repository.CourseLiveReactionRepository
import kz.epharm.lms.service.CourseLiveService
import kz.epharm.shared.error.AppException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Optional
import java.util.UUID

class CourseLiveServiceTest {
    private val access = mockk<kz.epharm.training.access.TrainingAdministrativeAccess>(relaxed = true)
    private val lessonRepository = mockk<CourseLessonRepository>()
    private val messageRepository = mockk<CourseLiveMessageRepository>()
    private val voteRepository = mockk<CourseLivePollVoteRepository>()
    private val reactionRepository = mockk<CourseLiveReactionRepository>()

    @Test
    fun `room shows the latest messages in chronological order`() {
        val lesson = CourseLessonEntity(id = "live-recent", courseId = "course-1")
            .also { it.kind = CourseLessonKind.live }
        val older = CourseLiveMessageEntity(
            id = UUID.randomUUID(), lessonId = lesson.id, body = "older",
            createdAt = Instant.parse("2026-10-12T09:00:00Z"),
        )
        val newer = CourseLiveMessageEntity(
            id = UUID.randomUUID(), lessonId = lesson.id, body = "newer",
            createdAt = Instant.parse("2026-10-12T09:01:00Z"),
        )
        every { lessonRepository.findById(lesson.id) } returns Optional.of(lesson)
        every {
            messageRepository.findTop200ByLessonIdAndHiddenFalseOrderByCreatedAtDescIdDesc(lesson.id)
        } returns listOf(newer, older)
        every { voteRepository.findAllByLessonId(lesson.id) } returns emptyList()
        every { reactionRepository.findAllByLessonIdAndCreatedAtAfter(lesson.id, any()) } returns emptyList()

        val room = CourseLiveService(access, lessonRepository, messageRepository, voteRepository, reactionRepository)
            .room(lesson.id, "pharmacist-1")

        assertThat(room.messages.map { it.body }).containsExactly("older", "newer")
        verify(exactly = 1) {
            messageRepository.findTop200ByLessonIdAndHiddenFalseOrderByCreatedAtDescIdDesc(lesson.id)
        }
    }

    @Test
    fun `status transitions stamp actual start and end once`() {
        val scheduled = Instant.parse("2030-10-12T09:00:00Z")
        val plannedEnd = Instant.parse("2030-10-12T10:00:00Z")
        val lesson = CourseLessonEntity(
            id = "live-timing", courseId = "course-1",
            externalUrl = "https://www.youtube.com/watch?v=demo",
            liveStartAt = scheduled, liveEndAt = plannedEnd,
        ).also {
            it.kind = CourseLessonKind.live
            it.liveProvider = CourseLiveProvider.youtube
            it.liveStatus = CourseLiveStatus.scheduled
        }
        every { lessonRepository.findById(lesson.id) } returns Optional.of(lesson)
        every { lessonRepository.findByIdForUpdate(lesson.id) } returns lesson
        every { lessonRepository.save(lesson) } returns lesson
        every {
            messageRepository.findTop200ByLessonIdAndHiddenFalseOrderByCreatedAtDescIdDesc(lesson.id)
        } returns emptyList()
        every { voteRepository.findAllByLessonId(lesson.id) } returns emptyList()
        every { reactionRepository.findAllByLessonIdAndCreatedAtAfter(lesson.id, any()) } returns emptyList()
        val service = CourseLiveService(access, lessonRepository, messageRepository, voteRepository, reactionRepository)

        val beforeStart = Instant.now()
        service.changeStatus("course-1", lesson.id, CourseLiveStatus.live, "admin-1")
        val afterStart = Instant.now()
        assertThat(lesson.liveStartAt).isBetween(beforeStart, afterStart)
        assertThat(lesson.liveEndAt).isNull()

        val actualStart = lesson.liveStartAt
        service.changeStatus("course-1", lesson.id, CourseLiveStatus.live, "admin-1")
        assertThat(lesson.liveStartAt).isEqualTo(actualStart)

        val beforeEnd = Instant.now()
        service.changeStatus("course-1", lesson.id, CourseLiveStatus.ended, "admin-1")
        val afterEnd = Instant.now()
        assertThat(lesson.liveEndAt).isBetween(beforeEnd, afterEnd)
        val actualEnd = lesson.liveEndAt
        service.changeStatus("course-1", lesson.id, CourseLiveStatus.ended, "admin-1")
        assertThat(lesson.liveEndAt).isEqualTo(actualEnd)
        assertThrows(AppException::class.java) {
            service.changeStatus("course-1", lesson.id, CourseLiveStatus.live, "admin-1")
        }
    }

    @Test
    fun `admin live route cannot access a lesson from another course`() {
        val lesson = CourseLessonEntity(id = "live-1", courseId = "course-1").also {
            it.kind = CourseLessonKind.live
        }
        every { lessonRepository.findById("live-1") } returns Optional.of(lesson)
        val service = CourseLiveService(access, lessonRepository, messageRepository, voteRepository, reactionRepository)

        service.requireCourseLesson("course-1", "live-1")
        assertThrows(AppException::class.java) {
            service.requireCourseLesson("course-2", "live-1")
        }
    }

    @Test
    fun `an unsafe stored link cannot start a live session`() {
        val lesson = CourseLessonEntity(
            id = "live-unsafe",
            courseId = "course-1",
            externalUrl = "javascript:alert(1)",
        ).also { it.kind = CourseLessonKind.live }
        every { lessonRepository.findById("live-unsafe") } returns Optional.of(lesson)
        every { lessonRepository.findByIdForUpdate("live-unsafe") } returns lesson
        val service = CourseLiveService(access, lessonRepository, messageRepository, voteRepository, reactionRepository)

        assertThrows(AppException::class.java) {
            service.changeStatus("course-1", "live-unsafe", CourseLiveStatus.live, "admin-1")
        }
    }

    @Test
    fun `Q and A polls and reactions work independently from public chat`() {
        val lesson = CourseLessonEntity(
            id = "live-1",
            courseId = "course-1",
            title = "Разбор кейсов",
            description = "Эфир с тренером",
            externalUrl = "https://www.youtube.com/watch?v=demo",
            liveStartAt = Instant.parse("2026-10-11T14:00:00Z"),
            livePolls = listOf(
                CourseLivePoll(
                    id = "poll-1",
                    prompt = "Материал понятен?",
                    active = true,
                    options = listOf(
                        CourseLivePollOption("yes", "Да"),
                        CourseLivePollOption("no", "Нужен пример"),
                    ),
                ),
            ),
        ).also {
            it.kind = CourseLessonKind.live
            it.liveProvider = CourseLiveProvider.youtube
            it.liveStatus = CourseLiveStatus.live
            it.liveChatEnabled = false
            it.liveQaEnabled = true
            it.liveReactionsEnabled = true
        }
        val messages = mutableListOf<CourseLiveMessageEntity>()
        val votes = mutableListOf<CourseLivePollVoteEntity>()
        val reactions = mutableListOf<CourseLiveReactionEntity>()

        every { lessonRepository.findById("live-1") } returns Optional.of(lesson)
        every { lessonRepository.findByIdForUpdate("live-1") } returns lesson
        every { messageRepository.save(any()) } answers {
            firstArg<CourseLiveMessageEntity>().also(messages::add)
        }
        every {
            messageRepository.findTop200ByLessonIdAndHiddenFalseOrderByCreatedAtDescIdDesc("live-1")
        } answers { messages.filterNot { it.hidden } }
        every { voteRepository.findAllByLessonId("live-1") } answers { votes.toList() }
        every { voteRepository.upsertVote(any(), "live-1", "poll-1", "pharmacist-1", any()) } answers {
            val optionId = arg<String>(4)
            val existing = votes.firstOrNull()
            if (existing == null) {
                votes += CourseLivePollVoteEntity(
                    id = firstArg(), lessonId = "live-1", pollId = "poll-1",
                    voterId = "pharmacist-1", optionId = optionId,
                )
            } else {
                existing.optionId = optionId
            }
            1
        }
        every { reactionRepository.save(any()) } answers {
            firstArg<CourseLiveReactionEntity>().also(reactions::add)
        }
        every {
            reactionRepository.findAllByLessonIdAndCreatedAtAfter("live-1", any())
        } answers { reactions.toList() }

        val service = CourseLiveService(
            access,
            lessonRepository,
            messageRepository,
            voteRepository,
            reactionRepository,
        )

        val afterQuestion = service.postMessage(
            "live-1",
            "pharmacist-1",
            "Василий",
            CourseLiveAuthorType.pharmacist,
            CourseLiveMessageKind.question,
            "Покажите пример",
        )
        assertThat(afterQuestion.messages.single().kind).isEqualTo(CourseLiveMessageKind.question)
        assertThat(afterQuestion.participantCount).isEqualTo(1)

        val afterVote = service.vote("live-1", "poll-1", "yes", "pharmacist-1")
        assertThat(afterVote.polls.single().myOptionId).isEqualTo("yes")
        assertThat(afterVote.polls.single().options.first().percent).isEqualTo(100)

        val afterReaction = service.react("live-1", "pharmacist-1", "clap")
        assertThat(afterReaction.reactions["clap"]).isEqualTo(1)

        assertThrows(AppException::class.java) {
            service.postMessage(
                "live-1",
                "pharmacist-1",
                "Василий",
                CourseLiveAuthorType.pharmacist,
                CourseLiveMessageKind.comment,
                "Обычный комментарий",
            )
        }
    }
}
