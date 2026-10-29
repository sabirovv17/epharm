package kz.epharm.merch.inventory.dto

import jakarta.validation.constraints.NotEmpty
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class MerchSourceRef(val system: String, val origin: String, val id: UUID)
data class MerchBranchDto(val id: UUID, val organizationId: UUID, val organizationName: String,
    val code: String, val name: String, val address: String, val city: String, val division: String?,
    val active: Boolean, val epharmPharmacyId: String?, val source: MerchSourceRef)
data class MerchBranchPage(val items: List<MerchBranchDto>, val total: Long, val limit: Int, val offset: Int)
data class MerchContainerDto(val id: UUID, val code: String, val name: String, val type: String,
    val category: String?, val position: Int, val active: Boolean)
data class MerchShelfDto(val id: UUID, val containerId: UUID?, val code: String, val category: String,
    val title: String?, val type: String, val width: BigDecimal, val position: Int, val active: Boolean,
    val saleMode: String, val baseMonthlyPrice: Long?, val halfMonthlyPrice: Long?)
data class MerchInventoryDto(val branch: MerchBranchDto, val containers: List<MerchContainerDto>, val shelves: List<MerchShelfDto>)

enum class MerchInventoryStatus { available, blocked, held, booked }
enum class MerchAvailabilityAction { block, unblock }
enum class MerchBlockReason { repair, own_promo, reserve }
data class MerchPlacementItemDto(val id: UUID, val requestId: UUID, val portion: String,
    val advertiserName: String, val origin: String, val canRelease: Boolean)
data class MerchPlacementDto(val status: MerchInventoryStatus, val portion: String, val portionLabel: String,
    val heldUntil: Instant?, val hasAdminAssignment: Boolean, val items: List<MerchPlacementItemDto>)
data class MerchCalendarCellDto(val month: LocalDate, val inventoryId: UUID?, val status: MerchInventoryStatus,
    val reason: MerchBlockReason?, val note: String?, val canEdit: Boolean, val placement: MerchPlacementDto?)
data class MerchCalendarShelfDto(val id: UUID, val code: String, val category: String, val width: BigDecimal,
    val price: Long?, val active: Boolean, val months: List<MerchCalendarCellDto>)
data class MerchMonthSummary(val month: LocalDate, val availableCount: Int, val blockedCount: Int, val heldCount: Int, val bookedCount: Int)
data class MerchAvailabilityCalendar(val branch: MerchBranchDto, val startMonth: LocalDate, val monthCount: Int,
    val months: List<MerchMonthSummary>, val shelves: List<MerchCalendarShelfDto>, val canManage: Boolean, val canViewPrices: Boolean)

data class MerchAvailabilityRequest(@field:NotEmpty val shelfIds: List<UUID>, val fromMonth: LocalDate, val toMonth: LocalDate,
    val action: MerchAvailabilityAction, val reason: MerchBlockReason? = null, val note: String? = null)
data class MerchAvailabilityResult(val branchId: UUID, val action: MerchAvailabilityAction,
    val fromMonth: LocalDate, val toMonth: LocalDate, val targetCount: Int, val changedCount: Int,
    val protectedCount: Int, val unchangedCount: Int, val auditId: UUID)
