package kz.epharm.merch.inventory.dto

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class MerchOrganizationEditor(val id: UUID,val revision: Long,val name: String,val legalName: String,val bin: String?,val active: Boolean)
data class SaveMerchOrganization(val expectedRevision: Long?=null,val name: String,val legalName: String,val bin: String?=null,val active: Boolean=true)
data class MerchBranchTraffic(val otsPerDay: Long?=null,val vehicleTrafficPerDay: Long?=null,val pedestrianTrafficPerDay: Long?=null,val source: String?=null,val measuredAt: LocalDate?=null)
data class SaveMerchBranch(val expectedRevision: Long?=null,val organizationId: UUID,val code: String,val name: String,val address: String,
    val city: String,val division: String?=null,val latitude: BigDecimal?=null,val longitude: BigDecimal?=null,val active: Boolean=true,val traffic: MerchBranchTraffic?=null)
data class SaveMerchContainer(val expectedRevision: Long,val code: String,val name: String,val type: String="cabinet",val category: String?=null,
    val position: Int=0,val active: Boolean=true)
data class SaveMerchShelf(val expectedRevision: Long,val containerId: UUID?=null,val code: String,val title: String?=null,
    val category: String,val type: String="shelf",val width: BigDecimal,val position: Int=0,val maxProducts: Int=20,val active: Boolean=true,val initialPrice: Long?=null)
data class MerchShelfEditor(val shelf: MerchShelfDto,val maxProducts: Int)
data class MerchEditorPrices(val shelfId: UUID,val month: LocalDate,val status: String,val price: Long?)
data class MerchEditorPhoto(val id: UUID,val shelfId: UUID?,val angle: String,val caption: String,val mimeType: String,val archived: Boolean)
data class MerchFloorRoom(val width: BigDecimal,val depth: BigDecimal)
data class MerchFloorFixture(val id: String,val type: String,val label: String,val x: BigDecimal,val y: BigDecimal,val width: BigDecimal,
    val depth: BigDecimal,val orientation: String="horizontal")
data class MerchCabinetPosition(val cabinetCode: String,val x: BigDecimal,val y: BigDecimal,val width: BigDecimal,val depth: BigDecimal,
    val orientation: String="horizontal",val zone: String="custom")
data class MerchFloorPlan(val version: Int=3,val room: MerchFloorRoom,val fixtures: List<MerchFloorFixture>,val cabinetPositions: List<MerchCabinetPosition>)
data class SaveMerchFloorPlan(val expectedRevision: Long,val plan: MerchFloorPlan)
data class MerchEditorRevision(val expectedRevision: Long)
data class ConfigureMerchShelves(val expectedRevision: Long,val counts: Map<String,Int>,val width: BigDecimal=BigDecimal("0.80"),val initialPrice: Long?=null)
data class UpdateMerchPrices(val expectedRevision: Long,val shelfIds: List<UUID>,val fromMonth: LocalDate,val toMonth: LocalDate,
    val price: Long,val saleMode: String?=null,val halfPrice: Long?=null,val applyFuture: Boolean=false)
data class MerchPriceResult(val revision: Long,val changedCount: Int,val protectedCount: Int)
data class MerchInventoryEditor(val branch: MerchBranchDto,val revision: Long,val latitude: BigDecimal?,val longitude: BigDecimal?,
    val containers: List<MerchContainerDto>,val shelves: List<MerchShelfEditor>,val monthlyPrices: List<MerchEditorPrices>,
    val photos: List<MerchEditorPhoto>,val floorPlan: MerchFloorPlan?,val floorPlanDraft: MerchFloorPlan?,val floorPlanWarning: String?,val publishedAt: Instant?,
    val canEditStructure: Boolean,val canEditPassport: Boolean,val canConfigurePrices: Boolean,val canManageOrganizations: Boolean,val traffic: MerchBranchTraffic)
