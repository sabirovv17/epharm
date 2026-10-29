package kz.epharm.merch.placements.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Constructed only by an authenticated controller/session adapter, never deserialized from a body. */
sealed interface PlacementActor {
    data class Internal(val accountId: UUID) : PlacementActor
    data class Producer(val customerId: UUID,val sessionId: UUID) : PlacementActor
    data object System : PlacementActor
}
enum class PlacementPortion { whole, left, right }
enum class PlacementStatus { draft, pending, approved, rejected, cancelled }
data class PlacementSelection(val shelfId: UUID, val portion: PlacementPortion = PlacementPortion.whole)
/** Server-normalized product metadata; producer identity and tariffs never come from its JSON. */
data class PlacementProductSnapshot(val productName:String?,val sku:String?,val facingCount:Int,
    val supplierCategory:String,val productsJson:String)
data class CreatePlacementDraft(val producerId: UUID, val placementMonth: LocalDate,
    @field:NotEmpty @field:Size(max=500) @field:Valid val items: List<PlacementSelection>, @field:Size(max=500) val note: String? = null)
data class SavePlacementDraft(@field:Min(0) val expectedRevision: Long,
    @field:NotEmpty @field:Size(max=500) @field:Valid val items: List<PlacementSelection>, @field:Size(max=500) val note: String? = null)
data class PlacementTransition(@field:Min(0) val expectedRevision: Long, @field:Size(max=1000) val reason: String? = null)
data class PlacementItemReview(@field:Min(0) val expectedRevision:Long,
    @field:NotEmpty @field:Size(max=500) val itemIds:List<UUID>,@field:Size(max=1000) val reason:String?=null)
class BindPlacementProducer(@field:Min(0) val expectedRevision:Long,val producerId:UUID,
    @field:NotBlank @field:Size(min=3,max=1000) val reason:String,
    @field:NotBlank @field:Size(max=128) val currentPassword:String) {
    override fun toString()="BindPlacementProducer(expectedRevision=$expectedRevision, producerId=$producerId, reason=[REDACTED], currentPassword=[REDACTED])"
}
data class ProducerListItem(val id: UUID, val name: String?, val active: Boolean,val profileComplete:Boolean=!name.isNullOrBlank())
data class PlacementItemDto(val id: UUID, val shelfId: UUID, val branchId: UUID, val shelfCode: String,
    val placementMonth: LocalDate, val portion: PlacementPortion, val status: PlacementStatus, val monthlyPrice: Long?)
data class PlacementDto(val id: UUID, val producerId: UUID?, val producerName: String?, val advertiserName: String?,
    val placementMonth: LocalDate, val status: PlacementStatus, val origin: String, val revision: Long,
    val totalAmount: Long?, val heldUntil: Instant?, val reservationState: String, val reservationBusinessDays: Int,
    val reservationTimezone: String, val reviewNote: String?, val note: String?, val items: List<PlacementItemDto>,
    val canEdit: Boolean, val canSubmit: Boolean, val canApprove: Boolean, val canReject: Boolean, val canCancel: Boolean,
    val createdAt: Instant, val updatedAt: Instant, val producerBindingStatus: String,val canBindProducer:Boolean=false,
    val approvedAmount:Long?=null,val pendingAmount:Long?=null)
data class PlacementPage(val items: List<PlacementDto>, val total: Long, val limit: Int, val offset: Int)
data class ProducerBranchOption(val id:UUID,val name:String,val address:String,val city:String)
data class ProducerBranchOptions(val items:List<ProducerBranchOption>,val total:Long,val limit:Int,val offset:Int)
data class ProducerPlaceOffer(val price:Long?,val available:Boolean)
data class ProducerAvailablePlace(val shelfId:UUID,val code:String,val saleMode:String,
    val whole:ProducerPlaceOffer,val left:ProducerPlaceOffer?,val right:ProducerPlaceOffer?)
data class ProducerAvailability(val branchId:UUID,val placementMonth:LocalDate,val items:List<ProducerAvailablePlace>,
    val paymentStatus:String="not_connected")
data class AdminPlacementCatalogBranch(val id:UUID,val name:String,val address:String,val city:String,val canCreate:Boolean,
    val canAssign:Boolean,val canViewPrices:Boolean,val items:List<ProducerAvailablePlace>)
data class AdminPlacementCatalog(val producerId:UUID,val producerName:String?,val month:LocalDate,
    val branches:List<AdminPlacementCatalogBranch>,val total:Long,val limit:Int,val offset:Int,val canCreate:Boolean,val canAssign:Boolean)
