package kz.epharm.fulfillment.service

import com.fasterxml.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.security.SecureRandom
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID
import kz.epharm.fulfillment.dto.FulfillmentActionRequest
import kz.epharm.fulfillment.dto.FulfillmentDeviceDto
import kz.epharm.fulfillment.dto.FulfillmentFeatureStatusDto
import kz.epharm.fulfillment.dto.FulfillmentLineDto
import kz.epharm.fulfillment.dto.FulfillmentOrderDto
import kz.epharm.fulfillment.dto.FulfillmentOrderPageDto
import kz.epharm.fulfillment.dto.FulfillmentPharmacyLinkDto
import kz.epharm.fulfillment.dto.FulfillmentPharmacyLinkRequest
import kz.epharm.fulfillment.dto.FulfillmentUpdateDto
import kz.epharm.fulfillment.dto.FulfillmentUpdateFeedDto
import kz.epharm.fulfillment.dto.RegisterFulfillmentDeviceResponse
import kz.epharm.fulfillment.dto.StorefrontOrderAck
import kz.epharm.fulfillment.dto.StorefrontOrderCreatedRequest
import kz.epharm.fulfillment.model.FulfillmentAction
import kz.epharm.fulfillment.model.FulfillmentDeviceContext
import kz.epharm.fulfillment.model.FulfillmentOrderRecord
import kz.epharm.fulfillment.model.FulfillmentStatus
import kz.epharm.fulfillment.security.FulfillmentCrypto
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

@Service
class FulfillmentService(
    private val jdbc: JdbcTemplate,
    private val transactions: TransactionTemplate,
    private val objectMapper: ObjectMapper,
    private val crypto: FulfillmentCrypto,
    @Value("\${app.fulfillment.enabled:false}") private val enabled: Boolean,
    @Value("\${app.fulfillment.device-registration-enabled:false}")
    private val deviceRegistrationEnabled: Boolean,
) {
    private val random = SecureRandom()

    fun ingest(rawBody: ByteArray, request: StorefrontOrderCreatedRequest): StorefrontOrderAck {
        ensureEnabled()
        validateCreated(request)
        val requestHash = crypto.sha256Hex(rawBody)
        val eventId = runCatching { UUID.fromString(request.eventId) }.getOrElse {
            invalid("eventId must be a UUID")
        }
        return transactions.execute {
            val collisions = jdbc.query(
                """
                SELECT order_id, event_id::text, request_sha256
                FROM fulfillment_orders
                WHERE event_id = ? OR order_id = ?
                FOR UPDATE
                """.trimIndent(),
                { rs, _ -> Triple(rs.getString(1), rs.getString(2), rs.getString(3)) },
                eventId,
                request.orderId,
            )
            if (collisions.isNotEmpty()) {
                val exact = collisions.singleOrNull()?.takeIf {
                    it.first == request.orderId &&
                        it.second.equals(request.eventId, ignoreCase = true) &&
                        it.third == requestHash
                } ?: conflict("Idempotency key was reused with a different order body")
                val current = requireOrder(exact.first)
                return@execute ack(current, accepted = false)
            }

            val pharmacyId = resolveActivePharmacy(request.pharmacyExternalId)
            jdbc.update(
                """
                INSERT INTO fulfillment_orders (
                    order_id, event_id, order_number, request_sha256,
                    pharmacy_external_id, pharmacy_id, created_at, total, currency,
                    delivery, payment_method, payment_status, is_demo, pickup_code_hmac
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                request.orderId,
                eventId,
                request.number,
                requestHash,
                request.pharmacyExternalId,
                pharmacyId,
                Timestamp.from(request.createdAt),
                request.total,
                request.currency,
                request.delivery,
                request.paymentMethod,
                request.paymentStatus,
                request.demo,
                crypto.pickupCodeHmac(request.orderId, request.pickupCode),
            )
            request.lines.forEachIndexed { index, line ->
                jdbc.update(
                    """
                    INSERT INTO fulfillment_order_lines (
                        order_id, line_no, product_id, sku, title, quantity, unit_price
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    request.orderId,
                    index,
                    line.productId,
                    line.sku,
                    line.title,
                    line.quantity,
                    line.unitPrice,
                )
            }
            audit(
                request.orderId,
                "storefront",
                request.eventId,
                "order_ingested",
                mapOf("assigned" to (pharmacyId != null), "externalPharmacyId" to request.pharmacyExternalId),
            )
            ack(requireOrder(request.orderId), accepted = true)
        }!!
    }

    fun updates(after: Long, requestedLimit: Int): FulfillmentUpdateFeedDto {
        ensureEnabled()
        if (after < 0) invalid("after must be non-negative")
        val limit = requestedLimit.coerceIn(1, 200)
        val rows = jdbc.query(
            """
            SELECT cursor, order_id, status, version, changed_at
            FROM fulfillment_order_updates
            WHERE cursor > ?
            ORDER BY cursor
            LIMIT ?
            """.trimIndent(),
            { rs, _ ->
                FulfillmentUpdateDto(
                    cursor = rs.getLong("cursor"),
                    orderId = rs.getString("order_id"),
                    status = rs.getString("status"),
                    version = rs.getLong("version"),
                    changedAt = rs.getTimestamp("changed_at").toInstant(),
                )
            },
            after,
            limit + 1,
        )
        val page = rows.take(limit)
        return FulfillmentUpdateFeedDto(
            after = after,
            nextCursor = page.lastOrNull()?.cursor ?: after,
            hasMore = rows.size > limit,
            updates = page,
        )
    }

    fun registerDevice(deviceIdRaw: String, pharmacyIdRaw: String): RegisterFulfillmentDeviceResponse {
        ensureEnabled()
        if (!deviceRegistrationEnabled) forbidden("Fulfillment device registration is disabled")
        val deviceId = bounded(deviceIdRaw, "deviceId", 128)
        val pharmacyId = bounded(pharmacyIdRaw, "pharmacyId", 64)
        val pharmacyExists = jdbc.queryForObject(
            "SELECT count(*) FROM pharmacies WHERE id = ? AND active = true",
            Long::class.java,
            pharmacyId,
        ) ?: 0L
        if (pharmacyExists != 1L) notFound("Active pharmacy was not found")

        val tokenBytes = ByteArray(32).also(random::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes)
        val tokenHash = crypto.sha256Hex(token)
        transactions.executeWithoutResult {
            jdbc.update(
                """
                INSERT INTO fulfillment_devices (
                    id, device_id, pharmacy_id, secret_sha256, secret_hint, active,
                    registered_at, last_seen_at, revoked_at
                ) VALUES (?, ?, ?, ?, ?, true, now(), now(), NULL)
                ON CONFLICT (device_id, pharmacy_id) DO UPDATE SET
                    secret_sha256 = EXCLUDED.secret_sha256,
                    secret_hint = EXCLUDED.secret_hint,
                    active = true,
                    registered_at = now(),
                    last_seen_at = now(),
                    revoked_at = NULL
                """.trimIndent(),
                UUID.randomUUID(),
                deviceId,
                pharmacyId,
                tokenHash,
                token.takeLast(8),
            )
            audit(null, "posm", deviceId, "device_registered", mapOf("pharmacyId" to pharmacyId))
        }
        return RegisterFulfillmentDeviceResponse(deviceId, pharmacyId, token)
    }

    fun authenticateDevice(token: String?): FulfillmentDeviceContext {
        ensureEnabled()
        val normalized = token?.trim()?.takeIf { it.length in 32..256 }
            ?: unauthorized("Missing fulfillment device token")
        val hash = crypto.sha256Hex(normalized)
        val rows = jdbc.query(
            """
            SELECT d.id::text, d.device_id, d.pharmacy_id
            FROM fulfillment_devices d
            JOIN pharmacies p ON p.id = d.pharmacy_id AND p.active = true
            WHERE d.secret_sha256 = ? AND d.active = true
            """.trimIndent(),
            { rs, _ -> FulfillmentDeviceContext(rs.getString(1), rs.getString(2), rs.getString(3)) },
            hash,
        )
        val context = rows.singleOrNull() ?: unauthorized("Invalid or revoked fulfillment device token")
        jdbc.update("UPDATE fulfillment_devices SET last_seen_at = now() WHERE id = ?::uuid", context.id)
        return context
    }

    fun listForDevice(
        device: FulfillmentDeviceContext,
        statusFilter: String,
        offsetRaw: Int,
        limitRaw: Int,
    ): FulfillmentOrderPageDto {
        ensureEnabled()
        val statuses = when (statusFilter.lowercase()) {
            "submitted" -> listOf(FulfillmentStatus.submitted)
            "active", "" -> listOf(
                FulfillmentStatus.submitted,
                FulfillmentStatus.assembling,
                FulfillmentStatus.ready,
            )
            else -> invalid("Unsupported POSM order status filter")
        }
        return listOrders(device.pharmacyId, statuses, assigned = true, offsetRaw, limitRaw.coerceAtMost(50))
    }

    fun listForAdmin(
        pharmacyId: String?,
        status: String?,
        unassigned: Boolean?,
        offset: Int,
        limit: Int,
    ): FulfillmentOrderPageDto {
        val statuses = status?.takeIf { it.isNotBlank() }?.let {
            listOf(parseStatus(it))
        }
        return listOrders(pharmacyId, statuses, assigned = unassigned?.not(), offset, limit.coerceAtMost(200))
    }

    fun getForDevice(orderId: String, device: FulfillmentDeviceContext): FulfillmentOrderDto {
        val order = requireOrder(orderId)
        if (order.pharmacyId != device.pharmacyId) notFound("Order was not found")
        return dto(order)
    }

    fun getForAdmin(orderId: String): FulfillmentOrderDto {
        return dto(requireOrder(orderId))
    }

    fun featureStatus() = FulfillmentFeatureStatusDto(enabled, deviceRegistrationEnabled)

    fun actAsDevice(
        orderId: String,
        request: FulfillmentActionRequest,
        device: FulfillmentDeviceContext,
    ): FulfillmentOrderDto = act(orderId, request, "posm", device.deviceId, device.pharmacyId)

    fun actAsAdmin(orderId: String, request: FulfillmentActionRequest, adminId: String): FulfillmentOrderDto =
        act(orderId, request, "hq", adminId, null)

    fun assign(orderId: String, pharmacyIdRaw: String, expectedVersion: Long, adminId: String): FulfillmentOrderDto {
        val pharmacyId = bounded(pharmacyIdRaw, "pharmacyId", 64)
        if (expectedVersion <= 0) invalid("expectedVersion must be positive")
        return transactions.execute {
            val current = requireOrder(orderId, lock = true)
            if (current.version != expectedVersion) conflict("Order version is stale")
            val active = jdbc.queryForObject(
                "SELECT count(*) FROM pharmacies WHERE id = ? AND active = true",
                Long::class.java,
                pharmacyId,
            ) ?: 0L
            if (active != 1L) notFound("Active pharmacy was not found")
            if (current.status in setOf(FulfillmentStatus.completed, FulfillmentStatus.cancelled)) {
                conflict("Terminal order cannot be reassigned")
            }
            jdbc.update(
                "UPDATE fulfillment_orders SET pharmacy_id = ?, version = version + 1, updated_at = now() WHERE order_id = ?",
                pharmacyId,
                current.orderId,
            )
            audit(current.orderId, "hq", adminId, "order_assigned", mapOf("pharmacyId" to pharmacyId))
            dto(requireOrder(current.orderId))
        }!!
    }

    fun saveLink(request: FulfillmentPharmacyLinkRequest, adminId: String): FulfillmentPharmacyLinkDto {
        val externalId = bounded(request.externalId, "externalId", 256)
        val pharmacyId = bounded(request.pharmacyId, "pharmacyId", 64)
        val crmBranchId = request.crmBranchId?.trim()?.takeIf { it.isNotEmpty() }?.also {
            if (it.length > 256) invalid("crmBranchId is too long")
        }
        return transactions.execute {
            val exists = jdbc.queryForObject(
                "SELECT count(*) FROM pharmacies WHERE id = ?",
                Long::class.java,
                pharmacyId,
            ) ?: 0L
            if (exists != 1L) notFound("Pharmacy was not found")
            jdbc.update(
                """
                INSERT INTO fulfillment_pharmacy_links (
                    external_id, pharmacy_id, crm_branch_id, active, updated_at
                ) VALUES (?, ?, ?, ?, now())
                ON CONFLICT (external_id) DO UPDATE SET
                    pharmacy_id = EXCLUDED.pharmacy_id,
                    crm_branch_id = EXCLUDED.crm_branch_id,
                    active = EXCLUDED.active,
                    updated_at = now()
                """.trimIndent(),
                externalId,
                pharmacyId,
                crmBranchId,
                request.active,
            )
            audit(null, "hq", adminId, "pharmacy_link_saved", mapOf("externalId" to externalId, "pharmacyId" to pharmacyId))
            requireLink(externalId)
        }!!
    }

    fun listLinks(): List<FulfillmentPharmacyLinkDto> {
        return jdbc.query(
            """
            SELECT l.external_id, l.pharmacy_id, p.name, p.addr, l.crm_branch_id, l.active, l.updated_at
            FROM fulfillment_pharmacy_links l
            JOIN pharmacies p ON p.id = l.pharmacy_id
            ORDER BY p.name, l.external_id
            """.trimIndent(),
            ::mapLink,
        )
    }

    fun listDevices(): List<FulfillmentDeviceDto> {
        return jdbc.query(
            """
            SELECT d.id::text, d.device_id, d.pharmacy_id, p.name, d.active, d.secret_hint,
                   d.registered_at, d.last_seen_at, d.revoked_at
            FROM fulfillment_devices d
            JOIN pharmacies p ON p.id = d.pharmacy_id
            ORDER BY d.last_seen_at DESC, d.device_id
            """.trimIndent(),
        ) { rs, _ ->
            FulfillmentDeviceDto(
                id = rs.getString(1),
                deviceId = rs.getString(2),
                pharmacyId = rs.getString(3),
                pharmacyName = rs.getString(4),
                active = rs.getBoolean(5),
                secretHint = rs.getString(6),
                registeredAt = rs.getTimestamp(7).toInstant(),
                lastSeenAt = rs.getTimestamp(8).toInstant(),
                revokedAt = rs.getTimestamp(9)?.toInstant(),
            )
        }
    }

    fun revokeDevice(id: UUID, adminId: String) {
        val changed = jdbc.update(
            "UPDATE fulfillment_devices SET active = false, revoked_at = now() WHERE id = ? AND active = true",
            id,
        )
        if (changed == 0) notFound("Active fulfillment device was not found")
        audit(null, "hq", adminId, "device_revoked", mapOf("deviceId" to id.toString()))
    }

    private fun listOrders(
        pharmacyId: String?,
        statuses: List<FulfillmentStatus>?,
        assigned: Boolean?,
        offsetRaw: Int,
        limitRaw: Int,
    ): FulfillmentOrderPageDto {
        val offset = offsetRaw.coerceAtLeast(0)
        val limit = limitRaw.coerceIn(1, 200)
        val clauses = mutableListOf<String>()
        val args = mutableListOf<Any>()
        if (!pharmacyId.isNullOrBlank()) {
            clauses += "o.pharmacy_id = ?"
            args += pharmacyId
        }
        if (!statuses.isNullOrEmpty()) {
            clauses += "o.status IN (${statuses.joinToString(",") { "?" }})"
            args.addAll(statuses.map { it.name })
        }
        when (assigned) {
            true -> clauses += "o.pharmacy_id IS NOT NULL"
            false -> clauses += "o.pharmacy_id IS NULL"
            null -> Unit
        }
        val where = if (clauses.isEmpty()) "" else "WHERE ${clauses.joinToString(" AND ")}"
        args += limit + 1
        args += offset
        val rows = jdbc.query(
            """
            SELECT o.*, p.name AS pharmacy_name, p.addr AS pharmacy_address
            FROM fulfillment_orders o
            LEFT JOIN pharmacies p ON p.id = o.pharmacy_id
            $where
            ORDER BY
                CASE o.status WHEN 'ready' THEN 0 WHEN 'submitted' THEN 1 WHEN 'assembling' THEN 2 ELSE 3 END,
                o.created_at,
                o.order_id
            LIMIT ? OFFSET ?
            """.trimIndent(),
            ::mapOrder,
            *args.toTypedArray(),
        )
        return FulfillmentOrderPageDto(rows.take(limit).map(::dto), offset, limit, rows.size > limit)
    }

    private fun act(
        orderIdRaw: String,
        request: FulfillmentActionRequest,
        actorType: String,
        actorId: String,
        requiredPharmacyId: String?,
    ): FulfillmentOrderDto {
        ensureEnabled()
        val orderId = bounded(orderIdRaw, "orderId", 256)
        if (request.expectedVersion <= 0) invalid("expectedVersion must be positive")
        val action = runCatching { FulfillmentAction.valueOf(request.action.trim().lowercase()) }.getOrElse {
            invalid("Unsupported fulfillment action")
        }
        val txResult = transactions.execute {
            val current = requireOrder(orderId, lock = true)
            if (requiredPharmacyId != null && current.pharmacyId != requiredPharmacyId) {
                return@execute ActionResult(error = AppException(ErrorCode.NOT_FOUND, "Order was not found", HttpStatus.NOT_FOUND))
            }
            if (current.pharmacyId == null && actorType == "posm") {
                return@execute ActionResult(error = AppException(ErrorCode.NOT_FOUND, "Order was not found", HttpStatus.NOT_FOUND))
            }
            if (current.version != request.expectedVersion) {
                return@execute ActionResult(error = AppException(ErrorCode.CONFLICT, "Order version is stale", HttpStatus.CONFLICT))
            }
            if (action != FulfillmentAction.cancel) {
                val pharmacyId = current.pharmacyId ?: return@execute ActionResult(
                    error = AppException(ErrorCode.CONFLICT, "Order must be assigned before processing", HttpStatus.CONFLICT),
                )
                val pharmacyActive = jdbc.queryForObject(
                    "SELECT count(*) FROM pharmacies WHERE id = ? AND active = true",
                    Long::class.java,
                    pharmacyId,
                ) ?: 0L
                if (pharmacyActive != 1L) {
                    return@execute ActionResult(
                        error = AppException(ErrorCode.CONFLICT, "Inactive pharmacy cannot process orders", HttpStatus.CONFLICT),
                    )
                }
            }

            val target = when (action) {
                FulfillmentAction.assemble -> {
                    if (current.status != FulfillmentStatus.submitted) return@execute illegalTransition()
                    FulfillmentStatus.assembling
                }
                FulfillmentAction.ready -> {
                    if (current.status != FulfillmentStatus.assembling) return@execute illegalTransition()
                    FulfillmentStatus.ready
                }
                FulfillmentAction.issue -> {
                    if (current.status != FulfillmentStatus.ready) return@execute illegalTransition()
                    val code = request.code?.trim().orEmpty()
                    if (!code.matches(Regex("\\d{6}"))) {
                        return@execute ActionResult(error = AppException(ErrorCode.VALIDATION_FAILED, "Pickup code must contain six digits"))
                    }
                    val now = Instant.now()
                    if (current.pickupLockedUntil?.isAfter(now) == true) {
                        return@execute ActionResult(
                            error = AppException(ErrorCode.CONFLICT, "Pickup code is temporarily locked", HttpStatus.TOO_MANY_REQUESTS),
                        )
                    }
                    val attempts = if (current.pickupLockedUntil != null && !current.pickupLockedUntil.isAfter(now)) 0
                        else current.pickupAttempts
                    val expectedCode = jdbc.queryForObject(
                        "SELECT pickup_code_hmac FROM fulfillment_orders WHERE order_id = ?",
                        String::class.java,
                        current.orderId,
                    )!!
                    if (!crypto.constantTimeEquals(crypto.pickupCodeHmac(current.orderId, code), expectedCode)) {
                        val nextAttempts = (attempts + 1).coerceAtMost(5)
                        val lockUntil = if (nextAttempts >= 5) now.plus(15, ChronoUnit.MINUTES) else null
                        jdbc.update(
                            "UPDATE fulfillment_orders SET pickup_attempts = ?, pickup_locked_until = ?, updated_at = now() WHERE order_id = ?",
                            nextAttempts,
                            lockUntil?.let(Timestamp::from),
                            current.orderId,
                        )
                        audit(
                            current.orderId,
                            actorType,
                            actorId,
                            "pickup_code_rejected",
                            mapOf("attempts" to nextAttempts, "locked" to (lockUntil != null)),
                        )
                        val status = if (lockUntil != null) HttpStatus.TOO_MANY_REQUESTS else HttpStatus.BAD_REQUEST
                        return@execute ActionResult(
                            error = AppException(ErrorCode.VALIDATION_FAILED, "Invalid pickup code", status),
                        )
                    }
                    val paymentError = paymentIssueError(current, request.cashCollected)
                    if (paymentError != null) return@execute ActionResult(error = paymentError)
                    FulfillmentStatus.completed
                }
                FulfillmentAction.cancel -> {
                    if (current.status !in setOf(
                            FulfillmentStatus.submitted,
                            FulfillmentStatus.assembling,
                            FulfillmentStatus.ready,
                        )
                    ) return@execute illegalTransition()
                    val reason = request.reason?.trim().orEmpty()
                    if (reason.isBlank() || reason.length > 500) {
                        return@execute ActionResult(error = AppException(ErrorCode.VALIDATION_FAILED, "Cancellation reason is required"))
                    }
                    FulfillmentStatus.cancelled
                }
            }

            val cancellationReason = request.reason?.trim()?.takeIf { target == FulfillmentStatus.cancelled }
            val completedAt = Instant.now().takeIf { target == FulfillmentStatus.completed }
            val paymentStatus = if (
                target == FulfillmentStatus.completed && current.paymentMethod.equals("cash", ignoreCase = true) && request.cashCollected
            ) "cash_collected" else current.paymentStatus
            jdbc.update(
                """
                UPDATE fulfillment_orders
                SET status = ?, version = version + 1, cancellation_reason = ?, completed_at = ?,
                    payment_status = ?, pickup_attempts = CASE WHEN ? = 'completed' THEN 0 ELSE pickup_attempts END,
                    pickup_locked_until = CASE WHEN ? = 'completed' THEN NULL ELSE pickup_locked_until END,
                    updated_at = now()
                WHERE order_id = ?
                """.trimIndent(),
                target.name,
                cancellationReason,
                completedAt?.let(Timestamp::from),
                paymentStatus,
                target.name,
                target.name,
                current.orderId,
            )
            val updated = requireOrder(current.orderId)
            jdbc.update(
                """
                INSERT INTO fulfillment_order_updates (order_id, version, status, source)
                VALUES (?, ?, ?, ?)
                """.trimIndent(),
                updated.orderId,
                updated.version,
                updated.status.name,
                actorType,
            )
            audit(
                updated.orderId,
                actorType,
                actorId,
                "order_${action.name}",
                mapOf("from" to current.status.name, "to" to updated.status.name, "version" to updated.version),
            )
            ActionResult(order = dto(updated))
        }!!
        txResult.error?.let { throw it }
        return txResult.order!!
    }

    private fun paymentIssueError(order: FulfillmentOrderRecord, cashCollected: Boolean): AppException? {
        if (order.demo) {
            return if (order.paymentStatus == "demo_no_charge") null else AppException(
                ErrorCode.CONFLICT,
                "Demo order is not trusted for no-charge issue",
                HttpStatus.CONFLICT,
            )
        }
        if (order.paymentMethod.equals("cash", ignoreCase = true)) {
            return if (order.paymentStatus in setOf("paid", "cash_collected") || cashCollected) null else AppException(
                ErrorCode.CONFLICT,
                "Cash collection must be explicitly confirmed",
                HttpStatus.CONFLICT,
            )
        }
        return if (order.paymentStatus == "paid") null else AppException(
            ErrorCode.CONFLICT,
            "Card order cannot be issued before trusted payment confirmation",
            HttpStatus.CONFLICT,
        )
    }

    private fun illegalTransition() = ActionResult(
        error = AppException(ErrorCode.CONFLICT, "Illegal order status transition", HttpStatus.CONFLICT),
    )

    private fun resolveActivePharmacy(externalId: String): String? = jdbc.query(
        """
        SELECT l.pharmacy_id
        FROM fulfillment_pharmacy_links l
        JOIN pharmacies p ON p.id = l.pharmacy_id AND p.active = true
        WHERE l.external_id = ? AND l.active = true
        """.trimIndent(),
        { rs, _ -> rs.getString(1) },
        externalId,
    ).singleOrNull()

    private fun requireOrder(orderId: String, lock: Boolean = false): FulfillmentOrderRecord {
        val suffix = if (lock) " FOR UPDATE OF o" else ""
        return jdbc.query(
            """
            SELECT o.*, p.name AS pharmacy_name, p.addr AS pharmacy_address
            FROM fulfillment_orders o
            LEFT JOIN pharmacies p ON p.id = o.pharmacy_id
            WHERE o.order_id = ?$suffix
            """.trimIndent(),
            ::mapOrder,
            orderId,
        ).singleOrNull() ?: notFound("Order was not found")
    }

    private fun mapOrder(rs: ResultSet, @Suppress("UNUSED_PARAMETER") row: Int): FulfillmentOrderRecord =
        FulfillmentOrderRecord(
            orderId = rs.getString("order_id"),
            eventId = rs.getString("event_id"),
            number = rs.getString("order_number"),
            pharmacyExternalId = rs.getString("pharmacy_external_id"),
            pharmacyId = rs.getString("pharmacy_id"),
            pharmacyName = rs.getString("pharmacy_name"),
            pharmacyAddress = rs.getString("pharmacy_address"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            total = rs.getBigDecimal("total"),
            currency = rs.getString("currency"),
            delivery = rs.getString("delivery"),
            paymentMethod = rs.getString("payment_method"),
            paymentStatus = rs.getString("payment_status"),
            demo = rs.getBoolean("is_demo"),
            status = FulfillmentStatus.valueOf(rs.getString("status")),
            version = rs.getLong("version"),
            pickupAttempts = rs.getInt("pickup_attempts"),
            pickupLockedUntil = rs.getTimestamp("pickup_locked_until")?.toInstant(),
            cancellationReason = rs.getString("cancellation_reason"),
            completedAt = rs.getTimestamp("completed_at")?.toInstant(),
            updatedAt = rs.getTimestamp("updated_at").toInstant(),
        )

    private fun dto(order: FulfillmentOrderRecord): FulfillmentOrderDto = FulfillmentOrderDto(
        orderId = order.orderId,
        number = order.number,
        pharmacyExternalId = order.pharmacyExternalId,
        pharmacyId = order.pharmacyId,
        pharmacyName = order.pharmacyName,
        pharmacyAddress = order.pharmacyAddress,
        createdAt = order.createdAt,
        total = order.total,
        currency = order.currency,
        delivery = order.delivery,
        paymentMethod = order.paymentMethod,
        paymentStatus = order.paymentStatus,
        demo = order.demo,
        status = order.status.name,
        version = order.version,
        pickupAttempts = order.pickupAttempts,
        pickupLockedUntil = order.pickupLockedUntil,
        cancellationReason = order.cancellationReason,
        completedAt = order.completedAt,
        updatedAt = order.updatedAt,
        lines = jdbc.query(
            """
            SELECT product_id, sku, title, quantity, unit_price
            FROM fulfillment_order_lines WHERE order_id = ? ORDER BY line_no
            """.trimIndent(),
            { rs, _ ->
                FulfillmentLineDto(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getInt(4),
                    rs.getBigDecimal(5),
                )
            },
            order.orderId,
        ),
    )

    private fun ack(order: FulfillmentOrderRecord, accepted: Boolean) = StorefrontOrderAck(
        orderId = order.orderId,
        accepted = accepted,
        status = order.status.name,
        version = order.version,
        assigned = order.pharmacyId != null,
    )

    private fun requireLink(externalId: String): FulfillmentPharmacyLinkDto = jdbc.query(
        """
        SELECT l.external_id, l.pharmacy_id, p.name, p.addr, l.crm_branch_id, l.active, l.updated_at
        FROM fulfillment_pharmacy_links l JOIN pharmacies p ON p.id = l.pharmacy_id
        WHERE l.external_id = ?
        """.trimIndent(),
        ::mapLink,
        externalId,
    ).singleOrNull() ?: notFound("Pharmacy link was not found")

    private fun mapLink(rs: ResultSet, @Suppress("UNUSED_PARAMETER") row: Int) = FulfillmentPharmacyLinkDto(
        externalId = rs.getString(1),
        pharmacyId = rs.getString(2),
        pharmacyName = rs.getString(3),
        pharmacyAddress = rs.getString(4),
        crmBranchId = rs.getString(5),
        active = rs.getBoolean(6),
        updatedAt = rs.getTimestamp(7).toInstant(),
    )

    private fun validateCreated(request: StorefrontOrderCreatedRequest) {
        bounded(request.orderId, "orderId", 256)
        bounded(request.number, "number", 128)
        bounded(request.pharmacyExternalId, "pharmacyExternalId", 256)
        if (request.currency != "KZT") invalid("currency must be KZT")
        if (request.total < BigDecimal.ZERO || request.total.scale() > 2) invalid("total must be non-negative with at most 2 decimals")
        if (!request.pickupCode.matches(Regex("\\d{6}"))) invalid("pickupCode must contain six digits")
        if (request.lines.isEmpty() || request.lines.size > 200) invalid("lines must contain 1..200 items")
        if (request.delivery !in setOf("pickup", "pharmacy")) invalid("delivery must target a pharmacy")
        if (request.paymentMethod !in setOf("cash", "card", "kaspi", "halyk")) invalid("paymentMethod is unsupported")
        if (request.paymentStatus !in setOf("pending", "paid", "demo_no_charge")) invalid("paymentStatus is unsupported")
        if (request.demo != (request.paymentStatus == "demo_no_charge")) {
            invalid("demo and paymentStatus are inconsistent")
        }
        request.lines.forEachIndexed { index, line ->
            bounded(line.productId, "lines[$index].productId", 256)
            if (line.sku.length > 256) invalid("lines[$index].sku is too long")
            bounded(line.title, "lines[$index].title", 500)
            if (line.quantity <= 0) invalid("lines[$index].quantity must be positive")
            line.unitPrice?.let { unitPrice ->
                if (unitPrice < BigDecimal.ZERO || unitPrice.scale() > 2) {
                    invalid("lines[$index].unitPrice must be non-negative with at most 2 decimals")
                }
            }
        }
    }

    private fun parseStatus(value: String): FulfillmentStatus = runCatching {
        FulfillmentStatus.valueOf(value.trim().lowercase())
    }.getOrElse { invalid("Unsupported fulfillment status") }

    private fun bounded(value: String, field: String, max: Int): String {
        val normalized = value.trim()
        if (normalized.isEmpty() || normalized.length > max) invalid("$field must contain 1..$max characters")
        return normalized
    }

    private fun audit(orderId: String?, actorType: String, actorId: String, action: String, details: Map<String, Any?>) {
        jdbc.update(
            """
            INSERT INTO fulfillment_audit (order_id, actor_type, actor_id, action, details)
            VALUES (?, ?, ?, ?, ?::jsonb)
            """.trimIndent(),
            orderId,
            actorType,
            actorId.take(256),
            action,
            objectMapper.writeValueAsString(details),
        )
    }

    private fun ensureEnabled() {
        if (!enabled) throw AppException(
            ErrorCode.UPSTREAM_UNAVAILABLE,
            "Order fulfillment is disabled",
            HttpStatus.SERVICE_UNAVAILABLE,
        )
    }

    private fun invalid(message: String): Nothing =
        throw AppException(ErrorCode.VALIDATION_FAILED, message, HttpStatus.BAD_REQUEST)

    private fun conflict(message: String): Nothing =
        throw AppException(ErrorCode.CONFLICT, message, HttpStatus.CONFLICT)

    private fun notFound(message: String): Nothing =
        throw AppException(ErrorCode.NOT_FOUND, message, HttpStatus.NOT_FOUND)

    private fun forbidden(message: String): Nothing =
        throw AppException(ErrorCode.FORBIDDEN, message, HttpStatus.FORBIDDEN)

    private fun unauthorized(message: String): Nothing =
        throw AppException(ErrorCode.UNAUTHORIZED, message, HttpStatus.UNAUTHORIZED)

    private data class ActionResult(
        val order: FulfillmentOrderDto? = null,
        val error: AppException? = null,
    )
}
