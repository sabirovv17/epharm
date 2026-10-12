package kz.epharm.lms.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

enum class CourseLiveAuthorType { admin, pharmacist }

enum class CourseLiveMessageKind { comment, question }

@Entity
@Table(name = "course_live_messages")
class CourseLiveMessageEntity(
    @Id
    var id: UUID = UUID.randomUUID(),

    @Column(name = "lesson_id", nullable = false, length = 64)
    var lessonId: String = "",

    @Column(name = "author_type", nullable = false, length = 24)
    var authorTypeRaw: String = CourseLiveAuthorType.pharmacist.name,

    @Column(name = "author_id", nullable = false, length = 128)
    var authorId: String = "",

    @Column(name = "author_name", nullable = false)
    var authorName: String = "",

    @Column(name = "kind", nullable = false, length = 24)
    var kindRaw: String = CourseLiveMessageKind.comment.name,

    @Column(nullable = false, length = 2000)
    var body: String = "",

    @Column(nullable = false)
    var pinned: Boolean = false,

    @Column(nullable = false)
    var hidden: Boolean = false,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
) {
    var authorType: CourseLiveAuthorType
        get() = CourseLiveAuthorType.valueOf(authorTypeRaw)
        set(value) { authorTypeRaw = value.name }

    var kind: CourseLiveMessageKind
        get() = CourseLiveMessageKind.valueOf(kindRaw)
        set(value) { kindRaw = value.name }
}

@Entity
@Table(name = "course_live_poll_votes")
class CourseLivePollVoteEntity(
    @Id
    var id: UUID = UUID.randomUUID(),

    @Column(name = "lesson_id", nullable = false, length = 64)
    var lessonId: String = "",

    @Column(name = "poll_id", nullable = false, length = 64)
    var pollId: String = "",

    @Column(name = "voter_id", nullable = false, length = 128)
    var voterId: String = "",

    @Column(name = "option_id", nullable = false, length = 64)
    var optionId: String = "",

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
)

@Entity
@Table(name = "course_live_reactions")
class CourseLiveReactionEntity(
    @Id
    var id: UUID = UUID.randomUUID(),

    @Column(name = "lesson_id", nullable = false, length = 64)
    var lessonId: String = "",

    @Column(name = "actor_id", nullable = false, length = 128)
    var actorId: String = "",

    @Column(nullable = false, length = 24)
    var reaction: String = "",

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
)
