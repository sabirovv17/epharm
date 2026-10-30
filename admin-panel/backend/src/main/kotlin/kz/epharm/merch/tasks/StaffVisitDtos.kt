package kz.epharm.merch.tasks

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class StartStaffVisit(val branchId:UUID,val month:LocalDate,val employeeName:String)
data class SubmitStaffVisit(val expectedVersion:Long,val employeeName:String,val notes:String="")
data class StaffWorkspaceOrganization(val id:UUID,val name:String)
data class StaffWorkspaceBranch(val id:UUID,val code:String,val name:String,val address:String,val city:String,
    val requiredCount:Int,val visitId:UUID?,val status:String?,val photoCount:Int,val submittedAt:Instant?,
    val reason:String?,val canStart:Boolean)
data class StaffWorkspaceDto(val month:LocalDate,val organization:StaffWorkspaceOrganization,val deadlineAt:Instant?,
    val readOnly:Boolean,val branches:List<StaffWorkspaceBranch>,val limit:Int,val offset:Int,val hasMore:Boolean,
    val tasks:List<Any> = emptyList())
data class StaffVisitPlaceDto(val taskItemId:UUID,val placementItemId:UUID,val shelfId:UUID,val shelfCode:String,
    val portion:String,val category:String?,val width:Double?,val advertiserName:String?,val referenceAvailable:Boolean)
data class StaffVisitDto(val id:UUID,val branchId:UUID,val month:LocalDate,val employeeName:String,val notes:String,
    val submittedAt:Instant?,val reason:String?,val identityStatus:String="staff_access_unverified",val canEdit:Boolean,
    val deadlineAt:Instant?,val version:Long,val dispatch:MerchTaskDispatchDto,val places:List<StaffVisitPlaceDto>)

internal data class StaffVisitTarget(val id:UUID,val shelfId:UUID,val code:String,val portion:String)
