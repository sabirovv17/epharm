package kz.epharm.merch.placements.service

import com.fasterxml.jackson.databind.ObjectMapper
import kz.epharm.auth.access.AdministrativeScope
import kz.epharm.auth.policy.PermissionKey
import kz.epharm.merch.placements.dto.*
import kz.epharm.merch.placements.repository.*
import kz.epharm.merch.producer.auth.ProducerAuthService
import kz.epharm.merch.producer.auth.ProducerPrincipal
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

object PlacementPermissions {
    fun key(action: String)=requireNotNull(PermissionKey.parse("merchandising","placements",action))
    val producers=requireNotNull(PermissionKey.parse("merchandising","producers","view"))
}

@Service
class PlacementService(private val repository: PlacementRepository,private val access: AdministrativeScope,
    private val json: ObjectMapper,private val transactions: PlatformTransactionManager,private val producerSessions:ProducerAuthService) {
    @Transactional(readOnly=true)
    fun producerBranches(actor:PlacementActor.Producer,limit:Int,offset:Int):ProducerBranchOptions {
        activeProducer(actor.customerId)
        if(limit !in 1..200 || offset !in 0..1_000_000) invalid("Некорректная страница аптек")
        return repository.publicBranches(limit,offset)
    }
    @Transactional(readOnly=true)
    fun producerAvailability(actor:PlacementActor.Producer,branch:UUID,month:LocalDate):ProducerAvailability {
        activeProducer(actor.customerId);validateMonth(month)
        return ProducerAvailability(branch,month,availablePlaces(branch,month))
    }
    @Transactional(readOnly=true)
    fun adminCatalog(actor:PlacementActor.Internal,producerId:UUID,month:LocalDate,branchId:UUID?,limit:Int,offset:Int):AdminPlacementCatalog {
        access.requireExplicitPermission(PlacementPermissions.key("view"));validateMonth(month)
        if(limit !in 1..200 || offset !in 0..1_000_000) invalid("Некорректная страница мест")
        if(access.currentProducerIds()?.let {producerId !in it}==true) forbidden()
        val producer=activeProducer(producerId)
        if(branchId!=null) require(actor,"view",setOf(branchId),producerId)
        val allowed=repository.activeBranches(access.currentMerchBranchIds()).filter {branch->
            (branchId==null || branch.id==branchId) && can(actor,"view",setOf(branch.id),producerId)}
        val page=allowed.drop(offset).take(limit).map {branch->
            val scope=setOf(branch.id);val money=can(actor,"financial_data",scope,producerId)
            AdminPlacementCatalogBranch(branch.id,branch.name,branch.address,branch.city,can(actor,"edit",scope,producerId),
                producer.profileComplete && money && can(actor,"assign",scope,producerId),money,availablePlaces(branch.id,month).map {place->
                    if(money) place else place.copy(whole=place.whole.copy(price=null),left=place.left?.copy(price=null),right=place.right?.copy(price=null))})
        }
        return AdminPlacementCatalog(producerId,producer.name,month,page,allowed.size.toLong(),limit,offset,
            allowed.any {can(actor,"edit",setOf(it.id),producerId)},producer.profileComplete && allowed.any {can(actor,"assign",setOf(it.id),producerId) && can(actor,"financial_data",setOf(it.id),producerId)})
    }
    private fun availablePlaces(branch:UUID,month:LocalDate):List<ProducerAvailablePlace> {
        val ids=repository.activeShelfIds(branch)
        val occupied=repository.occupiedPortions(branch,month,Instant.now())
        val rows=repository.quoteShelves(ids,month).map {shelf->
            val portions=occupied[shelf.id].orEmpty()
            fun offer(portion:PlacementPortion)=ProducerPlaceOffer(price(shelf,portion),shelf.rawStatus=="available" && "whole" !in portions &&
                (if(portion==PlacementPortion.whole) portions.isEmpty() else portion.name !in portions))
            ProducerAvailablePlace(shelf.id,shelf.code,shelf.mode,offer(PlacementPortion.whole),
                if(shelf.mode=="whole_or_halves") offer(PlacementPortion.left) else null,
                if(shelf.mode=="whole_or_halves") offer(PlacementPortion.right) else null)
        }
        return rows
    }
    @Transactional(readOnly=true)
    fun producers(): List<ProducerListItem> {
        access.requireExplicitPermission(PlacementPermissions.producers)
        return repository.producers(access.currentProducerIds())
    }
    @Transactional(readOnly=true)
    fun producerOptions():List<ProducerListItem> {
        access.requireExplicitPermission(PlacementPermissions.key("view"))
        return repository.producers(access.currentProducerIds())
    }
    @Transactional(readOnly=true)
    fun list(actor: PlacementActor,producerId: UUID?,branchId: UUID?,status: PlacementStatus?,limit: Int,offset: Int): PlacementPage {
        if(limit !in 1..200 || offset !in 0..1_000_000) invalid("Некорректная страница медиапланов")
        val branches: Set<UUID>?
        val producers: Set<UUID>?
        when(actor) {
            is PlacementActor.Internal -> { access.requireExplicitPermission(PlacementPermissions.key("view")); branches=access.currentMerchBranchIds();producers=access.currentProducerIds() }
            is PlacementActor.Producer -> { activeProducer(actor.customerId); branches=null;producers=setOf(actor.customerId); if(producerId!=null && producerId!=actor.customerId) forbidden() }
            PlacementActor.System -> forbidden()
        }
        val (ids,total)=repository.listIds(branches,producers,producerId,branchId,status,limit,offset)
        return PlacementPage(ids.map { view(it,actor) },total,limit,offset)
    }
    @Transactional(readOnly=true)
    fun view(id: UUID,actor: PlacementActor): PlacementDto {
        val plan=load(id);val items=repository.items(id)
        require(actor,"view",items.branches(),plan.producerId)
        return dto(plan,items,actor)
    }
    @Transactional
    fun create(request: CreatePlacementDraft,actor: PlacementActor): PlacementDto = create(request,actor,false)
    @Transactional
    fun assign(request: CreatePlacementDraft,actor: PlacementActor): PlacementDto {
        if(actor !is PlacementActor.Internal) forbidden()
        return create(request,actor,true)
    }
    private fun create(request: CreatePlacementDraft,actor: PlacementActor,assignment: Boolean): PlacementDto {
        lockActor(actor)
        validateMonth(request.placementMonth);validateSelections(request.items)
        if(actor is PlacementActor.Producer && actor.customerId!=request.producerId) forbidden()
        val shelves=repository.quoteShelves(request.items.map { it.shelfId },request.placementMonth)
        if(shelves.size!=request.items.size) invalid("Выбранное место не найдено")
        val branches=shelves.map { it.branch }.toSet()
        require(actor,if(assignment) "assign" else "edit",branches,request.producerId)
        if(assignment) require(actor,"financial_data",branches,request.producerId)
        val producer=activeProducer(request.producerId,lock=true)
        if(assignment) company(producer)
        repository.lock(branches,request.items.map { it.shelfId }.toSet())
        if(assignment) repository.ensureMonthCells(request.items.map { it.shelfId },request.placementMonth,Instant.now())
        val quotes=quotes(request.items,request.placementMonth)
        val now=Instant.now();val id=UUID.randomUUID()
        repository.create(id,producer,request.placementMonth,if(assignment) "admin_assignment" else "request",actor,request.note?.trim(),now)
        repository.saveItems(load(id),request.items,quotes,request.note?.trim(),now)
        if(assignment) {
            available(id,request.items,request.placementMonth,now)
            repository.setItemStatus(id,setOf(PlacementStatus.draft),PlacementStatus.approved)
            repository.transition(id,PlacementStatus.approved,null,actor,null,now)
        }
        audit(id,actor,if(assignment) "assigned" else "draft_created",now)
        return dto(load(id),repository.items(id),actor)
    }
    @Transactional
    fun save(id: UUID,request: SavePlacementDraft,actor: PlacementActor): PlacementDto {
        lockActor(actor)
        validateSelections(request.items)
        val (plan,items)=locked(id,request.items.map { it.shelfId }.toSet(),actor,"edit")
        requireRevision(plan,request.expectedRevision)
        val producer=plan.producerId?:conflict("Сначала подтвердите владельца исторического медиаплана")
        val allBranches=items.branches()+repository.quoteShelves(request.items.map { it.shelfId },plan.month).map { it.branch }
        require(actor,"edit",allBranches.toSet(),producer);activeProducer(producer)
        if(plan.status!=PlacementStatus.draft) conflict("Редактировать можно только черновик")
        validateMonth(plan.month)
        repository.saveItems(plan,request.items,quotes(request.items,plan.month),request.note?.trim(),Instant.now())
        audit(id,actor,"draft_saved",Instant.now())
        return dto(load(id),repository.items(id),actor)
    }
    @Transactional
    fun transition(id: UUID,action: String,request: PlacementTransition,actor: PlacementActor): PlacementDto {
        lockActor(actor)
        if(action !in setOf("submit","approve","reject","cancel")) invalid("Неизвестное действие")
        val (plan,items)=locked(id,actor=actor,action=action)
        requireRevision(plan,request.expectedRevision)
        val producer=plan.producerId?:conflict("Сначала подтвердите владельца исторического медиаплана")
        require(actor,action,items.branches(),producer);require(actor,"financial_data",items.branches(),producer)
        val producerRecord=activeProducer(producer)
        val now=Instant.now();val current=items.filter { it.status in setOf(PlacementStatus.draft,PlacementStatus.pending,PlacementStatus.approved) }
        val reason=request.reason?.trim()?.takeIf(String::isNotEmpty)
        when(action) {
            "submit" -> {
                if(plan.status!=PlacementStatus.draft || current.isEmpty()) conflict("Нет черновика для отправки")
                val company=company(producerRecord)
                validateMonth(plan.month)
                val selections=current.map { PlacementSelection(it.shelfId,it.portion) }
                repository.ensureMonthCells(selections.map { it.shelfId },plan.month,now)
                val actual=quotes(selections,plan.month)
                if(current.any { actual[it.shelfId]!=it.price }) conflict("Цены изменились: сохраните черновик и проверьте новую сумму")
                available(id,selections,plan.month,now)
                repository.snapshotCompany(id,company)
                repository.setItemStatus(id,setOf(PlacementStatus.draft),PlacementStatus.pending)
                repository.transition(id,PlacementStatus.pending,KazakhstanReservationCalendar.deadline(now),actor,null,now)
            }
            "approve","reject" -> {
                if(actor !is PlacementActor.Internal) forbidden()
                if(plan.status!=PlacementStatus.pending || plan.heldUntil==null || !plan.heldUntil.isAfter(now)) conflict("Резерв закрыт или срок подтверждения истёк")
                if(items.none { it.status==PlacementStatus.pending }) conflict("Нет позиций, ожидающих решения")
                if(action=="reject" && reason==null) invalid("Укажите причину отклонения")
                if(action=="approve") available(id,current.map { PlacementSelection(it.shelfId,it.portion) },plan.month,now,allowLegacyHeld=true)
                val status=if(action=="approve") PlacementStatus.approved else PlacementStatus.rejected
                repository.setItemStatus(id,setOf(PlacementStatus.pending),status)
                repository.transition(id,status,null,actor,reason,now)
                if(action=="reject") repository.clearLegacyHold(items,now)
            }
            "cancel" -> {
                if(plan.status !in setOf(PlacementStatus.draft,PlacementStatus.pending,PlacementStatus.approved)) conflict("Медиаплан уже закрыт")
                if(reason==null) invalid("Укажите причину отмены")
                if(actor is PlacementActor.Producer && plan.status==PlacementStatus.approved) forbidden()
                repository.setItemStatus(id,setOf(PlacementStatus.draft,PlacementStatus.pending,PlacementStatus.approved),PlacementStatus.cancelled)
                repository.transition(id,PlacementStatus.cancelled,null,actor,reason,now)
                repository.clearLegacyHold(items,now)
            }
        }
        audit(id,actor,action,now)
        return dto(load(id),repository.items(id),actor)
    }

    /** One transaction per plan avoids holding unrelated branches across a batch; scheduler has one disabled owner. */
    fun expireDue(now: Instant=Instant.now(),limit: Int=100): Int {
        require(limit in 1..1000)
        return repository.due(now,limit).count { id -> TransactionTemplate(transactions).execute {
            val (plan,items)=locked(id)
            if(plan.status!=PlacementStatus.pending || plan.heldUntil==null || plan.heldUntil.isAfter(now)) return@execute false
            repository.setItemStatus(id,setOf(PlacementStatus.pending),PlacementStatus.cancelled)
            val remaining=items.any { it.status==PlacementStatus.approved }
            repository.transition(id,if(remaining) PlacementStatus.approved else PlacementStatus.cancelled,null,PlacementActor.System,"Срок резерва истёк",now)
            repository.clearLegacyHold(items,now)
            audit(id,PlacementActor.System,"expired",now)
            true
        }==true }
    }
    private fun locked(id: UUID,extra: Set<UUID> = emptySet(),actor: PlacementActor?=null,action: String="view"): Pair<Plan,List<PlanItem>> {
        val before=load(id);val old=repository.items(id)
        val shelves=old.map { it.shelfId }.toSet()+extra
        val quotes=repository.quoteShelves(shelves,before.month)
        if(actor!=null) require(actor,if(before.producerId==null) "view" else action,old.branches()+quotes.map { it.branch },before.producerId)
        before.producerId?.let(repository::lockCustomer)
        repository.lock(old.branches()+quotes.map { it.branch },shelves)
        val current=repository.plan(id,true)?:missing();val items=repository.items(id,true)
        if(items.map { it.shelfId }.toSet()!=old.map { it.shelfId }.toSet()) conflict("Состав медиаплана изменился: обновите страницу")
        return current to items
    }
    private fun available(id: UUID,selections: List<PlacementSelection>,month: LocalDate,now: Instant,allowLegacyHeld: Boolean=false) {
        val shelves=repository.quoteShelves(selections.map { it.shelfId },month).associateBy { it.id }
        for(selection in selections) {
            val shelf=shelves[selection.shelfId]?:invalid("Место не найдено")
            if(!shelf.active || shelf.rawStatus!="available" && !(allowLegacyHeld && shelf.rawStatus=="held") ||
                repository.hasOverlap(selection.shelfId,month,selection.portion,id,now)) conflict("Место или выбранная часть заняты, заблокированы либо недоступны")
        }
    }
    private fun quotes(selections: List<PlacementSelection>,month: LocalDate): Map<UUID,Long> {
        val shelves=repository.quoteShelves(selections.map { it.shelfId },month).associateBy { it.id }
        val result=selections.associate { selection ->
            val shelf=shelves[selection.shelfId]?:invalid("Место не найдено")
            if(!shelf.active || selection.portion!=PlacementPortion.whole && shelf.mode!="whole_or_halves") invalid("Место неактивно или не продаётся частями")
            selection.shelfId to price(shelf,selection.portion)
        }
        try { PlacementRepository.sum(result.values) } catch(_:ArithmeticException) { conflict("Общая сумма слишком велика") } catch(_:IllegalArgumentException) { conflict("Общая сумма слишком велика") }
        return result
    }
    private fun price(shelf:QuoteShelf,portion:PlacementPortion):Long {
        val base=if(portion==PlacementPortion.whole) BigDecimal.valueOf(shelf.base) else
            shelf.half?.let(BigDecimal::valueOf)?:BigDecimal.valueOf(shelf.base).divide(BigDecimal(2000),0,RoundingMode.CEILING).multiply(BigDecimal(1000))
        val result=base.multiply(BigDecimal.ONE.add(shelf.adjustment.divide(BigDecimal(100))))
            .divide(BigDecimal(1000),0,RoundingMode.HALF_UP).multiply(BigDecimal(1000)).longValueExact()
            .coerceAtLeast(if(portion==PlacementPortion.whole) 10000 else 5000)
        if(result>Int.MAX_VALUE) conflict("Стоимость превышает допустимый диапазон суммы")
        return result
    }
    private fun dto(plan: Plan,items: List<PlanItem>,actor: PlacementActor): PlacementDto {
        val branches=items.branches();val now=Instant.now()
        fun can(action: String)=can(actor,action,branches,plan.producerId)
        val money=can("financial_data")
        val active=plan.producerId!=null && plan.producerActive
        val complete=plan.producerId?.let {repository.producer(it)?.profileComplete}==true
        val liveHold=plan.status==PlacementStatus.pending && plan.heldUntil?.isAfter(now)==true
        return PlacementDto(plan.id,plan.producerId,plan.producerName,plan.advertiser,plan.month,plan.status,plan.origin,plan.revision,
            if(money) plan.total else null,plan.heldUntil,when {liveHold->"active";plan.status==PlacementStatus.pending->"expired";plan.status==PlacementStatus.approved->"confirmed";else->"closed"},
            3,"Asia/Almaty",plan.reviewNote,plan.note,items.map { PlacementItemDto(it.id,it.shelfId,it.branchId,it.code,it.month,it.portion,it.status,if(money) it.price else null) },
            active && plan.status==PlacementStatus.draft && can("edit"),active && complete && plan.status==PlacementStatus.draft && money && can("submit"),
            active && liveHold && money && can("approve"),active && liveHold && money && can("reject"),
            active && plan.status in setOf(PlacementStatus.draft,PlacementStatus.pending,PlacementStatus.approved) && money && can("cancel") && !(actor is PlacementActor.Producer && plan.status==PlacementStatus.approved),
            plan.createdAt,plan.updatedAt,if(plan.producerId==null) "unresolved" else "verified")
    }
    private fun require(actor: PlacementActor,action: String,branches: Set<UUID>,producer: UUID?) {
        when(actor) {
            is PlacementActor.Internal -> if(producer==null) {
                if(action!="view" && action!="financial_data" || access.currentMerchBranchIds()!=null || access.currentProducerIds()!=null) forbidden()
                access.requireExplicitPermission(PlacementPermissions.key(action))
            } else access.requireMerchPlacementPermission(PlacementPermissions.key(action),branches,producer)
            is PlacementActor.Producer -> { if(producer!=actor.customerId || action !in setOf("view","edit","submit","cancel","financial_data")) forbidden();activeProducer(actor.customerId) }
            PlacementActor.System -> forbidden()
        }
    }
    private fun can(actor: PlacementActor,action: String,branches: Set<UUID>,producer: UUID?): Boolean = try {
        require(actor,action,branches,producer);true
    } catch(e:AppException) { if(e.code==ErrorCode.FORBIDDEN) false else throw e }
    private fun audit(id: UUID,actor: PlacementActor,action: String,now: Instant) {
        val revision=repository.bump(id)
        repository.audit(id,revision,actor,action,json.writeValueAsString(mapOf("state" to load(id),"items" to repository.items(id))),now)
    }
    private fun lockActor(actor:PlacementActor) {when(actor){
        is PlacementActor.Internal->access.lockCurrentActor()
        is PlacementActor.Producer->producerSessions.lockForWrite(ProducerPrincipal(actor.customerId,actor.sessionId))
        PlacementActor.System->forbidden()
    }}
    private fun activeProducer(id:UUID,lock:Boolean=false)=repository.producer(id,lock)?.takeIf {it.active}?:throw AppException(ErrorCode.FORBIDDEN,"Производитель недоступен",HttpStatus.FORBIDDEN)
    private fun company(producer:ProducerListItem)=producer.name?.takeIf(String::isNotBlank)?:throw AppException(ErrorCode.COMPLETE_PROFILE,"Заполните название компании в профиле производителя",HttpStatus.CONFLICT)
    private fun List<PlanItem>.branches()=map { it.branchId }.toSet()
    private fun load(id: UUID)=repository.plan(id)?:missing()
    private fun requireRevision(plan: Plan,expected: Long) { if(plan.revision!=expected) conflict("Медиаплан изменился: загрузите актуальную версию") }
    private fun validateSelections(items: List<PlacementSelection>) { if(items.size !in 1..500 || items.map { it.shelfId }.distinct().size!=items.size) invalid("Выберите от 1 до 500 мест без повторения полки") }
    private fun validateMonth(month: LocalDate) { if(month.dayOfMonth!=1 || month.year !in 1..9999 || month<LocalDate.now(KazakhstanReservationCalendar.timezone).withDayOfMonth(1)) invalid("Выберите полный текущий или будущий календарный месяц") }
    private fun missing(): Nothing=throw AppException(ErrorCode.NOT_FOUND,"Медиаплан не найден",HttpStatus.NOT_FOUND)
    private fun forbidden(): Nothing=throw AppException(ErrorCode.FORBIDDEN,"Недостаточно прав на медиаплан",HttpStatus.FORBIDDEN)
    private fun invalid(message:String): Nothing=throw AppException(ErrorCode.VALIDATION_FAILED,message,HttpStatus.BAD_REQUEST)
    private fun conflict(message:String): Nothing=throw AppException(ErrorCode.CONFLICT,message,HttpStatus.CONFLICT)
}
