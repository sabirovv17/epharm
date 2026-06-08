package kz.epharm.rules.repository

import kz.epharm.rules.entity.RuleEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface RuleRepository : JpaRepository<RuleEntity, String> {
    fun findAllByOrderByUpdatedAtDesc(): List<RuleEntity>
    fun findAllByTypeRawOrderByUpdatedAtDesc(typeRaw: String): List<RuleEntity>
    fun findAllByStatusRawOrderByUpdatedAtDesc(statusRaw: String): List<RuleEntity>
    fun countByStatusRaw(statusRaw: String): Long
}
