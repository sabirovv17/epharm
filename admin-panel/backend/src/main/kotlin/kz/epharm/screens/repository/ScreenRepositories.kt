package kz.epharm.screens.repository

import kz.epharm.screens.entity.PlaylistEntity
import kz.epharm.screens.entity.SlideEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface PlaylistRepository : JpaRepository<PlaylistEntity, String> {
    fun findAllByOrderByUpdatedAtDesc(): List<PlaylistEntity>
    fun findAllByStatusRawOrderByUpdatedAtDesc(statusRaw: String): List<PlaylistEntity>

    // V016 — подбор активного плейлиста по назначению (per-screen / глобальный).
    fun findFirstByStatusRawAndPharmacyIdOrderByUpdatedAtDesc(
        statusRaw: String,
        pharmacyId: String,
    ): PlaylistEntity?

    fun findFirstByStatusRawAndPharmacyIdIsNullOrderByUpdatedAtDesc(
        statusRaw: String,
    ): PlaylistEntity?
}

@Repository
interface SlideRepository : JpaRepository<SlideEntity, String> {
    fun findAllByOrderByCreatedAtDesc(): List<SlideEntity>
    fun findAllByPlaylistIdOrderByPositionAsc(playlistId: String): List<SlideEntity>
    fun countByPlaylistId(playlistId: String): Long
}
