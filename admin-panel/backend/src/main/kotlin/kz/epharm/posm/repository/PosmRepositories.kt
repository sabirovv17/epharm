package kz.epharm.posm.repository

import kz.epharm.posm.entity.PosSaleEntity
import kz.epharm.posm.entity.ProductPosCodeEntity
import kz.epharm.posm.entity.RecommendationEventEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ProductPosCodeRepository : JpaRepository<ProductPosCodeEntity, Long>

@Repository
interface PosSaleRepository : JpaRepository<PosSaleEntity, String>

@Repository
interface RecommendationEventRepository : JpaRepository<RecommendationEventEntity, String> {
    /** Все события чека — для фильтра «не показывать отклонённое в этом чеке». */
    fun findAllBySessionId(sessionId: String): List<RecommendationEventEntity>

    /** Последнее событие правила в этом чеке — для идемпотентного показа. */
    fun findFirstBySessionIdAndRuleIdOrderByShownAtDesc(
        sessionId: String,
        ruleId: String,
    ): RecommendationEventEntity?
}
