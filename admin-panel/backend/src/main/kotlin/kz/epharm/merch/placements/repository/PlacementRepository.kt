package kz.epharm.merch.placements.repository

import kz.epharm.merch.placements.dto.*
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

internal data class Plan(val id: UUID, val producerId: UUID?, val producerName: String?, val producerActive: Boolean,
    val advertiser: String?, val month: LocalDate, val status: PlacementStatus, val origin: String, val revision: Long,
    val total: Long, val heldUntil: Instant?, val reviewNote: String?, val note: String?, val createdAt: Instant, val updatedAt: Instant)
internal data class PlanItem(val id: UUID, val shelfId: UUID, val branchId: UUID, val code: String,
    val month: LocalDate, val portion: PlacementPortion, val status: PlacementStatus, val price: Long)
internal data class QuoteShelf(val id: UUID, val branch: UUID, val active: Boolean, val code: String,
    val mode: String, val base: Long, val half: Long?, val adjustment: BigDecimal, val rawStatus: String,
    val category:String?,val width:Double?,val containerCode:String?)

@Repository
class PlacementRepository(private val jdbc: JdbcTemplate, private val named: NamedParameterJdbcTemplate) {
    internal fun activeBranches(ids:Set<UUID>?):List<ProducerBranchOption> {
        if(ids?.isEmpty()==true) return emptyList()
        return named.query("""SELECT b.id,b.name,b.address,b.city FROM merch_branches b JOIN merch_organizations o ON o.id=b.organization_id
            WHERE b.active AND o.active${if(ids==null) "" else " AND b.id IN (:ids)"} ORDER BY b.city,b.name,b.id""",mapOf("ids" to ids)) {rs,_->
            ProducerBranchOption(rs.uuid("id"),rs.getString("name"),rs.getString("address"),rs.getString("city"))}
    }
    internal fun publicBranches(limit:Int,offset:Int):ProducerBranchOptions {
        val from="FROM merch_branches b JOIN merch_organizations o ON o.id=b.organization_id WHERE b.active AND o.active"
        val count=jdbc.queryForObject("SELECT count(*) $from",Long::class.java)!!
        val rows=jdbc.query("SELECT b.id,b.name,b.address,b.city $from ORDER BY b.city,b.name,b.id LIMIT ? OFFSET ?",{rs,_->
            ProducerBranchOption(rs.uuid("id"),rs.getString("name"),rs.getString("address"),rs.getString("city"))},limit,offset)
        return ProducerBranchOptions(rows,count,limit,offset)
    }
    internal fun activeShelfIds(branch:UUID):List<UUID> = jdbc.query("""SELECT s.id FROM merch_shelves s JOIN merch_branches b ON b.id=s.branch_id
        JOIN merch_organizations o ON o.id=b.organization_id WHERE s.branch_id=? AND s.active AND b.active AND o.active ORDER BY s.code,s.id""",{rs,_->rs.uuid("id")},branch)
    internal fun occupiedPortions(branch:UUID,month:LocalDate,now:Instant):Map<UUID,Set<String>> = jdbc.query("""SELECT i.shelf_id,coalesce(i.sale_portion,'whole') AS portion
        FROM merch_media_plan_items i JOIN merch_media_plans p ON p.id=i.media_plan_id JOIN merch_shelves s ON s.id=i.shelf_id
        WHERE s.branch_id=? AND i.placement_month=? AND (i.status='approved' OR(i.status='pending' AND p.status='pending' AND p.held_until>?))""",
        {rs,_->rs.uuid("shelf_id") to rs.getString("portion")},branch,month,Timestamp.from(now)).groupBy({it.first},{it.second}).mapValues {it.value.toSet()}
    fun producers(ids: Set<UUID>?): List<ProducerListItem> {
        if (ids?.isEmpty()==true) return emptyList()
        return named.query("SELECT id,name,active FROM merch_producers${if(ids==null) "" else " WHERE id IN (:ids)"} ORDER BY name,id",
            mapOf("ids" to ids)) { rs,_ -> ProducerListItem(rs.uuid("id"),rs.getString("name"),rs.getBoolean("active")) }
    }
    internal fun producer(id:UUID,lock:Boolean=false):ProducerListItem? = jdbc.query("SELECT id,name,active FROM merch_producers WHERE id=?"+if(lock) " FOR UPDATE" else "",
        {rs,_->ProducerListItem(rs.uuid("id"),rs.getString("name"),rs.getBoolean("active"))},id).singleOrNull()
    internal fun lockCustomer(id:UUID) {jdbc.queryForList("SELECT id FROM merch_producers WHERE id=? FOR UPDATE",id)}
    private val planSelect="""SELECT p.*,c.name AS producer_name,coalesce(c.active,false) AS producer_active
        FROM merch_media_plans p LEFT JOIN merch_producers c ON c.id=p.producer_id"""
    internal fun plan(id: UUID, lock: Boolean=false): Plan? = jdbc.query("$planSelect WHERE p.id=?${if(lock) " FOR UPDATE OF p" else ""}",
        { rs,_ -> plan(rs) },id).singleOrNull()
    internal fun items(id: UUID, lock: Boolean=false): List<PlanItem> = jdbc.query("""SELECT i.*,s.branch_id,s.code FROM merch_media_plan_items i
        JOIN merch_shelves s ON s.id=i.shelf_id WHERE i.media_plan_id=? ORDER BY i.id${if(lock) " FOR UPDATE OF i" else ""}""",
        { rs,_ -> PlanItem(rs.uuid("id"),rs.uuid("shelf_id"),rs.uuid("branch_id"),rs.getString("code"),rs.date("placement_month"),
            PlacementPortion.valueOf(rs.getString("sale_portion")?:"whole"),PlacementStatus.valueOf(rs.getString("status")),rs.getLong("monthly_price")) },id)
    internal fun listIds(branchScope: Set<UUID>?, producerScope: Set<UUID>?, producer: UUID?, branch: UUID?, status: PlacementStatus?,
        limit: Int, offset: Int): Pair<List<UUID>,Long> {
        if(branchScope?.isEmpty()==true || producerScope?.isEmpty()==true) return emptyList<UUID>() to 0L
        val params=mutableMapOf<String,Any?>("branches" to branchScope,"producers" to producerScope,"producer" to producer,"branch" to branch,"status" to status?.name,"limit" to limit,"offset" to offset)
        val where=buildString {
            append(" WHERE 1=1")
            if(producerScope!=null) append(" AND p.producer_id IN (:producers)")
            // Unresolved historical owners are visible only to the explicit ALL/ALL diagnostic view.
            if(branchScope!=null || producerScope!=null) append(" AND p.producer_id IS NOT NULL")
            if(producer!=null) append(" AND p.producer_id=:producer")
            if(status!=null) append(" AND p.status=:status")
            if(branch!=null) append(" AND EXISTS(SELECT 1 FROM merch_media_plan_items i JOIN merch_shelves s ON s.id=i.shelf_id WHERE i.media_plan_id=p.id AND s.branch_id=:branch)")
            if(branchScope!=null) append(" AND EXISTS(SELECT 1 FROM merch_media_plan_items i WHERE i.media_plan_id=p.id) AND NOT EXISTS(SELECT 1 FROM merch_media_plan_items i JOIN merch_shelves s ON s.id=i.shelf_id WHERE i.media_plan_id=p.id AND s.branch_id NOT IN (:branches))")
        }
        val count=named.queryForObject("SELECT count(*) FROM merch_media_plans p$where",params,Long::class.java)!!
        return named.query("SELECT p.id FROM merch_media_plans p$where ORDER BY p.created_at DESC,p.id LIMIT :limit OFFSET :offset",params) { rs,_ -> rs.uuid("id") } to count
    }

    internal fun quoteShelves(ids: Collection<UUID>, month: LocalDate): List<QuoteShelf> {
        if(ids.isEmpty()) return emptyList()
        return named.query("""SELECT s.*,b.price_adjustment_percent,(s.active AND b.active AND o.active) AS fully_active,
            coalesce(m.status,'available') AS inventory_status,coalesce(m.monthly_price,s.base_monthly_price) AS month_price,c.code AS container_code
            FROM merch_shelves s JOIN merch_branches b ON b.id=s.branch_id JOIN merch_organizations o ON o.id=b.organization_id
            LEFT JOIN merch_inventory_containers c ON c.id=s.container_id
            LEFT JOIN merch_monthly_inventory m ON m.shelf_id=s.id AND m.placement_month=:month WHERE s.id IN (:ids)""",
            mapOf("ids" to ids,"month" to month)) { rs,_ -> QuoteShelf(rs.uuid("id"),rs.uuid("branch_id"),rs.getBoolean("fully_active"),rs.getString("code"),
            rs.getString("sale_mode"),rs.getLong("month_price"),rs.getObject("half_monthly_price",Int::class.javaObjectType)?.toLong(),
            rs.getBigDecimal("price_adjustment_percent"),rs.getString("inventory_status"),rs.getString("category"),
            rs.getBigDecimal("width_m")?.toDouble(),rs.getString("container_code")) }
    }
    internal fun lock(branches: Set<UUID>, shelves: Set<UUID>) {
        if(branches.isNotEmpty()) named.queryForList("SELECT id FROM merch_branches WHERE id IN (:ids) ORDER BY id FOR UPDATE",mapOf("ids" to branches))
        if(shelves.isNotEmpty()) named.queryForList("SELECT id FROM merch_shelves WHERE id IN (:ids) ORDER BY id FOR UPDATE",mapOf("ids" to shelves))
    }
    internal fun hasOverlap(shelf: UUID, month: LocalDate, portion: PlacementPortion, exclude: UUID, now: Instant): Boolean = jdbc.queryForObject("""
        SELECT EXISTS(SELECT 1 FROM merch_media_plan_items i JOIN merch_media_plans p ON p.id=i.media_plan_id
        WHERE i.shelf_id=? AND i.placement_month=? AND p.id<>? AND
        (?='whole' OR coalesce(i.sale_portion,'whole')='whole' OR i.sale_portion=?) AND
        (i.status='approved' OR (i.status='pending' AND p.status='pending' AND p.held_until>?)))""",Boolean::class.java,
        shelf,month,exclude,portion.name,portion.name,Timestamp.from(now))!!

    internal fun ensureMonthCells(shelves: Collection<UUID>, month: LocalDate, now: Instant) {
        for(shelf in shelves) {
            val id=UUID.randomUUID()
            jdbc.update("""INSERT INTO merch_monthly_inventory(id,source_system,source_origin,source_id,source_payload,
                shelf_id,placement_month,status,monthly_price,created_at,updated_at)
                SELECT ?,'EPHARM','https://epharm.inkar.kz',?,'{}'::jsonb,id,?,'available',base_monthly_price,?,?
                FROM merch_shelves WHERE id=? ON CONFLICT(shelf_id,placement_month) DO NOTHING""",id,id,month,Timestamp.from(now),Timestamp.from(now),shelf)
        }
    }

    internal fun create(id: UUID, producer: ProducerListItem, month: LocalDate, origin: String, actor: PlacementActor, note: String?, now: Instant) {
        jdbc.update("""INSERT INTO merch_media_plans(id,source_system,source_origin,source_id,source_payload,producer_id,advertiser_name,
            placement_month,status,origin,total_amount,submitted_at,created_at,updated_at,created_by_admin,created_by_producer,assignment_note)
            VALUES(?,'EPHARM','https://epharm.inkar.kz',?,'{}'::jsonb,?, ?,?,'draft',?,0,?,?,?,?,?,?)""",id,id,producer.id,producer.name,month,origin,
            Timestamp.from(now),Timestamp.from(now),Timestamp.from(now),(actor as? PlacementActor.Internal)?.accountId,(actor as? PlacementActor.Producer)?.customerId,note)
    }
    internal fun saveItems(plan: Plan, selections: List<PlacementSelection>, quotes: Map<PlacementSelection,Long>, note: String?, now: Instant) {
        val old=items(plan.id).associateBy { PlacementSelection(it.shelfId,it.portion) }
        for(item in old.values.filter { existing -> PlacementSelection(existing.shelfId,existing.portion) !in selections })
            jdbc.update("UPDATE merch_media_plan_items SET status='cancelled' WHERE id=?",item.id)
        for(selection in selections) {
            val previous=old[selection]
            if(previous==null) {
                val id=UUID.randomUUID()
                jdbc.update("""INSERT INTO merch_media_plan_items(id,source_system,source_origin,source_id,source_payload,
                    media_plan_id,shelf_id,placement_month,monthly_price,status,sale_portion,created_at)
                    VALUES(?,'EPHARM','https://epharm.inkar.kz',?,'{}'::jsonb,?,?,?,?,'draft',?,?)""",id,id,plan.id,selection.shelfId,plan.month,quotes.getValue(selection),selection.portion.name,Timestamp.from(now))
            } else jdbc.update("UPDATE merch_media_plan_items SET status='draft',sale_portion=?,monthly_price=? WHERE id=?",selection.portion.name,quotes.getValue(selection),previous.id)
        }
        jdbc.update("UPDATE merch_media_plans SET total_amount=?,assignment_note=?,updated_at=? WHERE id=?",sum(quotes.values),note,Timestamp.from(now),plan.id)
    }
    internal fun transition(id: UUID, status: PlacementStatus, deadline: Instant?, actor: PlacementActor, reason: String?, now: Instant) {
        jdbc.update("""UPDATE merch_media_plans SET status=?,held_until=coalesce(?,held_until),submitted_at=CASE WHEN ?='pending' THEN ? ELSE submitted_at END,
            reviewed_by_admin=?,reviewed_at=?,review_note=?,updated_at=? WHERE id=?""",status.name,deadline?.let(Timestamp::from),status.name,Timestamp.from(now),
            if(status==PlacementStatus.pending) null else (actor as? PlacementActor.Internal)?.accountId,
            if(status==PlacementStatus.pending) null else Timestamp.from(now),reason,Timestamp.from(now),id)
    }
    internal fun snapshotCompany(id:UUID,name:String) {jdbc.update("UPDATE merch_media_plans SET advertiser_name=? WHERE id=?",name,id)}
    internal fun setItemStatus(plan: UUID, from: Set<PlacementStatus>, target: PlacementStatus) {
        named.update("UPDATE merch_media_plan_items SET status=:status WHERE media_plan_id=:id AND status IN (:from)",
            mapOf("status" to target.name,"id" to plan,"from" to from.map { it.name }))
    }
    internal fun setSelectedItemStatus(plan:UUID,ids:Set<UUID>,target:PlacementStatus) {
        named.update("UPDATE merch_media_plan_items SET status=:status WHERE media_plan_id=:plan AND id IN (:ids) AND status='pending'",
            mapOf("status" to target.name,"plan" to plan,"ids" to ids))
    }
    internal fun reviewSelectedPlan(id:UUID,status:PlacementStatus,actor:PlacementActor.Internal,reason:String?,now:Instant) {
        jdbc.update("UPDATE merch_media_plans SET status=?,reviewed_by_admin=?,reviewed_at=?,review_note=?,updated_at=? WHERE id=?",
            status.name,actor.accountId,Timestamp.from(now),reason,Timestamp.from(now),id)
    }
    internal fun bump(id: UUID): Long {
        jdbc.update("UPDATE merch_media_plans SET revision=revision+1 WHERE id=?",id)
        return jdbc.queryForObject("SELECT revision FROM merch_media_plans WHERE id=?",Long::class.java,id)!!
    }
    internal fun audit(plan: UUID, revision: Long, actor: PlacementActor, action: String, details: String, now: Instant) {
        val type=when(actor){is PlacementActor.Internal->"ADMIN";is PlacementActor.Producer->"PRODUCER";PlacementActor.System->"SYSTEM"}
        jdbc.update("""INSERT INTO merch_placement_audit(id,plan_id,revision,actor_type,admin_account_id,producer_id,action,occurred_at,details)
            VALUES(?,?,?,?,?,?,?,?,?::jsonb)""",UUID.randomUUID(),plan,revision,type,(actor as? PlacementActor.Internal)?.accountId,
            (actor as? PlacementActor.Producer)?.customerId,action,Timestamp.from(now),details)
    }
    internal fun clearLegacyHold(items: List<PlanItem>, now: Instant) {
        for(item in items) jdbc.update("""UPDATE merch_monthly_inventory m SET status='available',held_until=NULL,updated_at=?
            WHERE m.shelf_id=? AND m.placement_month=? AND m.status='held' AND NOT EXISTS(
              SELECT 1 FROM merch_media_plan_items i JOIN merch_media_plans p ON p.id=i.media_plan_id
              WHERE i.shelf_id=m.shelf_id AND i.placement_month=m.placement_month AND
              (i.status='approved' OR (i.status='pending' AND p.status='pending' AND p.held_until>?)))""",
            Timestamp.from(now),item.shelfId,item.month,Timestamp.from(now))
    }
    internal fun due(now: Instant, limit: Int): List<UUID> = jdbc.query("SELECT id FROM merch_media_plans WHERE status='pending' AND held_until<=? ORDER BY held_until,id LIMIT ?",
        { rs,_ -> rs.uuid("id") },Timestamp.from(now),limit)
    private fun plan(rs: ResultSet)=Plan(rs.uuid("id"),rs.getObject("producer_id",UUID::class.java),rs.getString("producer_name"),rs.getBoolean("producer_active"),
        rs.getString("advertiser_name"),rs.date("placement_month"),PlacementStatus.valueOf(rs.getString("status")),rs.getString("origin"),rs.getLong("revision"),
        rs.getLong("total_amount"),rs.getTimestamp("held_until")?.toInstant(),rs.getString("review_note"),rs.getString("assignment_note"),rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("updated_at").toInstant())
    private fun ResultSet.uuid(name:String)=getObject(name,UUID::class.java)
    private fun ResultSet.date(name:String)=getObject(name,LocalDate::class.java)
    companion object { fun sum(values: Collection<Long>): Long = values.fold(0L,Math::addExact).also { require(it in 0..Int.MAX_VALUE.toLong()) { "Placement amount exceeds source integer-KZT contract" } } }
}
