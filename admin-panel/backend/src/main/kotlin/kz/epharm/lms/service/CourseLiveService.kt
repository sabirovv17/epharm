package kz.epharm.lms.service

import kz.epharm.lms.dto.CourseLiveMessageDto
import kz.epharm.lms.dto.CourseLivePollOptionResultDto
import kz.epharm.lms.dto.CourseLivePollResultDto
import kz.epharm.lms.dto.CourseLiveRoomDto
import kz.epharm.lms.entity.CourseLessonEntity
import kz.epharm.lms.entity.CourseLessonKind
import kz.epharm.lms.entity.CourseLiveAuthorType
import kz.epharm.lms.entity.CourseLiveMessageEntity
import kz.epharm.lms.entity.CourseLiveMessageKind
import kz.epharm.lms.entity.CourseLiveProvider
import kz.epharm.lms.entity.CourseLiveReactionEntity
import kz.epharm.lms.entity.CourseLiveStatus
import kz.epharm.lms.repository.CourseLessonRepository
import kz.epharm.lms.repository.CourseLiveMessageRepository
import kz.epharm.lms.repository.CourseLivePollVoteRepository
import kz.epharm.lms.repository.CourseLiveReactionRepository
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Service
class CourseLiveService(
    private val lessonRepository: CourseLessonRepository,
    private val messageRepository: CourseLiveMessageRepository,
    private val voteRepository: CourseLivePollVoteRepository,
    private val reactionRepository: CourseLiveReactionRepository,
) {
    @Transactional(readOnly = true)
    fun requireCourseLesson(courseId: String, lessonId: String) {
        if (liveLesson(lessonId).courseId != courseId) throw notFound("Урок не найден")
    }

    @Transactional(readOnly = true)
    fun room(lessonId: String, actorId: String): CourseLiveRoomDto = mapRoom(liveLesson(lessonId), actorId)

    @Transactional
    fun postMessage(
        lessonId: String,
        actorId: String,
        actorName: String,
        authorType: CourseLiveAuthorType,
        kind: CourseLiveMessageKind,
        body: String,
    ): CourseLiveRoomDto {
        val lesson = liveLessonForUpdate(lessonId)
        if (kind == CourseLiveMessageKind.comment && !lesson.liveChatEnabled) {
            conflict("Чат этого эфира отключён")
        }
        if (kind == CourseLiveMessageKind.question && !lesson.liveQaEnabled) {
            conflict("Вопросы для этого эфира отключены")
        }
        val normalized = body.trim()
        if (normalized.isBlank()) invalid("Введите сообщение")
        messageRepository.save(
            CourseLiveMessageEntity(
                lessonId = lessonId,
                authorId = actorId,
                authorName = actorName.trim().take(255),
                body = normalized.take(2000),
            ).also {
                it.authorType = authorType
                it.kind = kind
            },
        )
        return mapRoom(lesson, actorId)
    }

    @Transactional
    fun vote(lessonId: String, pollId: String, optionId: String, voterId: String): CourseLiveRoomDto {
        val lesson = liveLessonForUpdate(lessonId)
        val poll = lesson.livePolls.firstOrNull { it.id == pollId }
            ?: throw notFound("Опрос не найден")
        if (!poll.active) conflict("Опрос уже закрыт")
        if (poll.options.none { it.id == optionId }) invalid("Вариант ответа не найден")
        voteRepository.upsertVote(UUID.randomUUID(), lessonId, pollId, voterId, optionId)
        return mapRoom(lesson, voterId)
    }

    @Transactional
    fun react(lessonId: String, actorId: String, reaction: String): CourseLiveRoomDto {
        val lesson = liveLessonForUpdate(lessonId)
        if (!lesson.liveReactionsEnabled) conflict("Реакции для этого эфира отключены")
        val normalized = reaction.trim().lowercase()
        if (normalized !in ALLOWED_REACTIONS) invalid("Неизвестная реакция")
        reactionRepository.save(
            CourseLiveReactionEntity(lessonId = lessonId, actorId = actorId, reaction = normalized),
        )
        return mapRoom(lesson, actorId)
    }

    @Transactional
    fun changeStatus(courseId: String, lessonId: String, status: CourseLiveStatus, actorId: String): CourseLiveRoomDto {
        val lesson = liveLessonForUpdate(lessonId)
        if (lesson.courseId != courseId) throw notFound("Урок не найден")
        if (status == CourseLiveStatus.live &&
            LiveStreamUrlPolicy.safeUrl(lesson.externalUrl, lesson.liveProvider ?: CourseLiveProvider.custom) == null
        ) {
            conflict("Перед запуском укажите безопасную HTTPS-ссылку на трансляцию")
        }
        val previousStatus = lesson.liveStatus ?: CourseLiveStatus.scheduled
        if (status != previousStatus) {
            if (status == CourseLiveStatus.live && previousStatus != CourseLiveStatus.scheduled) {
                conflict("Завершённый эфир нельзя запустить повторно")
            }
            if (status == CourseLiveStatus.ended && previousStatus != CourseLiveStatus.live) {
                conflict("Завершить можно только идущий эфир")
            }
            if (status == CourseLiveStatus.scheduled) {
                conflict("Начатый эфир нельзя вернуть в запланированные")
            }
            val now = Instant.now()
            if (status == CourseLiveStatus.live) {
                lesson.liveStartAt = now
                lesson.liveEndAt = null
            } else {
                lesson.liveEndAt = now
            }
            lesson.liveStatus = status
        }
        lessonRepository.save(lesson)
        return mapRoom(lesson, actorId)
    }

    @Transactional
    fun moderate(
        courseId: String,
        lessonId: String,
        messageId: UUID,
        pinned: Boolean?,
        hidden: Boolean?,
        actorId: String,
    ): CourseLiveRoomDto {
        val lesson = liveLesson(lessonId)
        if (lesson.courseId != courseId) throw notFound("Урок не найден")
        val message = messageRepository.findById(messageId).orElseThrow { notFound("Сообщение не найдено") }
        if (message.lessonId != lessonId) throw notFound("Сообщение не найдено")
        pinned?.let { message.pinned = it }
        hidden?.let { message.hidden = it }
        messageRepository.save(message)
        return mapRoom(lesson, actorId)
    }

    private fun mapRoom(lesson: CourseLessonEntity, actorId: String): CourseLiveRoomDto {
        val messages = messageRepository.findTop200ByLessonIdAndHiddenFalseOrderByCreatedAtDescIdDesc(lesson.id)
            .asReversed()
        val votes = voteRepository.findAllByLessonId(lesson.id)
        val votesByPoll = votes.groupBy { it.pollId }
        val polls = lesson.livePolls.map { poll ->
            val pollVotes = votesByPoll[poll.id].orEmpty()
            val total = pollVotes.size
            CourseLivePollResultDto(
                id = poll.id,
                prompt = poll.prompt,
                active = poll.active,
                totalVotes = total,
                myOptionId = pollVotes.firstOrNull { it.voterId == actorId }?.optionId,
                options = poll.options.map { option ->
                    val count = pollVotes.count { it.optionId == option.id }
                    CourseLivePollOptionResultDto(
                        id = option.id,
                        label = option.label,
                        votes = count,
                        percent = if (total == 0) 0 else ((count * 100.0) / total).toInt(),
                    )
                },
            )
        }
        val reactions = reactionRepository
            .findAllByLessonIdAndCreatedAtAfter(lesson.id, Instant.now().minus(REACTION_WINDOW))
            .groupingBy { it.reaction }
            .eachCount()
        return CourseLiveRoomDto(
            lessonId = lesson.id,
            title = lesson.title,
            description = lesson.description,
            provider = lesson.liveProvider ?: CourseLiveProvider.custom,
            status = lesson.liveStatus ?: CourseLiveStatus.scheduled,
            startsAt = lesson.liveStartAt,
            endsAt = lesson.liveEndAt,
            streamUrl = LiveStreamUrlPolicy.safeUrl(
                lesson.externalUrl,
                lesson.liveProvider ?: CourseLiveProvider.custom,
            ),
            chatEnabled = lesson.liveChatEnabled,
            qaEnabled = lesson.liveQaEnabled,
            reactionsEnabled = lesson.liveReactionsEnabled,
            participantCount = messages.map { it.authorId }.toSet().size,
            messages = messages.map {
                CourseLiveMessageDto(
                    id = it.id,
                    authorName = it.authorName,
                    authorType = it.authorType.name,
                    kind = it.kind,
                    body = it.body,
                    pinned = it.pinned,
                    mine = it.authorId == actorId,
                    createdAt = it.createdAt,
                )
            },
            polls = polls,
            reactions = reactions,
            serverTime = Instant.now(),
        )
    }

    private fun liveLesson(lessonId: String): CourseLessonEntity = lessonRepository.findById(lessonId).orElse(null)
        ?.takeIf { it.kind == CourseLessonKind.live }
        ?: throw notFound("Прямой эфир не найден")

    private fun liveLessonForUpdate(lessonId: String): CourseLessonEntity = lessonRepository.findByIdForUpdate(lessonId)
        ?.takeIf { it.kind == CourseLessonKind.live }
        ?: throw notFound("Прямой эфир не найден")

    private fun invalid(message: String): Nothing =
        throw AppException(ErrorCode.VALIDATION_FAILED, message, HttpStatus.BAD_REQUEST)

    private fun conflict(message: String): Nothing =
        throw AppException(ErrorCode.CONFLICT, message, HttpStatus.CONFLICT)

    private fun notFound(message: String): AppException =
        AppException(ErrorCode.NOT_FOUND, message, HttpStatus.NOT_FOUND)

    companion object {
        private val ALLOWED_REACTIONS = setOf("like", "heart", "clap", "wow", "question")
        private val REACTION_WINDOW: Duration = Duration.ofMinutes(10)
    }
}
