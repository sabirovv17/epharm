package kz.epharm.lms

import io.mockk.every
import io.mockk.mockk
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

class CourseLiveServiceTest {
    private val lessonRepository = mockk<CourseLessonRepository>()
    private val messageRepository = mockk<CourseLiveMessageRepository>()
    private val voteRepository = mockk<CourseLivePollVoteRepository>()
    private val reactionRepository = mockk<CourseLiveReactionRepository>()

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
        every { messageRepository.save(any()) } answers {
            firstArg<CourseLiveMessageEntity>().also(messages::add)
        }
        every {
            messageRepository.findTop200ByLessonIdAndHiddenFalseOrderByCreatedAtAsc("live-1")
        } answers { messages.filterNot { it.hidden } }
        every { voteRepository.findAllByLessonId("live-1") } answers { votes.toList() }
        every {
            voteRepository.findByLessonIdAndPollIdAndVoterId("live-1", "poll-1", "pharmacist-1")
        } answers { votes.firstOrNull() }
        every { voteRepository.save(any()) } answers {
            firstArg<CourseLivePollVoteEntity>().also { vote ->
                if (votes.none { it.id == vote.id }) votes += vote
            }
        }
        every { reactionRepository.save(any()) } answers {
            firstArg<CourseLiveReactionEntity>().also(reactions::add)
        }
        every {
            reactionRepository.findAllByLessonIdAndCreatedAtAfter("live-1", any())
        } answers { reactions.toList() }

        val service = CourseLiveService(
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
