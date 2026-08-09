package kz.epharm.screens.repository

import kz.epharm.screens.entity.PlaylistEntity
import kz.epharm.screens.entity.PlaylistPharmacyAssignmentEntity
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

    fun findFirstByStatusRawAndPharmacyIdIsNullAndParentPlaylistIdIsNullOrderByUpdatedAtDesc(
        statusRaw: String,
    ): PlaylistEntity?
}

@Repository
interface PlaylistPharmacyAssignmentRepository : JpaRepository<PlaylistPharmacyAssignmentEntity, String> {
    fun findByPharmacyId(pharmacyId: String): PlaylistPharmacyAssignmentEntity?
    fun findAllByPlaylistIdOrderByPharmacyIdAsc(playlistId: String): List<PlaylistPharmacyAssignmentEntity>
    fun deleteAllByPlaylistId(playlistId: String)
}

@Repository
interface SlideRepository : JpaRepository<SlideEntity, String> {
    fun findAllByOrderByCreatedAtDesc(): List<SlideEntity>
    fun findAllByPlaylistIdOrderByPositionAsc(playlistId: String): List<SlideEntity>
    fun countByPlaylistId(playlistId: String): Long
}
