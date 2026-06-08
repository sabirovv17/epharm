package kz.epharm.appupdate.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * Релиз POSM-клиента (Windows). Касса опрашивает «текущий» релиз и сама обновляется, если
 * version новее установленной. is_current — ровно один текущий на платформу (выставляется
 * транзакционно в AppReleaseService.register).
 */
@Entity
@Table(name = "app_releases")
class AppReleaseEntity(
    @Id
    @Column(name = "id", nullable = false, length = 64)
    var id: String = "",

    @Column(name = "platform", nullable = false, length = 32)
    var platform: String = "win-x64",

    @Column(name = "version", nullable = false, length = 32)
    var version: String = "",

    @Column(name = "url", nullable = false, length = 512)
    var url: String = "",

    @Column(name = "sha256", nullable = false, length = 128)
    var sha256: String = "",

    @Column(name = "mandatory", nullable = false)
    var mandatory: Boolean = false,

    @Column(name = "notes", nullable = false, length = 1024)
    var notes: String = "",

    @Column(name = "is_current", nullable = false)
    var isCurrent: Boolean = false,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
)
