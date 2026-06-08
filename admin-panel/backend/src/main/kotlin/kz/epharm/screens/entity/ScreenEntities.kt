package kz.epharm.screens.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import java.time.Instant

enum class PlaylistStatus { active, draft, archived }
enum class SlideKind { video, image }

@Entity
@Table(name = "playlists")
class PlaylistEntity(
    @Id
    @Column(name = "id", nullable = false, length = 64)
    var id: String = "",

    @Column(name = "name", nullable = false)
    var name: String = "",

    @Column(name = "status", nullable = false, length = 32)
    var statusRaw: String = PlaylistStatus.draft.name,

    @Column(name = "slides_count", nullable = false)
    var slidesCount: Int = 0,

    @Column(name = "duration_sec", nullable = false)
    var durationSec: Int = 0,

    @Column(name = "pharmacies", nullable = false)
    var pharmacies: Int = 0,

    // Назначение плейлиста (V016): null = все экраны (глобальный), 'ph_x' = конкретная аптека.
    @Column(name = "pharmacy_id", length = 64)
    var pharmacyId: String? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    var status: PlaylistStatus
        get() = PlaylistStatus.valueOf(statusRaw)
        set(value) { statusRaw = value.name }

    @PrePersist
    fun onCreate() {
        val now = Instant.now()
        if (createdAt == Instant.EPOCH) createdAt = now
        updatedAt = now
    }

    @PreUpdate
    fun onUpdate() {
        updatedAt = Instant.now()
    }
}

@Entity
@Table(name = "slides")
class SlideEntity(
    @Id
    @Column(name = "id", nullable = false, length = 64)
    var id: String = "",

    @Column(name = "title", nullable = false)
    var title: String = "",

    @Column(name = "kind", nullable = false, length = 32)
    var kindRaw: String = SlideKind.image.name,

    @Column(name = "duration_sec", nullable = false)
    var durationSec: Int = 0,

    @Column(name = "media_url", nullable = false, length = 512)
    var mediaUrl: String = "",

    @Column(name = "playlist_id", length = 64)
    var playlistId: String? = null,

    @Column(name = "position", nullable = false)
    var position: Int = 0,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    var kind: SlideKind
        get() = SlideKind.valueOf(kindRaw)
        set(value) { kindRaw = value.name }

    @PrePersist
    fun onCreate() {
        val now = Instant.now()
        if (createdAt == Instant.EPOCH) createdAt = now
        updatedAt = now
    }

    @PreUpdate
    fun onUpdate() {
        updatedAt = Instant.now()
    }
}
