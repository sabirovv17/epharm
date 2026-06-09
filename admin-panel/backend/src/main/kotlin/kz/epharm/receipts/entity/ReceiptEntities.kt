package kz.epharm.receipts.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import java.time.Instant

/** Статус pending-бонуса от POSM. */
enum class PendingBonusStatus { awaiting_receipt, matched, expired }

/**
 * Ветка сверки чека:
 *  - pending             — ждёт подтверждения источниками (создан, но ещё ничем не подтверждён)
 *  - moderation_required — подтверждён ТОЛЬКО одним источником (лог ИЛИ Excel) → ручная проверка
 *  - flagged             — анти-фрод (дубль / чужая аптека / расхождение сумм источников)
 *  - approved / rejected — финальные
 */
enum class ReceiptStatus { pending, moderation_required, flagged, approved, rejected }

/** Откуда пришёл чек: фото от фармацевта или POSM-клиент кассы (лог + Excel). */
enum class ReceiptSource { photo, posm }

@Entity
@Table(name = "pending_bonuses")
class PendingBonusEntity(
    @Id
    @Column(name = "id", nullable = false, length = 64)
    var id: String = "",

    @Column(name = "pharmacist_id", nullable = false, length = 64)
    var pharmacistId: String = "",

    @Column(name = "pharmacist_name", nullable = false)
    var pharmacistName: String = "",

    @Column(name = "pharmacy_id", nullable = false, length = 64)
    var pharmacyId: String = "",

    @Column(name = "pharmacy_name", nullable = false)
    var pharmacyName: String = "",

    @Column(name = "sku", nullable = false, length = 64)
    var sku: String = "",

    @Column(name = "product_name", nullable = false)
    var productName: String = "",

    @Column(name = "expected_amount", nullable = false)
    var expectedAmount: Long = 0,

    @Column(name = "bonus", nullable = false)
    var bonus: Long = 0,

    @Column(name = "rule_id", length = 64)
    var ruleId: String? = null,

    @Column(name = "status", nullable = false, length = 32)
    var statusRaw: String = PendingBonusStatus.awaiting_receipt.name,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    var status: PendingBonusStatus
        get() = PendingBonusStatus.valueOf(statusRaw)
        set(value) { statusRaw = value.name }

    @PrePersist
    fun onCreate() {
        val now = Instant.now()
        if (createdAt == Instant.EPOCH) createdAt = now
        updatedAt = now
    }

    @PreUpdate
    fun onUpdate() { updatedAt = Instant.now() }
}

@Entity
@Table(name = "receipts")
class ReceiptEntity(
    @Id
    @Column(name = "id", nullable = false, length = 64)
    var id: String = "",

    @Column(name = "pharmacist_id", nullable = false, length = 64)
    var pharmacistId: String = "",

    @Column(name = "pharmacist_name", nullable = false)
    var pharmacistName: String = "",

    @Column(name = "pharmacy_id", nullable = false, length = 64)
    var pharmacyId: String = "",

    @Column(name = "pharmacy_name", nullable = false)
    var pharmacyName: String = "",

    @Column(name = "photo_url", length = 512)
    var photoUrl: String? = null,

    @Column(name = "fiscal_id", length = 128)
    var fiscalId: String? = null,

    @Column(name = "parsed_sku", nullable = false, length = 64)
    var parsedSku: String = "",

    @Column(name = "parsed_amount", nullable = false)
    var parsedAmount: Long = 0,

    @Column(name = "parsed_cashier", nullable = false)
    var parsedCashier: String = "",

    @Column(name = "parsed_at")
    var parsedAt: Instant? = null,

    @Column(name = "status", nullable = false, length = 32)
    var statusRaw: String = ReceiptStatus.pending.name,

    @Column(name = "auto_approved", nullable = false)
    var autoApproved: Boolean = false,

    @Column(name = "flag_reason", length = 64)
    var flagReason: String? = null,

    @Column(name = "pending_bonus_id", length = 64)
    var pendingBonusId: String? = null,

    @Column(name = "source", nullable = false, length = 16)
    var sourceRaw: String = ReceiptSource.photo.name,

    @Column(name = "confirmed_by_log", nullable = false)
    var confirmedByLog: Boolean = false,

    @Column(name = "confirmed_by_excel", nullable = false)
    var confirmedByExcel: Boolean = false,

    @Column(name = "bonus_credited", nullable = false)
    var bonusCredited: Long = 0,

    @Column(name = "reviewer", length = 64)
    var reviewer: String? = null,

    @Column(name = "reviewed_at")
    var reviewedAt: Instant? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    var status: ReceiptStatus
        get() = ReceiptStatus.valueOf(statusRaw)
        set(value) { statusRaw = value.name }

    var source: ReceiptSource
        get() = ReceiptSource.valueOf(sourceRaw)
        set(value) { sourceRaw = value.name }

    @PrePersist
    fun onCreate() {
        val now = Instant.now()
        if (createdAt == Instant.EPOCH) createdAt = now
        updatedAt = now
    }

    @PreUpdate
    fun onUpdate() { updatedAt = Instant.now() }
}
