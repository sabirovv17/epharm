package kz.epharm.merch.placements.service

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.UUID
import kz.epharm.merch.inventory.dto.MerchFloorPlan
import kz.epharm.merch.inventory.service.MerchInventoryEditorService
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

internal data class PlacementBranchGeometry(val latitude:Double?,val longitude:Double?,val floorPlan:MerchFloorPlan?,val warning:String?)

/** Called only after the catalog's existing branch/customer check; drafts and private media never enter this projection. */
@Component
class PlacementPlanningProjection(private val jdbc:JdbcTemplate,private val mapper:ObjectMapper,private val editor:MerchInventoryEditorService) {
    internal fun facingFee(producer:UUID):String?=jdbc.query("""SELECT t.facing_month_price FROM merch_supplier_settings s
        JOIN merch_supplier_pricing_tiers t ON t.category=s.supplier_category AND t.active WHERE s.producer_id=?""",
        {rs,_->rs.getLong(1).toString()},producer).singleOrNull()
    internal fun branch(id:UUID):PlacementBranchGeometry {
        val row=jdbc.queryForMap("SELECT latitude,longitude,floor_plan::text FROM merch_branches WHERE id=?",id)
        val latitude=(row["latitude"] as? Number)?.toDouble()?.takeIf {it.isFinite()&&it in -90.0..90.0}
        val longitude=(row["longitude"] as? Number)?.toDouble()?.takeIf {it.isFinite()&&it in -180.0..180.0}
        val raw=row["floor_plan"] as? String
        val plan=raw?.takeIf {it.length<=64*1024}?.let {value->runCatching {
            editor.normalizePlan(mapper.readTree(value)).takeIf {valid(it)}
        }.getOrNull()}
        return PlacementBranchGeometry(if(longitude!=null)latitude else null,if(latitude!=null)longitude else null,plan,
            if(plan==null) if(raw==null) "Схема помещения ещё не опубликована" else "Сохранённая схема требует проверки; выбирайте места в списке" else null)
    }
    private fun valid(plan:MerchFloorPlan):Boolean {
        fun number(value:java.math.BigDecimal)=value.toDouble().isFinite()
        if(plan.version!=3||plan.room.width.toDouble() !in 1.0..1000.0||plan.room.depth.toDouble() !in 1.0..1000.0||
            plan.fixtures.size>50||plan.cabinetPositions.size>500)return false
        return (plan.fixtures.map {listOf(it.x,it.y,it.width,it.depth)}+plan.cabinetPositions.map {listOf(it.x,it.y,it.width,it.depth)})
            .all {r->r.all(::number)&&r[2].signum()>0&&r[3].signum()>0&&r[0].signum()>=0&&r[1].signum()>=0&&r[0]<=plan.room.width&&r[1]<=plan.room.depth}
    }
}
