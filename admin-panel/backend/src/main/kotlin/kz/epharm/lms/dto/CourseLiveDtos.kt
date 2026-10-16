package kz.epharm.lms.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import kz.epharm.lms.entity.CourseLiveMessageKind
import kz.epharm.lms.entity.CourseLiveProvider
import kz.epharm.lms.entity.CourseLiveStatus
import java.time.Instant
import java.util.UUID

data class CourseLiveMessageDto(
    val id: UUID,
    val authorName: String,
    val authorType: String,
    val kind: CourseLiveMessageKind,
    val body: String,
    val pinned: Boolean,
    val mine: Boolean,
    val createdAt: Instant,
)

data class CourseLivePollOptionResultDto(
    val id: String,
    val label: String,
    val votes: Int,
    val percent: Int,
)

data class CourseLivePollResultDto(
    val id: String,
    val prompt: String,
    val active: Boolean,
    val options: List<CourseLivePollOptionResultDto>,
    val totalVotes: Int,
    val myOptionId: String?,
)

data class CourseLiveRoomDto(
    val lessonId: String,
    val title: String,
    val description: String,
    val provider: CourseLiveProvider,
    val status: CourseLiveStatus,
    val startsAt: Instant?,
    val endsAt: Instant?,
    val streamUrl: String?,
    val chatEnabled: Boolean,
    val qaEnabled: Boolean,
    val reactionsEnabled: Boolean,
    val participantCount: Int,
    val messages: List<CourseLiveMessageDto>,
    val polls: List<CourseLivePollResultDto>,
    val reactions: Map<String, Int>,
    val serverTime: Instant,
)

data class CreateCourseLiveMessageRequest(
    val kind: CourseLiveMessageKind = CourseLiveMessageKind.comment,
    @field:NotBlank
    @field:Size(max = 2000)
    val body: String,
)

data class CourseLiveVoteRequest(
    @field:NotBlank
    @field:Size(max = 64)
    val optionId: String,
)

data class CourseLiveReactionRequest(
    @field:NotBlank
    @field:Size(max = 24)
    val reaction: String,
)

data class CourseLiveStatusRequest(
    val status: CourseLiveStatus,
)

data class CourseLiveModerationRequest(
    val pinned: Boolean? = null,
    val hidden: Boolean? = null,
)
