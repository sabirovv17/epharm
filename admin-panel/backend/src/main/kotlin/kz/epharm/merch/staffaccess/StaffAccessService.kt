package kz.epharm.merch.staffaccess

import kz.epharm.auth.access.AdministrativeScope
import kz.epharm.auth.access.UnifiedAdminCatalog
import kz.epharm.auth.access.UnifiedAuthorityLock
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Service
class StaffAccessService(
    private val jdbc: JdbcTemplate,
    private val access: AdministrativeScope,
    private val tokens: StaffAccessTokens,
    @Value("\${app.merch.staff-access-enabled:false}") private val enabled: Boolean,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val bcrypt = BCryptPasswordEncoder(10)
    private val view = UnifiedAdminCatalog.key("merchandising", "staff_access", "view")
    private val create = UnifiedAdminCatalog.key("merchandising", "staff_access", "create")
    private val revoke = UnifiedAdminCatalog.key("merchandising", "staff_access", "revoke")
    private data class Code(
        val id: UUID, val organization: UUID, val month: LocalDate, val hash: String,
        val lookup: String?, val qr: String?, val allBranches: Boolean, val deadline: Instant?,
        val status: String, val maxUses: Int, val uses: Int, val qrUses: Long,
        val created: Instant, val lastUsed: Instant?, val revoked: Instant?, val manualRevoked: Instant?, val note: String?,
    ) {
        override fun toString() = "StaffAccessCode(id=$id, credentials=redacted)"
    }
    private data class Session(val id: UUID, val code: UUID, val expires: Instant, val revoked: Instant?)
    private data class Branch(val id: UUID, val organization: UUID, val organizationName: String,
                              val code: String, val name: String, val city: String, val address: String)

    @Transactional(noRollbackFor = [AppException::class])
    fun exchangeCode(request: StaffCodeRequest, clientAddress: String): StaffAccessLogin =
        exchange("code", request.code, clientAddress)

    @Transactional(noRollbackFor = [AppException::class])
    fun exchangeQr(request: StaffQrRequest, clientAddress: String): StaffAccessLogin =
        exchange("qr", request.access, clientAddress)

    private fun exchange(kind: String, raw: String?, clientAddress: String): StaffAccessLogin {
        gate()
        // Only the login path takes this budget lock; no authority/domain lock is held yet.
        jdbc.queryForList("SELECT pg_advisory_xact_lock(817120,1)")
        val now = clock.instant()
        jdbc.update("""DELETE FROM merch_staff_access_attempts WHERE id IN
            (SELECT id FROM merch_staff_access_attempts WHERE source_id IS NULL AND created_at<? ORDER BY created_at LIMIT 1000)""",
            Timestamp.from(now.minusSeconds(86400)))
        val ip = tokens.ipHash(clientAddress)
        val minute = Timestamp.from(now.minusSeconds(60))
        if (count("SELECT count(*) FROM merch_staff_access_attempts WHERE created_at>=?", minute) >= 120 ||
            count("""SELECT count(*) FROM merch_staff_access_attempts WHERE access_type=? AND ip_hash=?
                AND NOT success AND created_at>=?""", kind, ip, Timestamp.from(now.minusSeconds(1800))) >= 3) limited()
        val value = if (kind == "code") StaffAccessTokens.normalizeCode(raw) else StaffAccessTokens.normalizeToken(raw)
        val lookup = value?.let { if (kind == "code") tokens.codeLookup(it) else StaffAccessTokens.qrHash(it) }
            ?: tokens.invalidLookup(kind, raw.orEmpty())
        val attempt = UUID.randomUUID()
        if (value == null) {
            recordAttempt(attempt, kind, lookup, ip, now, 0, "invalid_format")
            invalid()
        }
        val candidates = if (kind == "code") {
            val legacy = eligible("c.code_lookup_hash IS NULL", emptyArray(), now, 17)
            if (legacy.size > 16) {
                recordAttempt(attempt, kind, lookup, ip, now, 0, "legacy_capacity")
                limited()
            }
            eligible("c.code_lookup_hash=?", arrayOf(lookup), now, 2) + legacy
        } else eligible("c.qr_token_hash=?", arrayOf(lookup), now, 2)
        val cost = if (kind == "code") candidates.size.coerceAtLeast(1) else 0
        if (cost > 17 || count("SELECT coalesce(sum(bcrypt_cost),0) FROM merch_staff_access_attempts WHERE created_at>=?", minute) + cost > 64) limited()
        recordAttempt(attempt, kind, lookup, ip, now, cost, "not_found_or_expired")
        val matches = if (kind == "qr") candidates else candidates.filter { matches(value, it.hash) }
        if (kind == "code" && candidates.isEmpty()) bcrypt.matches(value, DUMMY_HASH)
        if (matches.size != 1) {
            if (matches.size > 1) jdbc.update("UPDATE merch_staff_access_attempts SET failure_reason='ambiguous' WHERE id=?", attempt)
            invalid()
        }
        val candidate = matches.single()
        UnifiedAuthorityLock.acquire(jdbc)
        val code = loadCode(candidate.id, true) ?: invalid()
        val issuedAt = clock.instant()
        if (!isOpen(code, issuedAt) || code.hash != candidate.hash || code.qr != candidate.qr ||
            code.lookup != candidate.lookup || kind == "code" && code.uses >= code.maxUses) invalid()
        val branches = branchIds(code, activeOnly = true)
        if (branches.isEmpty() || !activeOrganization(code.organization)) invalid()
        if (kind == "code") jdbc.update("""UPDATE merch_staff_access_codes SET use_count=use_count+1,last_used_at=?,updated_at=?,
            status=CASE WHEN use_count+1>=max_uses THEN 'revoked' ELSE status END,
            revoked_at=CASE WHEN use_count+1>=max_uses THEN coalesce(revoked_at,?) ELSE revoked_at END WHERE id=?""",
            Timestamp.from(issuedAt), Timestamp.from(issuedAt), Timestamp.from(issuedAt), code.id)
        else jdbc.update("""UPDATE merch_staff_access_codes SET qr_use_count=qr_use_count+1,last_used_at=?,qr_last_used_at=?,updated_at=? WHERE id=?""",
            Timestamp.from(issuedAt), Timestamp.from(issuedAt), Timestamp.from(issuedAt), code.id)
        val sessionId = UUID.randomUUID()
        val token = tokens.token()
        val expires = issuedAt.plusSeconds(43200)
        jdbc.update("""INSERT INTO merch_staff_access_sessions(id,code_id,token_hash,created_at,expires_at)
            VALUES(?,?,?,?,?)""", sessionId, code.id, StaffAccessTokens.sessionHash(token), Timestamp.from(issuedAt), Timestamp.from(expires))
        jdbc.update("UPDATE merch_staff_access_attempts SET success=true,code_id=?,failure_reason=NULL WHERE id=?", code.id, attempt)
        return StaffAccessLogin(token, expires, context(code, Session(sessionId, code.id, expires, null), branches, issuedAt))
    }

    /** Fresh realm/scope proof. A mutation joins its caller's write TX before any task/dispatch locks. */
    @Transactional(readOnly = true)
    fun requireSession(rawToken: String, mutation: Boolean = false): StaffAccessContext {
        gate()
        val raw = StaffAccessTokens.normalizeToken(rawToken) ?: unauthorized()
        if (mutation) {
            check(TransactionSynchronizationManager.isActualTransactionActive() &&
                !TransactionSynchronizationManager.isCurrentTransactionReadOnly()) { "Staff mutation proof needs the caller's write transaction" }
            UnifiedAuthorityLock.acquire(jdbc)
        }
        val hash = StaffAccessTokens.sessionHash(raw)
        val identity = jdbc.query("SELECT code_id FROM merch_staff_access_sessions WHERE token_hash=?",
            { rs, _ -> rs.getObject(1, UUID::class.java) }, hash).singleOrNull() ?: unauthorized()
        val code = loadCode(identity, mutation) ?: unauthorized()
        val session = session(hash, mutation) ?: unauthorized()
        val now = clock.instant()
        if (session.code != code.id || session.revoked != null || !session.expires.isAfter(now) ||
            code.manualRevoked != null || !activeOrganization(code.organization)) unauthorized()
        val branches = branchIds(code, activeOnly = true)
        if (branches.isEmpty()) unauthorized()
        val result = context(code, session, branches, now)
        if (mutation && result.readOnly) throw AppException(ErrorCode.FORBIDDEN,
            "Срок сдачи истёк: доступно только чтение", HttpStatus.FORBIDDEN)
        return result
    }

    @Transactional
    fun logout(rawToken: String) {
        UnifiedAuthorityLock.acquire(jdbc)
        val context = requireSession(rawToken)
        loadCode(context.codeId, true) ?: unauthorized()
        session(StaffAccessTokens.sessionHash(rawToken.trim()), true) ?: unauthorized()
        jdbc.update("UPDATE merch_staff_access_sessions SET revoked_at=coalesce(revoked_at,?) WHERE id=?",
            Timestamp.from(clock.instant()), context.sessionId)
    }

    @Transactional(readOnly = true)
    fun options(): StaffAccessOptions = StaffAccessOptions(visibleBranches().groupBy { it.organization }.map { (id, branches) ->
        StaffAccessOrganizationOption(id, branches.first().organizationName, branches.map(::option))
    })

    @Transactional(readOnly = true)
    fun settings(organizationId: UUID, month: LocalDate): StaffAccessSettings {
        validateMonth(month)
        val visible = visibleBranches().filter { it.organization == organizationId }
        if (visible.isEmpty()) denied()
        val codes = jdbc.query("SELECT c.* FROM merch_staff_access_codes c WHERE c.organization_id=? AND c.placement_month=? ORDER BY c.created_at DESC,c.id",
            { rs, _ -> code(rs) }, organizationId, month).filter { record ->
            val scope = branchIds(record, activeOnly = false)
            scope.isNotEmpty() && scope.all { access.canMerchBranchPermission(view, it) }
        }.map(::view)
        val branches = visible.map(::option)
        return StaffAccessSettings(organizationId, visible.first().organizationName, month, branches, codes, branches.any { it.canCreate })
    }

    @Transactional
    fun create(request: CreateStaffAccess, actorId: UUID): StaffAccessCreated {
        gate()
        access.lockCurrentActor()
        validateMonth(request.placementMonth)
        if (request.branchIds.isEmpty() || request.branchIds.size > 1000 || request.deadlineAt?.isAfter(clock.instant()) == false) validation()
        request.branchIds.forEach { access.requireMerchBranchPermission(view, it); access.requireMerchBranchPermission(create, it) }
        val valid = jdbc.query("""SELECT b.id FROM merch_branches b JOIN merch_organizations o ON o.id=b.organization_id
            WHERE b.id=ANY(?::uuid[]) AND b.organization_id=? AND b.active AND o.active ORDER BY b.id FOR SHARE OF b,o""",
            { rs, _ -> rs.getObject(1, UUID::class.java) }, uuidArray(request.branchIds), request.organizationId).toSet()
        if (valid != request.branchIds) denied()
        jdbc.queryForList("SELECT pg_advisory_xact_lock(817121,1)")
        val now = clock.instant()
        val legacy = eligible("c.code_lookup_hash IS NULL", emptyArray(), now, 17)
        if (legacy.size > 16) limited()
        val value = (1..20).asSequence().map { tokens.code() }.firstOrNull { proposed ->
            eligible("c.code_lookup_hash=?", arrayOf(tokens.codeLookup(proposed)), now, 1).isEmpty() && legacy.none { matches(proposed, it.hash) }
        } ?: throw AppException(ErrorCode.CONFLICT, "Не удалось подготовить уникальный код. Повторите создание", HttpStatus.CONFLICT)
        val qr = tokens.token()
        val id = UUID.randomUUID()
        jdbc.update("""INSERT INTO merch_staff_access_codes(id,source_system,source_origin,source_id,source_payload,organization_id,
            placement_month,code_hash,code_lookup_hash,qr_token_hash,qr_created_at,all_org_branches,deadline_at,status,max_uses,use_count,
            qr_use_count,created_at,updated_at,created_by_account_id)
            VALUES(?,'EPHARM','https://epharm.inkar.kz',?,'{}',?,?,?,?,?,?,false,?,'active',1,0,0,?,?,?)""",
            id, id, request.organizationId, request.placementMonth, bcrypt.encode(value), tokens.codeLookup(value), StaffAccessTokens.qrHash(qr),
            Timestamp.from(now), request.deadlineAt?.let(Timestamp::from), Timestamp.from(now), Timestamp.from(now), actorId)
        request.branchIds.sorted().forEach { branch -> jdbc.update("INSERT INTO merch_staff_access_code_branches(code_id,branch_id,created_at) VALUES(?,?,?)",
            id, branch, Timestamp.from(now)) }
        audit(actorId, id, "CREATE")
        return StaffAccessCreated(view(loadCode(id, false)!!), value.take(3) + "-" + value.drop(3), qr)
    }

    @Transactional
    fun revoke(codeId: UUID, actorId: UUID): StaffAccessCodeView {
        access.lockCurrentActor()
        val code = loadCode(codeId, true) ?: notFound()
        val branches = branchIds(code, activeOnly = false)
        if (branches.isEmpty()) denied()
        branches.forEach { access.requireMerchBranchPermission(view, it); access.requireMerchBranchPermission(revoke, it) }
        if (code.manualRevoked != null) return view(code)
        val now = Timestamp.from(clock.instant())
        jdbc.update("""UPDATE merch_staff_access_codes SET status='revoked',revoked_at=coalesce(revoked_at,?),
            manual_revoked_at=coalesce(manual_revoked_at,?),revoked_by_account_id=?,updated_at=? WHERE id=?""", now, now, actorId, now, codeId)
        jdbc.update("UPDATE merch_staff_access_sessions SET revoked_at=coalesce(revoked_at,?) WHERE code_id=?", now, codeId)
        audit(actorId, codeId, "REVOKE")
        return view(loadCode(codeId, false)!!)
    }

    private fun visibleBranches(): List<Branch> {
        val allowed = access.currentMerchBranchIds()
        if (allowed?.isEmpty() == true) return emptyList()
        val filter = allowed?.let(::uuidArray)
        return jdbc.query("""SELECT b.id,b.organization_id,o.name organization_name,b.code,b.name,b.city,b.address
            FROM merch_branches b JOIN merch_organizations o ON o.id=b.organization_id
            WHERE b.active AND o.active AND (?::uuid[] IS NULL OR b.id=ANY(?::uuid[])) ORDER BY o.name,b.code,b.id""",
            { rs, _ -> Branch(rs.getObject("id", UUID::class.java), rs.getObject("organization_id", UUID::class.java),
                rs.getString("organization_name"), rs.getString("code"), rs.getString("name"), rs.getString("city"), rs.getString("address")) },
            filter, filter).filter { access.canMerchBranchPermission(view, it.id) }
    }
    private fun option(branch: Branch) = StaffAccessBranchOption(branch.id, branch.code, branch.name, branch.city, branch.address,
        enabled && access.canMerchBranchPermission(create, branch.id))
    private fun branchIds(code: Code, activeOnly: Boolean): Set<UUID> = jdbc.query("""SELECT b.id FROM merch_branches b
        WHERE b.organization_id=? AND (NOT ? OR b.active) AND (? OR EXISTS
        (SELECT 1 FROM merch_staff_access_code_branches s WHERE s.code_id=? AND s.branch_id=b.id)) ORDER BY b.id""",
        { rs, _ -> rs.getObject(1, UUID::class.java) }, code.organization, activeOnly, code.allBranches, code.id).toSet()
    private fun activeOrganization(id: UUID) = jdbc.query("SELECT active FROM merch_organizations WHERE id=?",
        { rs, _ -> rs.getBoolean(1) }, id).singleOrNull() == true
    private fun eligible(predicate: String, params: Array<Any>, now: Instant, limit: Int): List<Code> = jdbc.query("""SELECT c.*
        FROM merch_staff_access_codes c JOIN merch_organizations o ON o.id=c.organization_id AND o.active
        WHERE c.status='active' AND c.revoked_at IS NULL AND c.manual_revoked_at IS NULL
        AND (c.deadline_at IS NULL OR c.deadline_at>=?) AND $predicate ORDER BY c.created_at DESC,c.id LIMIT ?""",
        { rs, _ -> code(rs) }, Timestamp.from(now), *params, limit)
    private fun loadCode(id: UUID, lock: Boolean): Code? = jdbc.query("SELECT * FROM merch_staff_access_codes WHERE id=?" + if (lock) " FOR UPDATE" else "",
        { rs, _ -> code(rs) }, id).singleOrNull()
    private fun session(hash: String, lock: Boolean): Session? = jdbc.query("SELECT * FROM merch_staff_access_sessions WHERE token_hash=?" + if (lock) " FOR UPDATE" else "",
        { rs, _ -> Session(rs.getObject("id", UUID::class.java), rs.getObject("code_id", UUID::class.java),
            rs.getTimestamp("expires_at").toInstant(), rs.getTimestamp("revoked_at")?.toInstant()) }, hash).singleOrNull()
    private fun code(rs: ResultSet) = Code(rs.getObject("id", UUID::class.java), rs.getObject("organization_id", UUID::class.java),
        rs.getObject("placement_month", LocalDate::class.java), rs.getString("code_hash"), rs.getString("code_lookup_hash"), rs.getString("qr_token_hash"),
        rs.getBoolean("all_org_branches"), rs.getTimestamp("deadline_at")?.toInstant(), rs.getString("status"), rs.getInt("max_uses"),
        rs.getInt("use_count"), rs.getLong("qr_use_count"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("last_used_at")?.toInstant(),
        rs.getTimestamp("revoked_at")?.toInstant(), rs.getTimestamp("manual_revoked_at")?.toInstant(), rs.getString("note"))
    private fun isOpen(code: Code, now: Instant) = code.status == "active" && code.revoked == null && code.manualRevoked == null && code.deadline?.isBefore(now) != true
    private fun context(code: Code, session: Session, branches: Set<UUID>, now: Instant) = StaffAccessContext(session.id, code.id,
        code.organization, code.month, branches, code.deadline, session.expires, code.deadline?.isBefore(now) == true)
    private fun view(code: Code): StaffAccessCodeView {
        val branches = branchIds(code, activeOnly = false)
        return StaffAccessCodeView(code.id, code.organization, code.month, branches, code.allBranches, code.deadline, code.status, code.maxUses,
            code.uses, code.qrUses, code.created, code.lastUsed, code.qr != null, code.manualRevoked != null, code.uses >= code.maxUses,
            code.deadline?.isBefore(clock.instant()) == true, code.manualRevoked == null && branches.isNotEmpty() && branches.all { access.canMerchBranchPermission(revoke, it) }, code.note)
    }
    private fun recordAttempt(id: UUID, kind: String, lookup: String, ip: String, now: Instant, cost: Int, reason: String) {
        jdbc.update("""INSERT INTO merch_staff_access_attempts(id,access_type,lookup_hash,ip_hash,success,bcrypt_cost,created_at,failure_reason)
            VALUES(?,?,?,?,false,?,?,?)""", id, kind, lookup, ip, cost, Timestamp.from(now), reason)
    }
    private fun matches(raw: String, hash: String) = runCatching { bcrypt.matches(raw, hash) }.getOrDefault(false)
    private fun audit(actor: UUID, id: UUID, operation: String) { jdbc.update("""INSERT INTO unified_identity_audit(id,actor_id,entity_type,entity_id,operation)
        VALUES(?,?,'MERCH_STAFF_ACCESS_CODE',?,?)""", UUID.randomUUID(), actor, id.toString(), operation) }
    private fun count(sql: String, vararg args: Any) = jdbc.queryForObject(sql, Long::class.java, *args) ?: 0L
    private fun uuidArray(ids: Set<UUID>) = ids.sorted().joinToString(",", "{", "}")
    private fun validateMonth(month: LocalDate) { if (month.dayOfMonth != 1) validation() }
    private fun gate() { if (!enabled) denied() }
    private fun invalid(): Nothing = throw AppException(ErrorCode.INVALID_CREDENTIALS, "Неверный, недоступный или истёкший код доступа", HttpStatus.UNAUTHORIZED)
    private fun unauthorized(): Nothing = throw AppException(ErrorCode.UNAUTHORIZED, "Сессия доступа завершена", HttpStatus.UNAUTHORIZED)
    private fun denied(): Nothing = throw AppException(ErrorCode.FORBIDDEN, "Нет доступа к этому действию", HttpStatus.FORBIDDEN)
    private fun notFound(): Nothing = throw AppException(ErrorCode.NOT_FOUND, "Код доступа не найден", HttpStatus.NOT_FOUND)
    private fun validation(): Nothing = throw AppException(ErrorCode.VALIDATION_FAILED, "Проверьте месяц, аптеки и срок доступа")
    private fun limited(): Nothing = throw AppException(ErrorCode.VALIDATION_FAILED, "Слишком много попыток входа. Повторите позже", HttpStatus.TOO_MANY_REQUESTS)
    companion object {
        // Fixed synthetic bcrypt10 hash only equalizes an empty native lookup; it is not an account credential.
        private const val DUMMY_HASH = "\$2a\$10\$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy"
    }
}
