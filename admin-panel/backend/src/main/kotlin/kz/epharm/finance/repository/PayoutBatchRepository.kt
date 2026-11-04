package kz.epharm.finance.repository

import kz.epharm.finance.entity.PayoutBatchEntity
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.Optional

@Repository
interface PayoutBatchRepository : JpaRepository<PayoutBatchEntity, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select batch from PayoutBatchEntity batch where batch.id = :id")
    fun findByIdForUpdate(@Param("id") id: String): Optional<PayoutBatchEntity>

    fun findAllByOrderByCreatedAtDesc(): List<PayoutBatchEntity>
    fun findAllByStatusRawOrderByCreatedAtDesc(statusRaw: String): List<PayoutBatchEntity>
    fun countByStatusRaw(statusRaw: String): Long
}
