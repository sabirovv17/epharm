package kz.epharm.merch.inventory.repository

import kz.epharm.merch.inventory.dto.*
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

internal data class InventoryShelf(val dto: MerchShelfDto, val basePrice: Long)
internal data class InventoryCell(val id: UUID, val shelfId: UUID, val month: LocalDate, val status: MerchInventoryStatus,
    val reason: MerchBlockReason?, val note: String?)
internal data class PlacementRow(val shelfId: UUID, val month: LocalDate, val status: String,
    val heldUntil: Instant?, val item: MerchPlacementItemDto)

@Repository
class MerchInventoryRepository(private val jdbc: JdbcTemplate, private val named: NamedParameterJdbcTemplate) {
    private val branchSelect = """SELECT b.*,o.name AS organization_name FROM merch_branches b
        JOIN merch_organizations o ON o.id=b.organization_id"""

    fun branches(ids: Set<UUID>?, limit: Int, offset: Int): MerchBranchPage {
        if (ids?.isEmpty() == true) return MerchBranchPage(emptyList(), 0, limit, offset)
        val where = if (ids == null) "" else " WHERE b.id IN (:ids)"
        val params = mapOf("ids" to ids, "limit" to limit, "offset" to offset)
        val total = named.queryForObject("SELECT count(*) FROM merch_branches b$where", params, Long::class.java)!!
        val rows = named.query("$branchSelect$where ORDER BY b.city,b.name,b.id LIMIT :limit OFFSET :offset", params) { rs, _ -> branch(rs) }
        return MerchBranchPage(rows, total, limit, offset)
    }

    fun branch(id: UUID, lock: Boolean = false): MerchBranchDto? = jdbc.query(
        "$branchSelect WHERE b.id=?${if (lock) " FOR UPDATE OF b" else ""}", { rs, _ -> branch(rs) }, id).singleOrNull()
    fun adjustment(id: UUID): BigDecimal = jdbc.queryForObject("SELECT price_adjustment_percent FROM merch_branches WHERE id=?", BigDecimal::class.java, id)!!
    fun containers(branch: UUID): List<MerchContainerDto> = jdbc.query("""SELECT * FROM merch_inventory_containers
        WHERE branch_id=? ORDER BY position_index,code,id""", { rs, _ -> MerchContainerDto(rs.uuid("id"), rs.getString("code"),
        rs.getString("name"), rs.getString("container_type"), rs.getString("category"), rs.getInt("position_index"), rs.getBoolean("active")) }, branch)

    /** Writers lock branch, then shelf UUID order; absent month cells are serialized by their shelf. */
    internal fun shelves(branch: UUID, selected: List<UUID>? = null, lock: Boolean = false): List<InventoryShelf> {
        val selection = if (selected == null) "" else " AND id IN (:ids)"
        val ordering = if (lock) "id FOR UPDATE" else "category,code,id"
        return named.query("SELECT * FROM merch_shelves WHERE branch_id=:branch$selection ORDER BY $ordering",
            mapOf("branch" to branch, "ids" to selected)) { rs, _ ->
            InventoryShelf(MerchShelfDto(rs.uuid("id"), rs.getObject("container_id", UUID::class.java), rs.getString("code"),
                rs.getString("category"), rs.getString("title"), rs.getString("place_type"), rs.getBigDecimal("width_m"),
                rs.getInt("position_index"), rs.getBoolean("active"), rs.getString("sale_mode"), rs.getLong("base_monthly_price"),
                rs.getObject("half_monthly_price", Int::class.javaObjectType)?.toLong()), rs.getLong("base_monthly_price"))
        }
    }

    internal fun cells(branch: UUID, from: LocalDate, to: LocalDate): List<InventoryCell> = jdbc.query("""
        SELECT i.* FROM merch_monthly_inventory i JOIN merch_shelves s ON s.id=i.shelf_id
        WHERE s.branch_id=? AND i.placement_month BETWEEN ? AND ?""", { rs, _ ->
        InventoryCell(rs.uuid("id"), rs.uuid("shelf_id"), rs.getObject("placement_month", LocalDate::class.java),
            MerchInventoryStatus.valueOf(rs.getString("status")), rs.getString("block_reason")?.let(MerchBlockReason::valueOf), rs.getString("block_note"))
    }, branch, from, to)

    internal fun placements(branch: UUID, from: LocalDate, to: LocalDate, now: Instant): List<PlacementRow> = jdbc.query("""
        SELECT i.*,p.held_until,p.advertiser_name,p.origin FROM merch_media_plan_items i
        JOIN merch_media_plans p ON p.id=i.media_plan_id JOIN merch_shelves s ON s.id=i.shelf_id
        WHERE s.branch_id=? AND i.placement_month BETWEEN ? AND ? AND
        (i.status='approved' OR (i.status='pending' AND p.status='pending' AND p.held_until>?))
        ORDER BY COALESCE(i.sale_portion,'whole'),p.advertiser_name,i.id""", { rs, _ ->
        PlacementRow(rs.uuid("shelf_id"), rs.getObject("placement_month", LocalDate::class.java), rs.getString("status"),
            rs.getTimestamp("held_until")?.toInstant(), MerchPlacementItemDto(rs.uuid("id"), rs.uuid("media_plan_id"),
                rs.getString("sale_portion") ?: "whole", rs.getString("advertiser_name"), rs.getString("origin"),
                rs.getString("status") == "approved" && rs.getString("origin") == "admin_assignment"))
    }, branch, from, to, java.sql.Timestamp.from(now))

    internal fun block(shelf: InventoryShelf, month: LocalDate, reason: MerchBlockReason, note: String?, actor: UUID, now: Instant): Int {
        val id = UUID.randomUUID()
        return jdbc.update("""INSERT INTO merch_monthly_inventory(id,source_system,source_origin,source_id,source_payload,
            shelf_id,placement_month,status,monthly_price,block_reason,block_note,blocked_by_admin,blocked_at,created_at,updated_at)
            VALUES(?,'EPHARM','https://epharm.inkar.kz',?,'{}'::jsonb,?,?,'blocked',?,?,?,?,?,?,?)
            ON CONFLICT(shelf_id,placement_month) DO UPDATE SET status='blocked',block_reason=excluded.block_reason,
            block_note=excluded.block_note,blocked_by_admin=excluded.blocked_by_admin,blocked_by_source=NULL,
            blocked_at=excluded.blocked_at,held_until=NULL,updated_at=excluded.updated_at
            WHERE merch_monthly_inventory.status IN ('available','blocked')""", id, id, shelf.dto.id, month,
            shelf.basePrice, reason.name, note, actor, java.sql.Timestamp.from(now), java.sql.Timestamp.from(now), java.sql.Timestamp.from(now))
    }

    internal fun unblock(shelf: UUID, month: LocalDate, now: Instant): Int = jdbc.update("""
        UPDATE merch_monthly_inventory SET status='available',block_reason=NULL,block_note=NULL,
        blocked_by_admin=NULL,blocked_by_source=NULL,blocked_at=NULL,held_until=NULL,updated_at=?
        WHERE shelf_id=? AND placement_month=? AND status='blocked'""", java.sql.Timestamp.from(now), shelf, month)

    internal fun audit(id: UUID, branch: UUID, actor: UUID, action: MerchAvailabilityAction, now: Instant, details: String) {
        jdbc.update("INSERT INTO merch_inventory_audit(id,branch_id,actor_account_id,action,occurred_at,details) VALUES(?,?,?,?,?,?::jsonb)",
            id, branch, actor, action.name, java.sql.Timestamp.from(now), details)
    }

    private fun branch(rs: ResultSet) = MerchBranchDto(rs.uuid("id"), rs.uuid("organization_id"), rs.getString("organization_name"),
        rs.getString("code"), rs.getString("name"), rs.getString("address"), rs.getString("city"), rs.getString("division"),
        rs.getBoolean("active"), rs.getString("epharm_pharmacy_id"),
        MerchSourceRef(rs.getString("source_system"), rs.getString("source_origin"), rs.uuid("source_id")))
    private fun ResultSet.uuid(column: String) = getObject(column, UUID::class.java)
}
