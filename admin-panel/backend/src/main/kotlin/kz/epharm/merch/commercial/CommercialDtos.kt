package kz.epharm.merch.commercial

import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.annotation.JsonAlias
import kz.epharm.merch.placements.dto.PlacementDto
import kz.epharm.merch.placements.dto.PlacementPortion
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class CommercialProduct(val id:String,@param:JsonAlias("name") val productName:String="",val sku:String?=null,
    val productCategory:String="",val productSubcategory:String="",val facingCount:Int=1,
    val activeMonths:List<LocalDate> = emptyList(),val shelfCategories:List<String> = emptyList())
data class CommercialShelf(val id:String="",val shelfId:UUID,val pharmacyId:UUID?=null,
    val portion:PlacementPortion=PlacementPortion.whole,val placementMonths:List<LocalDate>,
    val products:List<CommercialProduct> = emptyList(),val productName:String?=null,val sku:String?=null,val facingCount:Int=1,
    @get:JsonFormat(shape=JsonFormat.Shape.STRING) val price:Long?=null,val code:String?=null,val category:String?=null,val width:Double?=null,
    val pharmacyName:String?=null,val city:String?=null,val address:String?=null,
    val organizationId:UUID?=null,val organizationName:String?=null,val shelfKey:String?=null,
    val placementSelectionId:String?=null,val portionLabel:String?=null)
data class CommercialActivity(val id:UUID?=null,val productName:String,val sku:String?=null,
    val activityType:String,val activeMonths:List<LocalDate>,val pharmacyCount:Int=1,val facingCount:Int=1,
    val peopleCount:Int=0,@get:JsonFormat(shape=JsonFormat.Shape.STRING) val unitPrice:Long?,val supplierCategory:String?=null,val note:String?=null)
data class CommercialPlanData(val title:String,val startMonth:LocalDate,val endMonth:LocalDate,@get:JsonFormat(shape=JsonFormat.Shape.STRING) val budgetLimit:Long?,
    val status:String="draft",val notes:String?=null,val planningMode:String="shelves",
    val productBriefs:List<CommercialProduct> = emptyList(),val shelfSelection:List<CommercialShelf>?=null,
    val extraActivities:List<CommercialActivity> = emptyList(),val items:List<CommercialActivity> = emptyList())
data class SaveCommercialPlan(val expectedRevision:Long,val plan:CommercialPlanData)
data class SubmitCommercialPlan(val expectedRevision:Long)
data class CopyCommercialPlan(val expectedRevision:Long,val newId:UUID,val startMonth:LocalDate)
data class CommercialPlanDto(val id:UUID,val producerId:UUID,val revision:Long,val plan:CommercialPlanData,
    val requestIds:List<UUID>,val monthlyRequests:List<PlacementDto>,val copiedFrom:UUID?,@get:JsonFormat(shape=JsonFormat.Shape.STRING) val estimatedTotal:java.math.BigInteger?,
    val estimateState:String,val canEdit:Boolean,val canSubmit:Boolean,val canExport:Boolean,val updatedAt:Instant,
    val paymentStatus:String="not_connected",val canCopy:Boolean=false,val unresolvedRequestIds:List<UUID> = emptyList())
data class CommercialPlanPage(val items:List<CommercialPlanDto>,val total:Long,val limit:Int,val offset:Int)
data class CommercialPricing(val category:String,val label:String,@get:JsonFormat(shape=JsonFormat.Shape.STRING) val multiplier:java.math.BigDecimal,
    @get:JsonFormat(shape=JsonFormat.Shape.STRING) val facingMonthPrice:Long,@get:JsonFormat(shape=JsonFormat.Shape.STRING) val promotionMonthPrice:Long,
    @get:JsonFormat(shape=JsonFormat.Shape.STRING) val trainingEventPrice:Long,@get:JsonFormat(shape=JsonFormat.Shape.STRING) val motivationPersonPrice:Long)
data class CommercialSettings(val revision:Long,val supplierCategory:String?,@get:JsonFormat(shape=JsonFormat.Shape.STRING) val sixMonthBudget:Long?,val primaryContactRole:String?)
data class CommercialCategorySettings(val settings:CommercialSettings,val canConfigureCategory:Boolean)
data class SaveCommercialSettings(val expectedRevision:Long,val sixMonthBudget:Long?,val primaryContactRole:String?)
data class SetCommercialCategory(val expectedRevision:Long,val category:String)
data class CommercialMember(val id:UUID,val revision:Long,val fullName:String,val email:String,val position:String?,val accessRole:String,val active:Boolean)
data class SaveCommercialMember(val expectedRevision:Long,val fullName:String,val email:String,val position:String?,val accessRole:String,val active:Boolean)
data class CommercialWorkspace(val settings:CommercialSettings,val pricing:CommercialPricing?,val members:List<CommercialMember>,val plans:CommercialPlanPage)
data class CommercialCsvRequest(val csv:String,val producerId:UUID?=null)
data class CommercialCsvRow(val line:Int,val branchCode:String,val shelfCode:String,val month:LocalDate?,val shelfId:UUID?,val branchId:UUID?,val error:String?)
data class CommercialCsvPreview(val rows:List<CommercialCsvRow>,val valid:Boolean,val placements:List<CommercialShelf>)

/** These are placement taxonomy IDs from source 078, not the separate ACC recommendation classifier. */
object CommercialValidation {
    val taxonomy=mapOf("medicines" to setOf("prescription","otc","vitamins_supplements"),
        "medical_devices" to setOf("diagnostics","dressings","orthopedics"),"beauty_care" to setOf("dermocosmetics","personal_hygiene","oral_care"),
        "mother_child" to setOf("baby_food","mother_products","baby_hygiene"),"nutrition_wellness" to setOf("sports_nutrition","functional_food","diet_food"),
        "other" to setOf("seasonal","accessories"))
    val categories=setOf("standard","big_pharma","india","local","distributor")
    fun invalid(message:String):Nothing=throw AppException(ErrorCode.VALIDATION_FAILED,message,HttpStatus.BAD_REQUEST)
    fun text(value:String,max:Int,min:Int=0,multiline:Boolean=false):String {
        if(value.length>max || value.trim().length<min || value.any {(it.code<32 && !(multiline && it in "\n\r\t")) || it.code in 127..159}) invalid("Проверьте длину и формат текстовых полей")
        return value.trim()
    }
    fun months(start:LocalDate,end:LocalDate):List<LocalDate> {
        if(start.dayOfMonth!=1 || end.dayOfMonth!=1 || end<start || end>=start.plusMonths(12)) invalid("Период должен содержать от 1 до 12 полных месяцев")
        return generateSequence(start) {it.plusMonths(1)}.takeWhile {it<=end}.toList()
    }
    fun plan(value:CommercialPlanData):CommercialPlanData {
        val months=months(value.startMonth,value.endMonth).toSet()
        if(value.budgetLimit==null || value.budgetLimit<0 || value.status !in setOf("draft","ready","archived") || value.planningMode !in setOf("shelves","products")) invalid("Проверьте бюджет, статус и способ планирования")
        text(value.title,180,2);value.notes?.let {text(it,2000,multiline=true)}
        if(value.productBriefs.size>20 || value.planningMode=="products" && value.productBriefs.isEmpty()) invalid("Добавьте от 1 до 20 товаров")
        fun product(p:CommercialProduct,allowEmptyMonths:Boolean=false) {
            text(p.id,80,1);text(p.productName,180);p.sku?.let {text(it,80)}
            if(p.facingCount !in 1..100 || p.activeMonths.size !in (if(allowEmptyMonths) 0..12 else 1..12) || p.activeMonths.distinct().size!=p.activeMonths.size || !months.containsAll(p.activeMonths)) invalid("Проверьте фейсинги и месяцы товара")
            if(p.productCategory.isNotEmpty() || p.productSubcategory.isNotEmpty()) {
                if(p.productSubcategory !in taxonomy[p.productCategory].orEmpty()) invalid("Некорректная категория/подкатегория размещаемого товара")
            }
            if(p.shelfCategories.distinct().size!=p.shelfCategories.size || !setOf("AA","AB","BA","BB","CA","CB").containsAll(p.shelfCategories)) invalid("Некорректная категория эффективности полки")
        }
        if(value.productBriefs.map {it.id}.distinct().size!=value.productBriefs.size) invalid("Повторяющиеся ID товаров")
        value.productBriefs.forEach {product(it,allowEmptyMonths=true)}
        val rows=value.shelfSelection.orEmpty()
        if(rows.size>500 || value.items.size>500 || value.extraActivities.size>30) invalid("Превышен лимит мест или активностей")
        val occupied=mutableMapOf<Pair<UUID,LocalDate>,MutableSet<PlacementPortion>>()
        for(row in rows) {
            text(row.id,80);listOf(row.code,row.category,row.pharmacyName,row.city,row.address,row.organizationName,row.shelfKey,row.placementSelectionId,row.portionLabel).filterNotNull().forEach {text(it,600)}
            if(row.placementMonths.size !in 1..12 || row.placementMonths.distinct().size!=row.placementMonths.size || !months.containsAll(row.placementMonths)) invalid("Выберите месяцы каждого места в пределах плана")
            if(row.price!=null && row.price !in 0..1_000_000_000L || row.width!=null && (!row.width.isFinite() || row.width !in 0.0..20.0)) invalid("Некорректная предварительная оценка места")
            if(row.products.size !in 1..20 || row.products.map {it.id}.distinct().size!=row.products.size) invalid("Для места добавьте от 1 до 20 различных товаров")
            row.products.forEach {product(it)}
            for(month in row.placementMonths) {
                val portions=occupied.getOrPut(row.shelfId to month) {mutableSetOf()}
                if(!portions.add(row.portion) || portions.size>1 && PlacementPortion.whole in portions) invalid("В одном месяце пересекаются части места")
                val active=row.products.filter {month in it.activeMonths}
                if(active.isEmpty() || active.sumOf {it.facingCount}>100) invalid("Для каждого месяца места нужны товары, суммарно не более 100 фейсингов")
            }
        }
        val ids=value.items.mapNotNull {it.id}
        if(ids.distinct().size!=ids.size) invalid("Повторяющиеся ID активностей")
        for(a in value.items+value.extraActivities) {
            text(a.productName,180,2);a.sku?.let {text(it,80)};a.note?.let {text(it,1000,multiline=true)}
            if(a.activityType !in setOf("placement","promotion","training","motivation") || a.pharmacyCount !in 1..10000 || a.facingCount !in 1..100 || a.peopleCount !in 0..100000 ||
                a.unitPrice==null || a.unitPrice<0 || a.activeMonths.size !in 1..12 || a.activeMonths.distinct().size!=a.activeMonths.size || !months.containsAll(a.activeMonths)) invalid("Проверьте расчёт и месяцы бюджетной активности")
        }
        if(value.extraActivities.any {it.activityType=="placement"}) invalid("Дополнительная активность должна быть промо, обучением или мотивацией")
        return value.copy(title=value.title.trim(),notes=value.notes?.trim())
    }
}
