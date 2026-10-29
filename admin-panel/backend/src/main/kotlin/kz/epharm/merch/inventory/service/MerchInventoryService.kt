package kz.epharm.merch.inventory.service

import com.fasterxml.jackson.databind.ObjectMapper
import kz.epharm.auth.access.AdministrativeScope
import kz.epharm.auth.policy.PermissionKey
import kz.epharm.merch.inventory.dto.*
import kz.epharm.merch.inventory.repository.MerchInventoryRepository
import kz.epharm.merch.inventory.repository.PlacementRow
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

object MerchInventoryPermissions {
    val view = requireNotNull(PermissionKey.parse("merchandising", "inventory", "view"))
    val manage = requireNotNull(PermissionKey.parse("merchandising", "inventory", "manage_availability"))
    val financial = requireNotNull(PermissionKey.parse("merchandising", "inventory", "financial_data"))
}

/** Owned runtime tables only. Reads never expire holds or mutate booking/task/reward state. */
@Service
class MerchInventoryService(private val repository: MerchInventoryRepository,
    private val scope: AdministrativeScope, private val mapper: ObjectMapper) {

    @Transactional(readOnly = true)
    fun branches(limit: Int, offset: Int): MerchBranchPage {
        scope.requireExplicitPermission(MerchInventoryPermissions.view)
        if (limit !in 1..200 || offset !in 0..1_000_000) invalid("Некорректная страница аптек мерчендайзинга")
        return repository.branches(scope.currentMerchBranchIds(), limit, offset)
    }

    @Transactional(readOnly = true)
    fun inventory(branchId: UUID): MerchInventoryDto {
        scope.requireMerchBranchPermission(MerchInventoryPermissions.view, branchId)
        val branch = branch(branchId)
        val money = scope.canMerchBranchPermission(MerchInventoryPermissions.financial, branchId)
        return MerchInventoryDto(branch, repository.containers(branchId), repository.shelves(branchId).map {
            if (money) it.dto else it.dto.copy(baseMonthlyPrice = null, halfMonthlyPrice = null)
        })
    }

    @Transactional(readOnly = true)
    fun calendar(branchId: UUID, startMonth: LocalDate, monthCount: Int): MerchAvailabilityCalendar {
        scope.requireMerchBranchPermission(MerchInventoryPermissions.view, branchId)
        requireMonth(startMonth)
        if (monthCount !in 1..12) invalid("Календарь может содержать от 1 до 12 месяцев")
        val branch = branch(branchId)
        val months = (0 until monthCount).map { startMonth.plusMonths(it.toLong()) }
        if (months.last().year > 9999) invalid("Некорректный диапазон календаря")
        val now = Instant.now()
        val cells = repository.cells(branchId, months.first(), months.last()).associateBy { it.shelfId to it.month }
        val placements = repository.placements(branchId, months.first(), months.last(), now).groupBy { it.shelfId to it.month }
        val manage = scope.canMerchBranchPermission(MerchInventoryPermissions.manage, branchId)
        val money = scope.canMerchBranchPermission(MerchInventoryPermissions.financial, branchId)
        val adjustment = repository.adjustment(branchId)
        val shelves = repository.shelves(branchId).map { shelf ->
            val row = shelf.dto
            MerchCalendarShelfDto(row.id, row.code, row.category, row.width,
                if (money) adjustedPrice(shelf.basePrice, adjustment) else null, row.active, months.map { month ->
                val cell = cells[row.id to month]
                val placement = placements[row.id to month]?.let(::placement)
                val status = when {
                    !row.active -> MerchInventoryStatus.blocked
                    placement != null -> placement.status
                    else -> cell?.status ?: MerchInventoryStatus.available
                }
                MerchCalendarCellDto(month, cell?.id, status, cell?.reason, cell?.note,
                    manage && row.active && placement == null && status in editableStatuses, placement)
            })
        }
        val totals = months.mapIndexed { index, month ->
            val rows = shelves.map { it.months[index].status }
            MerchMonthSummary(month, rows.count { it == MerchInventoryStatus.available }, rows.count { it == MerchInventoryStatus.blocked },
                rows.count { it == MerchInventoryStatus.held }, rows.count { it == MerchInventoryStatus.booked })
        }
        return MerchAvailabilityCalendar(branch, startMonth, monthCount, totals, shelves, manage, money)
    }

    @Transactional
    fun setAvailability(branchId: UUID, request: MerchAvailabilityRequest, actor: UUID): MerchAvailabilityResult {
        scope.lockCurrentActor()
        scope.requireMerchBranchPermission(MerchInventoryPermissions.view, branchId)
        scope.requireMerchBranchPermission(MerchInventoryPermissions.manage, branchId)
        val months = months(request.fromMonth, request.toMonth)
        if (request.shelfIds.isEmpty() || request.shelfIds.distinct().size != request.shelfIds.size) invalid("Выберите полки без повторений")
        if (request.action == MerchAvailabilityAction.block && request.reason == null) invalid("Укажите причину блокировки")
        if ((request.note?.codePointCount(0, request.note.length) ?: 0) > 300) invalid("Комментарий слишком длинный")
        branch(branchId, lock = true)
        val shelves = repository.shelves(branchId, request.shelfIds, lock = true)
        if (shelves.size != request.shelfIds.size || shelves.any { !it.dto.active }) invalid("Одна или несколько полок не относятся к аптеке или неактивны")
        val now = Instant.now()
        val cells = repository.cells(branchId, months.first(), months.last()).associateBy { it.shelfId to it.month }
        val placements = repository.placements(branchId, months.first(), months.last(), now).map { it.shelfId to it.month }.toSet()
        var changed = 0
        var protectedCount = 0
        val note = request.note?.trim()?.takeIf(String::isNotEmpty)
        for (shelf in shelves) for (month in months) {
            val key = shelf.dto.id to month
            if (key in placements || cells[key]?.status in protectedStatuses) { protectedCount++; continue }
            changed += when (request.action) {
                MerchAvailabilityAction.block -> repository.block(shelf, month, request.reason!!, note, actor, now)
                MerchAvailabilityAction.unblock -> repository.unblock(shelf.dto.id, month, now)
            }
        }
        val target = Math.multiplyExact(shelves.size, months.size)
        val result = MerchAvailabilityResult(branchId, request.action, months.first(), months.last(), target, changed,
            protectedCount, (target - changed - protectedCount).coerceAtLeast(0), UUID.randomUUID())
        repository.audit(result.auditId, branchId, actor, request.action, now, mapper.writeValueAsString(mapOf(
            "shelfIds" to request.shelfIds, "fromMonth" to request.fromMonth, "toMonth" to request.toMonth,
            "reason" to request.reason, "note" to note, "targetCount" to target, "changedCount" to changed, "protectedCount" to protectedCount)))
        return result
    }

    private fun branch(id: UUID, lock: Boolean = false) = repository.branch(id, lock) ?: throw AppException(
        ErrorCode.NOT_FOUND, "Аптека мерчендайзинга не найдена", HttpStatus.NOT_FOUND)
    private fun requireMonth(value: LocalDate) { if (value.dayOfMonth != 1 || value.year !in 1..9999) invalid("Месяц должен начинаться с первого числа допустимого календарного года") }
    private fun months(from: LocalDate, to: LocalDate): List<LocalDate> {
        requireMonth(from); requireMonth(to)
        val count = ChronoUnit.MONTHS.between(from, to) + 1
        if (count !in 1..12) invalid("Диапазон должен содержать от 1 до 12 календарных месяцев")
        return (0 until count).map { from.plusMonths(it) }
    }
    private fun adjustedPrice(base: Long, adjustment: BigDecimal): Long = BigDecimal.valueOf(base)
        .multiply(BigDecimal.ONE.add(adjustment.divide(BigDecimal(100))))
        .divide(BigDecimal(1000), 0, RoundingMode.HALF_UP).multiply(BigDecimal(1000)).longValueExact().coerceAtLeast(10_000)
    private fun placement(rows: List<PlacementRow>): MerchPlacementDto {
        val portions = rows.map { it.item.portion }.toSet()
        val portion = when { "whole" in portions -> "whole"; "left" in portions && "right" in portions -> "both"; "left" in portions -> "left"; else -> "right" }
        return MerchPlacementDto(if (rows.any { it.status == "approved" }) MerchInventoryStatus.booked else MerchInventoryStatus.held,
            portion, when (portion) { "whole" -> "Целая полка"; "both" -> "Обе половины"; "left" -> "Левая половина"; else -> "Правая половина" },
            rows.filter { it.status == "pending" }.mapNotNull { it.heldUntil }.maxOrNull(), rows.any { it.item.origin == "admin_assignment" }, rows.map { it.item })
    }
    private fun invalid(message: String): Nothing = throw AppException(ErrorCode.VALIDATION_FAILED, message, HttpStatus.BAD_REQUEST)
    private val editableStatuses = setOf(MerchInventoryStatus.available, MerchInventoryStatus.blocked)
    private val protectedStatuses = setOf(MerchInventoryStatus.held, MerchInventoryStatus.booked)
}
