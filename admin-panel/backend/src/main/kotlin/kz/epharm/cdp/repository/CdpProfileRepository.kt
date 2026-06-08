package kz.epharm.cdp.repository

import kz.epharm.cdp.entity.CdpProfileEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface CdpProfileRepository : JpaRepository<CdpProfileEntity, String> {
    fun findByPhone(phone: String): CdpProfileEntity?
}
