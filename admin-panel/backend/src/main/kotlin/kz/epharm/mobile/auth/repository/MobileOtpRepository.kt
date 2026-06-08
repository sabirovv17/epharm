package kz.epharm.mobile.auth.repository

import kz.epharm.mobile.auth.entity.MobileOtpEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface MobileOtpRepository : JpaRepository<MobileOtpEntity, String>
