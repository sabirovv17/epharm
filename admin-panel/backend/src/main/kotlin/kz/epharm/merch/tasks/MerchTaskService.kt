package kz.epharm.merch.tasks

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.ByteArrayInputStream
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.imageio.ImageIO
import kz.epharm.auth.access.AdministrativeScope
import kz.epharm.auth.policy.PermissionKey
import kz.epharm.finance.service.PharmacistWalletService
import kz.epharm.finance.service.WalletCreditSource
import kz.epharm.merch.delivery.NativeCapabilityAuthorization
import kz.epharm.merch.delivery.AuthorizedTaskCapability
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile

object MerchTaskPermissions {
    val view = key("view"); val create = key("create"); val execute = key("execute")
    val review = key("review"); val financial = key("financial_data")
    private fun key(action: String) = requireNotNull(PermissionKey.parse("merchandising", "tasks", action))
}

/** Native writes only. Importers preserve snapshots without calling any method in this service. */
@Service
class MerchTaskService(private val jdbc: JdbcTemplate, private val json: ObjectMapper,
    private val access: AdministrativeScope, private val wallet: PharmacistWalletService,
    private val storage: TaskEvidenceStorage, private val capabilities: NativeCapabilityAuthorization) {

    @Transactional
    fun create(req: CreateMerchTaskDispatch, actor: UUID): MerchTaskDispatchDto {
        access.lockCurrentActor()
        access.requireMerchBranchPermission(MerchTaskPermissions.create, req.branchId)
        val creatorPerson = access.currentVerifiedPersonId()
        validateCreate(req)
        val branch = jdbc.query("SELECT active FROM merch_branches WHERE id=?", { rs, _ -> rs.getBoolean(1) }, req.branchId).singleOrNull()
        if (branch != true) invalid("Нужна активная аптека мерча")
        if (req.rewardAmountKzt > 0) access.requireMerchBranchPermission(MerchTaskPermissions.financial, req.branchId)
        req.assignedPharmacistId?.let {
            if (jdbc.queryForObject("""SELECT count(*) FROM pharmacists p JOIN merch_branches b
                ON b.epharm_pharmacy_id=p.pharmacy_id AND b.pharmacy_binding_verified
                WHERE p.id=? AND p.status='active' AND b.id=?""", Long::class.java, it, req.branchId) != 1L) invalid("Исполнитель не подтверждён в этой аптеке")
        }
        val id = UUID.randomUUID(); val now = Instant.now()
        jdbc.update("""INSERT INTO merch_task_dispatches(id,source_system,source_origin,source_id,source_payload,branch_id,title,
            status,due_at,reward_amount_kzt,assigned_pharmacist_id,created_by,created_at,updated_at,priority,available_from)
            VALUES (?,'EPHARM','https://epharm.inkar.kz',?,?::jsonb,?,?,'ready',?,?,?,?,?,?,?,?)""",
            id, id, json.writeValueAsString(req), req.branchId, req.title.trim(), Timestamp.from(req.dueAt), req.rewardAmountKzt,
            req.assignedPharmacistId, actor, Timestamp.from(now), Timestamp.from(now), req.priority, req.availableFrom?.let(Timestamp::from))
        for (item in req.items) {
            val itemId = UUID.randomUUID()
            val checklist = item.checklist.map { TaskChecklistItem(UUID.randomUUID(), it.trim()) }
            jdbc.update("""INSERT INTO merch_task_items(id,dispatch_id,source_id,source_payload,title,instructions,task_type,status,
                required,required_photo_count,checklist,created_at,updated_at,available_from,due_at)
                VALUES (?,?,?,'{}',?,?,'photo_report','assigned',?,?,?::jsonb,?,?,?,?)""", itemId, id, itemId, item.title.trim(),
                item.instructions, item.required, item.requiredPhotos, json.writeValueAsString(checklist), Timestamp.from(now), Timestamp.from(now),
                req.availableFrom?.let(Timestamp::from), Timestamp.from(req.dueAt))
        }
        event(id, null, actor, creatorPerson, "created", mapOf("itemCount" to req.items.size))
        return dto(dispatch(id), true)
    }

    @Transactional(readOnly = true)
    fun list(limit: Int, offset: Int): MerchTaskDispatchPage {
        access.requireExplicitPermission(MerchTaskPermissions.view)
        page(limit, offset)
        val scope = access.currentMerchBranchIds()
        val args = mutableListOf<Any>()
        val where = if (scope == null) "" else {
            if (scope.isEmpty()) forbidden()
            args.addAll(scope); " WHERE d.branch_id IN (${scope.joinToString(",") { "?" }})"
        }
        args += limit + 1; args += offset
        val rows = jdbc.query("SELECT d.id FROM merch_task_dispatches d$where ORDER BY d.created_at DESC,d.id LIMIT ? OFFSET ?",
            { rs, _ -> rs.getObject(1, UUID::class.java) }, *args.toTypedArray())
        return MerchTaskDispatchPage(rows.take(limit).map { id ->
            val d = dispatch(id); dto(d, access.canMerchBranchPermission(MerchTaskPermissions.financial, d.branchId), details = false)
        }, limit, offset, rows.size > limit)
    }

    @Transactional(readOnly = true)
    fun get(id: UUID): MerchTaskDispatchDto {
        val d = dispatch(id)
        access.requireMerchBranchPermission(MerchTaskPermissions.view, d.branchId)
        return dto(d, access.canMerchBranchPermission(MerchTaskPermissions.financial, d.branchId))
    }

    @Transactional(readOnly = true)
    fun getPharmacist(id: UUID, pharmacistId: String): MerchTaskDispatchDto {
        val d = dispatch(id)
        return dto(d, true, mobileExecutor = pharmacistExecutor(d, pharmacistId))
    }

    @Transactional(readOnly = true)
    fun branches(): List<MerchTaskBranchDto> {
        access.requireExplicitPermission(MerchTaskPermissions.view)
        val scope = access.currentMerchBranchIds()
        if (scope?.isEmpty() == true) forbidden()
        val args = scope?.toTypedArray() ?: emptyArray()
        val where = if (scope == null) "" else " WHERE id IN (${scope.joinToString(",") { "?" }})"
        val person = currentAdminPerson()
        return jdbc.query("SELECT id,name,city,address,active FROM merch_branches$where ORDER BY id LIMIT 50001", { rs, _ ->
            val id=rs.uuid("id");val active=rs.getBoolean("active")
            val create=active && person!=null && access.canMerchBranchPermission(MerchTaskPermissions.create,id)
            MerchTaskBranchDto(id,rs.getString("name"),rs.getString("city"),rs.getString("address"),active,
                create,create && access.canMerchBranchPermission(MerchTaskPermissions.financial,id))
        },*args).also { if(it.size>50_000) throw AppException(ErrorCode.VALIDATION_FAILED,
            "Список аптек слишком велик: требуется уточнить область доступа",HttpStatus.PAYLOAD_TOO_LARGE) }
    }

    @Transactional(readOnly = true)
    fun getCapability(rawToken: String): MerchTaskDispatchDto {
        val cap=freeReadCapability(rawToken)
        return capabilityDto(dispatch(cap.dispatchId),cap)
    }

    @Transactional
    fun startCapability(itemId: UUID, rawToken: String): MerchTaskAttemptDto {
        val cap=capabilities.requireFreeMutation(rawToken)
        val (d,item)=capabilityLocked(itemId,cap)
        return publicAttempt(start(d,item,TaskExecutor(null,null,null,null,cap.linkId)))
    }

    @Transactional
    fun uploadCapability(itemId: UUID, uploadId: UUID, file: MultipartFile, rawToken: String): MerchTaskEvidenceDto {
        val cap=capabilities.requireFreeMutation(rawToken)
        val (d,item)=capabilityLocked(itemId,cap)
        return upload(d,item,null,uploadId,file,capabilityLinkId=cap.linkId)
    }

    @Transactional
    fun submitCapability(itemId: UUID, request: SubmitMerchTaskAttempt, rawToken: String): MerchTaskAttemptDto {
        val cap=capabilities.requireFreeMutation(rawToken)
        val (d,item)=capabilityLocked(itemId,cap)
        return publicAttempt(submit(d,item,null,null,request,cap.linkId))
    }

    @Transactional(readOnly = true)
    fun downloadCapability(evidenceId: UUID, rawToken: String): Pair<MerchTaskEvidenceDto,ByteArray> {
        val cap=freeReadCapability(rawToken)
        val location=jdbc.query("""SELECT i.dispatch_id,e.storage_backend,a.capability_link_id FROM merch_task_evidence e
            JOIN merch_task_items i ON i.id=e.item_id LEFT JOIN merch_task_attempts a ON a.id=e.attempt_id WHERE e.id=?""",
            {rs,_->Triple(rs.uuid("dispatch_id"),rs.getString("storage_backend"),rs.getObject("capability_link_id",UUID::class.java))},evidenceId).singleOrNull()?:notFound()
        if(location.first!=cap.dispatchId || location.second=="private_host" && location.third!=cap.linkId) notFound()
        return download(evidenceId)
    }

    @Transactional
    fun startAdmin(itemId: UUID, actor: UUID): MerchTaskAttemptDto {
        access.lockCurrentActor()
        val pair = locked(itemId)
        access.requireMerchBranchPermission(MerchTaskPermissions.execute, pair.first.branchId)
        val person = access.currentVerifiedPersonId()
        return start(pair.first, pair.second, TaskExecutor(person, actor, null, null))
    }

    @Transactional
    fun startPharmacist(itemId: UUID, pharmacistId: String): MerchTaskAttemptDto {
        val pair = locked(itemId)
        return start(pair.first, pair.second, pharmacistExecutor(pair.first, pharmacistId,lockPharmacist=true))
    }

    private fun start(d: TaskDispatchRow, item: TaskItemRow, executor: TaskExecutor): MerchTaskAttemptDto {
        writable(d)
        available(item)
        if (item.status == "in_progress") {
            val existing = currentAttempt(item)
            requireExecutor(existing.id, executor.personId, executor.capabilityLinkId)
            return existing
        }
        if (item.status !in setOf("assigned", "accepted", "revision_required")) conflict("Пункт недоступен для новой попытки")
        val id = UUID.randomUUID(); val now = Instant.now()
        jdbc.update("""INSERT INTO merch_task_attempts(id,item_id,attempt_no,executor_person_id,executor_account_id,pharmacist_id,
            binding_id,capability_link_id,status,created_at) VALUES (?,?,?,?,?,?,?,?,'draft',?)""", id, item.id, item.attempt + 1, executor.personId,
            executor.accountId, executor.pharmacistId, executor.bindingId, executor.capabilityLinkId, Timestamp.from(now))
        jdbc.update("UPDATE merch_task_items SET current_attempt=current_attempt+1,status='in_progress',version=version+1,updated_at=now() WHERE id=?", item.id)
        jdbc.update("UPDATE merch_task_dispatches SET status='in_progress',version=version+1,updated_at=now() WHERE id=?", d.id)
        event(d.id, item.id, executor.accountId, executor.personId, "attempt_started", mapOf("attemptId" to id) +
            if(executor.capabilityLinkId==null) emptyMap() else mapOf("capabilityLinkId" to executor.capabilityLinkId))
        return attempt(id)
    }

    @Transactional
    fun uploadAdmin(itemId: UUID, clientUploadId: UUID, file: MultipartFile, actor: UUID): MerchTaskEvidenceDto {
        access.lockCurrentActor()
        val pair = locked(itemId)
        access.requireMerchBranchPermission(MerchTaskPermissions.execute, pair.first.branchId)
        return upload(pair.first, pair.second, access.currentVerifiedPersonId(), clientUploadId, file, actor)
    }
    @Transactional
    fun uploadPharmacist(itemId: UUID, clientUploadId: UUID, file: MultipartFile, pharmacistId: String): MerchTaskEvidenceDto {
        val pair = locked(itemId)
        return upload(pair.first, pair.second, pharmacistExecutor(pair.first, pharmacistId,lockPharmacist=true).personId, clientUploadId, file)
    }
    private fun upload(d: TaskDispatchRow, item: TaskItemRow, person: UUID?, uploadId: UUID, file: MultipartFile, actor: UUID? = null,
        capabilityLinkId: UUID? = null): MerchTaskEvidenceDto {
        return TaskPhotoGate.run { uploadAcquired(d,item,person,uploadId,file,actor,capabilityLinkId) }
    }
    private fun uploadAcquired(d: TaskDispatchRow, item: TaskItemRow, person: UUID?, uploadId: UUID, file: MultipartFile, actor: UUID?, capabilityLinkId: UUID?): MerchTaskEvidenceDto {
        writable(d)
        available(item)
        if (item.status != "in_progress") conflict("Сначала начните попытку")
        val attempt = currentAttempt(item)
        requireExecutor(attempt.id, person, capabilityLinkId)
        val bytes = imageBytes(file)
        val hash = TaskEvidenceStorage.hash(bytes)
        val prior = jdbc.query("SELECT id,sha256 FROM merch_task_evidence WHERE attempt_id=? AND client_upload_id=?",
            { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getString(2) }, attempt.id, uploadId).singleOrNull()
        if (prior != null) {
            if (prior.second != hash) conflict("Повторная загрузка имеет другое содержимое")
            return evidence(prior.first)
        }
        if (jdbc.queryForObject("SELECT count(*) FROM merch_task_evidence WHERE attempt_id=?", Int::class.java, attempt.id)!! >= 12) invalid("Не больше 12 фотографий в попытке")
        val id = UUID.randomUUID()
        val mime = if (bytes[0] == 0x89.toByte()) "image/png" else "image/jpeg"
        val key = "native/${d.id}/${item.id}/${attempt.id}/$id.${if (mime == "image/png") "png" else "jpg"}"
        storage.write(key, bytes)
        jdbc.update("""INSERT INTO merch_task_evidence(id,item_id,attempt_id,client_upload_id,storage_backend,object_key,mime_type,
            byte_size,sha256,original_filename,metadata,uploaded_by_person_id,created_at)
            VALUES (?,?,?,?,'private_host',?,?,?,?,?,'{"verification":"manual_review_required"}',?,now())""", id, item.id, attempt.id,
            uploadId, key, mime, bytes.size.toLong(), hash, file.originalFilename, person)
        event(d.id, item.id, actor, person, "evidence_uploaded", mapOf("evidenceId" to id, "attemptId" to attempt.id, "sha256" to hash) +
            if(capabilityLinkId==null) emptyMap() else mapOf("capabilityLinkId" to capabilityLinkId))
        return evidence(id)
    }

    @Transactional
    fun submitAdmin(itemId: UUID, req: SubmitMerchTaskAttempt, actor: UUID): MerchTaskAttemptDto {
        access.lockCurrentActor()
        val pair = locked(itemId)
        access.requireMerchBranchPermission(MerchTaskPermissions.execute, pair.first.branchId)
        return submit(pair.first, pair.second, access.currentVerifiedPersonId(), actor, req)
    }
    @Transactional
    fun submitPharmacist(itemId: UUID, req: SubmitMerchTaskAttempt, pharmacistId: String): MerchTaskAttemptDto {
        val pair = locked(itemId)
        return submit(pair.first, pair.second, pharmacistExecutor(pair.first, pharmacistId,lockPharmacist=true).personId, null, req)
    }
    private fun submit(d: TaskDispatchRow, item: TaskItemRow, person: UUID?, actor: UUID?, req: SubmitMerchTaskAttempt, capabilityLinkId: UUID? = null): MerchTaskAttemptDto {
        writable(d)
        available(item)
        if (req.comment.length > 1200 || req.expectedAttempt != item.attempt) conflict("Попытка изменилась: обновите пункт")
        val attempt = currentAttempt(item)
        requireExecutor(attempt.id, person, capabilityLinkId)
        if (attempt.status == "submitted") return attempt
        if (item.status != "in_progress" || attempt.status != "draft") conflict("Пункт нельзя отправить")
        if (req.completedChecklistIds.any { id -> item.checklist.none { it.id == id } } ||
            item.checklist.any { it.required && it.id !in req.completedChecklistIds }) invalid("Выполните обязательный чек-лист")
        if (attempt.evidence.size < item.requiredPhotos) invalid("Недостаточно фотографий в текущей попытке")
        jdbc.update("UPDATE merch_task_attempts SET status='submitted',submitted_at=now(),completed_checklist_ids=?::jsonb,employee_comment=? WHERE id=?",
            json.writeValueAsString(req.completedChecklistIds), req.comment, attempt.id)
        jdbc.update("UPDATE merch_task_items SET status='submitted',version=version+1,updated_at=now() WHERE id=?", item.id)
        refresh(d.id)
        event(d.id, item.id, actor, person, "submitted", mapOf("attemptId" to attempt.id) +
            if(capabilityLinkId==null) emptyMap() else mapOf("capabilityLinkId" to capabilityLinkId))
        return attempt(attempt.id)
    }

    @Transactional
    fun review(itemId: UUID, req: ReviewMerchTaskItem, actor: UUID): MerchTaskDispatchDto {
        access.lockCurrentActor()
        val (d, item) = locked(itemId)
        val attempt = attempt(req.attemptId)
        if (attempt.id != currentAttempt(item).id) conflict("Проверяется другая попытка")
        val reviewer = access.currentVerifiedPersonId()
        val executor = attempt.executorPersonId
        val manual = executor==null && d.reward==0L && capabilityOwner(attempt.id)!=null && req.manualUnverifiedExecutor
        if(manual) access.requireManualUnverifiedTaskReview(MerchTaskPermissions.review,d.branchId)
        else {
            if(executor==null) forbidden()
            access.requireTaskReviewPermission(MerchTaskPermissions.review, d.branchId, executor)
            if (reviewer == executor) forbidden() // A manual flag never overrides verified self-review, including aliases.
        }
        if (req.decision !in setOf("approve", "revision") || req.reason.trim().length !in 3..1200) invalid("Нужны решение и причина от 3 до 1200 символов")
        val old = attempt.review
        if (old != null) {
            if (old.reviewerAccountId == actor && old.decision == req.decision && old.reason == req.reason.trim())
                return dto(d, access.canMerchBranchPermission(MerchTaskPermissions.financial, d.branchId))
            conflict("Попытка уже проверена")
        }
        if (item.version != req.expectedItemVersion || item.status != "submitted" || attempt.status != "submitted") conflict("Пункт уже изменён")
        jdbc.update("""INSERT INTO merch_task_reviews(id,item_id,attempt_id,reviewer_account_id,reviewer_person_id,decision,reason,created_at)
            VALUES (?,?,?,?,?,?,?,now())""", UUID.randomUUID(), itemId, attempt.id, actor, reviewer, req.decision, req.reason.trim())
        val approved = req.decision == "approve"
        jdbc.update("UPDATE merch_task_attempts SET status=? WHERE id=?", if (approved) "approved" else "revision_required", attempt.id)
        jdbc.update("UPDATE merch_task_items SET status=?,version=version+1,updated_at=now() WHERE id=?", if (approved) "completed" else "revision_required", itemId)
        refresh(d.id)
        event(d.id, itemId, actor, reviewer, req.decision, mapOf("attemptId" to attempt.id, "reason" to req.reason.trim(),
            "reviewMode" to if(manual) "manual_unverified_executor" else "independent_verified_person",
            "executorIdentityStatus" to attempt.executorIdentityStatus))
        creditIfComplete(dispatch(d.id))
        return dto(dispatch(d.id), access.canMerchBranchPermission(MerchTaskPermissions.financial, d.branchId))
    }

    private fun creditIfComplete(d: TaskDispatchRow) {
        if (jdbc.queryForObject("SELECT count(*) FROM merch_task_items WHERE dispatch_id=? AND required AND status<>'completed'", Int::class.java, d.id)!! > 0 ||
            jdbc.queryForObject("SELECT count(*) FROM merch_task_items WHERE dispatch_id=? AND required", Int::class.java, d.id) == 0 ||
            jdbc.queryForObject("SELECT count(*) FROM merch_task_rewards WHERE dispatch_id=?", Int::class.java, d.id)!! > 0) return
        if (d.historicalReward) { rewardState(d, "held", "historical_approval_or_outbox_requires_HQ"); return }
        if (d.reward == 0L) { rewardState(d, "not_monetary", "zero_reward"); return }
        val due = d.dueAt ?: run { rewardState(d, "held", "historical_due_at_not_recorded"); return }
        val attempts = jdbc.query("""SELECT a.* FROM merch_task_attempts a JOIN merch_task_items i ON i.id=a.item_id
            WHERE i.dispatch_id=? AND i.required AND a.attempt_no=i.current_attempt""", { rs, _ ->
            arrayOf<Any?>(rs.getString("pharmacist_id"), rs.getObject("executor_person_id", UUID::class.java),
                rs.getObject("binding_id", UUID::class.java), rs.getTimestamp("submitted_at")?.toInstant())
        }, d.id)
        val identities = attempts.map { Triple(it[0] as String?, it[1] as UUID?, it[2] as UUID?) }.distinct()
        val identity = identities.singleOrNull()
        if (attempts.isEmpty() || identity?.first == null || identity.second == null || identity.third == null ||
            attempts.any { (it[3] as Instant?)?.isAfter(due) != false }) { rewardState(d, "held", "executor_or_timeliness_requires_HQ"); return }
        val verified = try { pharmacistExecutor(d, identity.first!!, lockPharmacist = true) } catch (_: AppException) { null }
        if (verified == null || verified.personId != identity.second || verified.bindingId != identity.third) {
            rewardState(d, "held", "executor_moved_inactive_or_binding_changed"); return
        }
        val existing = jdbc.query("SELECT id,pharmacist_id,amount_kzt FROM pharmacist_wallet_entries WHERE source='merch_task' AND source_id=?",
            { rs, _ -> Triple(rs.uuid("id"),rs.getString("pharmacist_id"),rs.getLong("amount_kzt")) }, d.id.toString()).singleOrNull()
        if (existing != null) {
            if (existing.second != identity.first || existing.third != d.reward) {
                rewardState(d, "held", "existing_ledger_identity_or_amount_conflicts"); return
            }
            jdbc.update("""INSERT INTO merch_task_rewards(dispatch_id,pharmacist_id,executor_person_id,historical_pharmacy_id,
                amount_kzt,state,reason,wallet_entry_id,created_at) VALUES (?,?,?,?,?,'already_credited','existing_wallet_posting_verified',?,now())""",
                d.id,identity.first,identity.second,d.pharmacyId,d.reward,existing.first)
            return
        }
        val credit = wallet.credit(WalletCreditSource.MERCH_TASK, d.id.toString(), identity.first!!, d.reward, "native-merch-dispatch:${d.id}")
        jdbc.update("""INSERT INTO merch_task_rewards(dispatch_id,pharmacist_id,executor_person_id,historical_pharmacy_id,
            amount_kzt,state,reason,wallet_entry_id,created_at) VALUES (?,?,?,?,?,'credited','all_required_items_approved',?,now())""",
            d.id, identity.first, identity.second, d.pharmacyId, d.reward, credit.entryId)
    }

    private fun rewardState(d: TaskDispatchRow, state: String, reason: String) {
        jdbc.update("INSERT INTO merch_task_rewards(dispatch_id,amount_kzt,state,reason,created_at) VALUES (?,?,?,?,now())", d.id, d.reward, state, reason)
    }

    @Transactional(readOnly = true)
    fun history(itemId: UUID, limit: Int, offset: Int): MerchTaskAttemptPage {
        page(limit, offset)
        val item = item(itemId)
        access.requireMerchBranchPermission(MerchTaskPermissions.view, dispatch(item.dispatchId).branchId)
        val ids = jdbc.query("SELECT id FROM merch_task_attempts WHERE item_id=? ORDER BY attempt_no DESC LIMIT ? OFFSET ?",
            { rs, _ -> rs.getObject(1, UUID::class.java) }, itemId, limit + 1, offset)
        return MerchTaskAttemptPage(ids.take(limit).map(::attempt), limit, offset, ids.size > limit)
    }

    @Transactional(readOnly = true)
    fun downloadAdmin(evidenceId: UUID): Pair<MerchTaskEvidenceDto, ByteArray> {
        val itemId = jdbc.query("SELECT item_id FROM merch_task_evidence WHERE id=?", { rs, _ -> rs.getObject(1, UUID::class.java) }, evidenceId).singleOrNull() ?: notFound()
        access.requireMerchBranchPermission(MerchTaskPermissions.view, dispatch(item(itemId).dispatchId).branchId)
        return download(evidenceId)
    }
    @Transactional(readOnly = true)
    fun downloadPharmacist(evidenceId: UUID, pharmacistId: String): Pair<MerchTaskEvidenceDto, ByteArray> {
        val itemId = jdbc.query("SELECT item_id FROM merch_task_evidence WHERE id=?", { rs, _ -> rs.getObject(1, UUID::class.java) }, evidenceId).singleOrNull() ?: notFound()
        pharmacistExecutor(dispatch(item(itemId).dispatchId), pharmacistId)
        return download(evidenceId)
    }
    private fun download(id: UUID): Pair<MerchTaskEvidenceDto, ByteArray> {
        val location = jdbc.query("SELECT storage_backend,object_key,sha256 FROM merch_task_evidence WHERE id=?",
            { rs, _ -> Triple(rs.getString(1), rs.getString(2), rs.getString(3)) }, id).singleOrNull() ?: notFound()
        val metadata = evidence(id)
        val bytes = storage.read(location.first, location.second, location.third)
        if (bytes.size.toLong()!=metadata.byteSize) conflict("Размер доказательства не соответствует исходной записи; файл сохранён")
        return metadata to bytes
    }

    private fun pharmacistExecutor(d: TaskDispatchRow, pharmacistId: String, lockPharmacist: Boolean = false): TaskExecutor {
        if (!d.pharmacyBindingVerified || d.pharmacyId.isNullOrBlank() || !d.branchActive ||
            d.assignedPharmacist != null && d.assignedPharmacist != pharmacistId) forbidden()
        val person = jdbc.query("""SELECT l.person_id FROM pharmacist_person_links l
            JOIN unified_persons u ON u.id=l.person_id AND length(btrim(u.verification_reference))>0
            JOIN pharmacists p ON p.id=l.pharmacist_id AND p.status='active' AND p.pharmacy_id=?
            WHERE l.pharmacist_id=? AND length(btrim(l.verification_reference))>0 ${if (lockPharmacist) "FOR UPDATE OF p" else ""}""",
            { rs, _ -> rs.getObject(1, UUID::class.java) }, d.pharmacyId, pharmacistId).singleOrNull() ?: forbidden()
        val binding = if (d.dueAt == null) null else jdbc.query("""SELECT id FROM merch_executor_bindings WHERE dispatch_id=? AND pharmacist_id=?
            AND pharmacy_id=? AND reward_kzt=? AND task_due_at=?""", { rs, _ -> rs.getObject(1, UUID::class.java) },
            d.id, pharmacistId, d.pharmacyId, d.reward, Timestamp.from(d.dueAt)).singleOrNull()
        if (d.reward > 0 && binding == null) forbidden()
        return TaskExecutor(person, null, pharmacistId, binding)
    }

    private fun locked(itemId: UUID): Pair<TaskDispatchRow, TaskItemRow> {
        val parent = jdbc.query("SELECT dispatch_id FROM merch_task_items WHERE id=?", { rs, _ -> rs.getObject(1, UUID::class.java) }, itemId).singleOrNull() ?: notFound()
        // Same lock as the still-compatible signed legacy reward callback, before task/pharmacist rows.
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 817105))",parent.toString())
        return dispatch(parent, true) to item(itemId, true)
    }
    private fun capabilityLocked(itemId: UUID, cap: AuthorizedTaskCapability): Pair<TaskDispatchRow,TaskItemRow> {
        // Check membership before acquiring a second dispatch lock: a forged neighbouring item cannot reverse lock order.
        if(item(itemId).dispatchId!=cap.dispatchId) notFound()
        return locked(itemId).also { if(it.first.id!=cap.dispatchId || it.first.branchId!=cap.branchId) notFound() }
    }
    private fun freeReadCapability(rawToken: String): AuthorizedTaskCapability = capabilities.authorize(rawToken).also {
        if(it.rewardAmountKzt!=0L) throw AppException(ErrorCode.FORBIDDEN,"Для оплачиваемого задания подтвердите исполнителя через ePharm",HttpStatus.FORBIDDEN)
    }
    private fun capabilityOwner(attemptId:UUID):UUID? = jdbc.query("SELECT capability_link_id FROM merch_task_attempts WHERE id=?",
        {rs,_->rs.getObject(1,UUID::class.java)},attemptId).singleOrNull()
    private fun publicAttempt(value:MerchTaskAttemptDto)=value.copy(executorPersonId=null,pharmacistId=null,
        review=value.review?.copy(reviewerAccountId=null,reviewerPersonId=null))
    private fun capabilityDto(d:TaskDispatchRow,cap:AuthorizedTaskCapability):MerchTaskDispatchDto {
        val result=dto(d,true)
        val editable=!cap.readOnly
        return result.copy(reward=null,items=result.items.map { item ->
            val own=item.currentAttempt?.let { capabilityOwner(it.id)==cap.linkId }==true
            val available=editable && item.availableFrom?.isAfter(Instant.now())!=true
            val draft=available && own && item.status=="in_progress" && item.currentAttempt?.status=="draft"
            item.copy(currentAttempt=if(own) item.currentAttempt?.let(::publicAttempt) else null,
                canStart=available && item.status in setOf("assigned","accepted","revision_required"),
                canUpload=draft && item.currentAttempt!!.evidence.size<12,
                canSubmit=draft && item.currentAttempt!!.evidence.size>=item.requiredPhotos,canReview=false)
        })
    }
    private fun dispatch(id: UUID, lock: Boolean = false): TaskDispatchRow = jdbc.query("""SELECT d.*,b.epharm_pharmacy_id,
        b.pharmacy_binding_verified,b.active AS branch_active FROM merch_task_dispatches d JOIN merch_branches b ON b.id=d.branch_id
        WHERE d.id=? ${if (lock) "FOR UPDATE OF d" else ""}""", { rs, _ ->
        TaskDispatchRow(rs.uuid("id"), rs.uuid("branch_id"), rs.getString("title"), rs.getString("status"), rs.getTimestamp("due_at")?.toInstant(),
            rs.getLong("reward_amount_kzt"), rs.getString("assigned_pharmacist_id"), rs.getString("source_system"),
            rs.getBoolean("historical_reward_preserved"), rs.getLong("version"), rs.getTimestamp("available_from")?.toInstant(),
            rs.getString("epharm_pharmacy_id"), rs.getBoolean("pharmacy_binding_verified"), rs.getBoolean("branch_active"),
            rs.getString("priority"),rs.getString("task_type"))
    }, id).singleOrNull() ?: notFound()

    private fun item(id: UUID, lock: Boolean = false): TaskItemRow = jdbc.query("SELECT * FROM merch_task_items WHERE id=? ${if (lock) "FOR UPDATE" else ""}", { rs, _ ->
        TaskItemRow(rs.uuid("id"), rs.uuid("dispatch_id"), rs.getString("title"), rs.getString("instructions"), rs.getString("task_type"),
            rs.getString("status"), rs.getBoolean("required"), rs.getInt("required_photo_count"), json.readValue(rs.getString("checklist")),
            rs.getInt("current_attempt"), rs.getLong("version"),rs.getTimestamp("available_from")?.toInstant(),rs.getTimestamp("due_at")?.toInstant())
    }, id).singleOrNull() ?: notFound()

    private fun currentAttempt(item: TaskItemRow): MerchTaskAttemptDto {
        val id = jdbc.query("SELECT id FROM merch_task_attempts WHERE item_id=? AND attempt_no=?", { rs, _ -> rs.uuid("id") }, item.id, item.attempt)
            .singleOrNull() ?: conflict("Попытка не начата")
        return attempt(id)
    }
    private fun attempt(id: UUID): MerchTaskAttemptDto = jdbc.query("SELECT * FROM merch_task_attempts WHERE id=?", { rs, _ ->
        val evidence = jdbc.query("SELECT id FROM merch_task_evidence WHERE attempt_id=? ORDER BY created_at,id", { r, _ -> r.uuid("id") }, id).map(::evidence)
        val review = jdbc.query("SELECT * FROM merch_task_reviews WHERE attempt_id=?", { r, _ -> MerchTaskReviewDto(r.uuid("id"), id,
            r.uuid("reviewer_account_id"), r.uuid("reviewer_person_id"), r.getString("decision"), r.getString("reason"), r.instant("created_at")) }, id).singleOrNull()
        MerchTaskAttemptDto(id, rs.getInt("attempt_no"), rs.getString("status"), rs.getObject("executor_person_id", UUID::class.java),
            rs.getString("pharmacist_id"), rs.getTimestamp("submitted_at")?.toInstant(), rs.instant("created_at"), evidence, review,
            rs.getString("employee_comment"),json.readValue(rs.getString("completed_checklist_ids")),
            if(rs.getObject("executor_person_id")!=null) "verified_person" else if(rs.getObject("capability_link_id")!=null) "capability_unverified" else "source_unresolved")
    }, id).singleOrNull() ?: notFound()

    private fun evidence(id: UUID): MerchTaskEvidenceDto = jdbc.query("SELECT * FROM merch_task_evidence WHERE id=?", { rs, _ ->
        MerchTaskEvidenceDto(id, rs.getString("mime_type"), rs.getLong("byte_size"), rs.getString("sha256"), rs.getString("original_filename"), rs.instant("created_at"))
    }, id).singleOrNull() ?: notFound()

    private fun dto(d: TaskDispatchRow, money: Boolean, details: Boolean = true, mobileExecutor: TaskExecutor? = null): MerchTaskDispatchDto {
        val ids = jdbc.query("SELECT id FROM merch_task_items WHERE dispatch_id=? ORDER BY created_at,id", { rs, _ -> rs.uuid("id") }, d.id)
        val person = if (mobileExecutor==null) currentAdminPerson() else mobileExecutor.personId
        val execute = person!=null && (mobileExecutor!=null || access.canMerchBranchPermission(MerchTaskPermissions.execute,d.branchId))
        val now=Instant.now()
        val writable=d.branchActive && d.status !in setOf("completed","cancelled") && d.availableFrom?.isAfter(now)!=true
        val rows = if (!details) emptyList() else ids.map { id -> item(id).let {
            val attempt = if (it.attempt==0) null else currentAttempt(it)
            val available=writable && it.availableFrom?.isAfter(now)!=true
            val ownDraft=available && execute && it.status=="in_progress" && attempt?.status=="draft" && attempt.executorPersonId==person
            val review=mobileExecutor==null && person!=null && it.status=="submitted" && attempt?.status=="submitted" &&
                attempt.review==null && if(attempt.executorPersonId!=null) attempt.executorPersonId!=person && allowed {
                    access.requireTaskReviewPermission(MerchTaskPermissions.review,d.branchId,attempt.executorPersonId) }
                else d.reward==0L && capabilityOwner(attempt.id)!=null && allowed {
                    access.requireManualUnverifiedTaskReview(MerchTaskPermissions.review,d.branchId) }
            MerchTaskItemDto(it.id, it.title, it.instructions, it.type, it.status, it.required, it.requiredPhotos, it.checklist,
                it.version, attempt, jdbc.query(
                    "SELECT id FROM merch_task_evidence WHERE item_id=? AND attempt_id IS NULL ORDER BY created_at,id",
                    { rs, _ -> rs.uuid("id") }, it.id).map(::evidence),it.availableFrom,it.dueAt,
                canStart=available && execute && it.status in setOf("assigned","accepted","revision_required"),
                canUpload=ownDraft && attempt!!.evidence.size<12,canSubmit=ownDraft && attempt!!.evidence.size>=it.requiredPhotos,canReview=review)
        } }
        val reward = if (!money) null else jdbc.query("SELECT * FROM merch_task_rewards WHERE dispatch_id=?", { rs, _ ->
            MerchTaskRewardDto(rs.getString("state"), rs.getLong("amount_kzt"), rs.getString("reason"), rs.getObject("wallet_entry_id", UUID::class.java))
        }, d.id).singleOrNull()
        return MerchTaskDispatchDto(d.id, d.branchId, d.title, d.status, d.dueAt, d.source, d.version, if (money) d.reward else null,
            rows, reward, ids.size, details,d.priority,d.taskType)
    }

    private fun currentAdminPerson(): UUID? = try { access.currentVerifiedPersonId() }
        catch (error: AppException) { if(error.code==ErrorCode.FORBIDDEN) null else throw error }
    private fun allowed(action: () -> Unit): Boolean = try { action();true }
        catch (error: AppException) { if(error.code==ErrorCode.FORBIDDEN) false else throw error }

    private fun requireExecutor(attemptId: UUID, person: UUID?, capabilityLinkId: UUID? = null) {
        val count=when {
            capabilityLinkId!=null && person==null -> jdbc.queryForObject("SELECT count(*) FROM merch_task_attempts WHERE id=? AND capability_link_id=? AND executor_person_id IS NULL",Int::class.java,attemptId,capabilityLinkId)
            capabilityLinkId==null && person!=null -> jdbc.queryForObject("SELECT count(*) FROM merch_task_attempts WHERE id=? AND executor_person_id=? AND capability_link_id IS NULL",Int::class.java,attemptId,person)
            else -> forbidden()
        }
        if(count!=1) forbidden()
    }
    private fun writable(d: TaskDispatchRow) {
        if (!d.branchActive || d.status in setOf("completed", "cancelled") || d.availableFrom?.isAfter(Instant.now()) == true) conflict("Задание не доступно для выполнения")
    }
    private fun available(item: TaskItemRow) { if(item.availableFrom?.isAfter(Instant.now())==true) conflict("Период пункта ещё не начался") }
    private fun refresh(id: UUID) {
        val states = jdbc.query("SELECT status FROM merch_task_items WHERE dispatch_id=? AND status<>'cancelled'", { rs, _ -> rs.getString(1) }, id)
        val status = when {
            states.isNotEmpty() && states.all { it == "completed" } -> "completed"
            states.any { it == "revision_required" } -> "revision_required"
            states.all { it in setOf("completed", "submitted") } -> "submitted"
            else -> "in_progress"
        }
        jdbc.update("UPDATE merch_task_dispatches SET status=?,version=version+1,updated_at=now() WHERE id=?", status, id)
    }
    private fun event(dispatch: UUID, item: UUID?, actor: UUID?, person: UUID?, type: String, details: Map<String, Any>) {
        jdbc.update("INSERT INTO merch_task_events(id,dispatch_id,item_id,actor_account_id,actor_person_id,event_type,details,created_at) VALUES (?,?,?,?,?,?,?::jsonb,now())",
            UUID.randomUUID(), dispatch, item, actor, person, type, json.writeValueAsString(details))
    }
    private fun validateCreate(req: CreateMerchTaskDispatch) {
        if (req.title.trim().length !in 2..160 || req.items.size !in 1..200 || req.items.none { it.required } ||
            req.priority !in setOf("low","normal","high","urgent") || req.availableFrom?.isAfter(req.dueAt)==true ||
            req.rewardAmountKzt !in 0L..1_000_000L || !req.dueAt.isAfter(Instant.now()) ||
            req.dueAt.isAfter(Instant.now().plusSeconds(366L * 86400)) || req.items.any {
                it.title.trim().length !in 2..240 || it.instructions.length > 4000 || it.requiredPhotos !in 0..12 ||
                    it.checklist.size > 20 || it.checklist.any { value -> value.trim().length !in 2..240 }
            }) invalid("Некорректные поля задания или сроки")
    }
    private fun imageBytes(file: MultipartFile): ByteArray {
        if (file.size > 20 * 1024 * 1024) throw AppException(ErrorCode.VALIDATION_FAILED,
            "Уменьшите фотографию до 20 МБ",HttpStatus.PAYLOAD_TOO_LARGE)
        if (file.isEmpty || file.originalFilename.orEmpty().length > 255 ||
            file.originalFilename.orEmpty().any(Char::isISOControl)) invalid("Нужна фотография JPG/PNG до 20 МБ")
        val bytes = file.bytes
        val jpeg = bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()
        val png = bytes.size >= 8 && bytes.copyOfRange(0,8).contentEquals(byteArrayOf(0x89.toByte(), 80,78,71,13,10,26,10))
        if (!jpeg && !png) invalid("Файл не является JPG/PNG")
        ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { stream ->
            val readers = ImageIO.getImageReaders(stream)
            if (!readers.hasNext()) invalid("Фотография не читается")
            val reader = readers.next()
            try {
                reader.input = stream
                val width = reader.getWidth(0); val height = reader.getHeight(0)
                if (width < 1 || height < 1) invalid("Недопустимый размер фотографии")
                if(width.toLong()*height>12_000_000) throw AppException(ErrorCode.VALIDATION_FAILED,
                    "Уменьшите фотографию до 12 мегапикселей",HttpStatus.PAYLOAD_TOO_LARGE)
                // Decode only after the long pixel-count check, within TaskPhotoGate's single slot.
                // Retain the original file bytes; decoding proves the submitted image is readable.
                val outputType = reader.getImageTypes(0).asSequence().firstOrNull { it.colorModel.pixelSize <= 32 }
                    ?: invalid("Используйте фотографию JPG/PNG с глубиной цвета до 32 бит")
                val parameters = reader.defaultReadParam.apply { destinationType = outputType }
                val decoded = reader.read(0, parameters) ?: invalid("Фотография не читается")
                try {
                    if (decoded.width != width || decoded.height != height) invalid("Недопустимый размер фотографии")
                } finally { decoded.flush() }
            } catch (error: AppException) { throw error }
            catch (_: Exception) { invalid("Фотография не читается") } finally { reader.dispose() }
        }
        return bytes
    }
    private fun page(limit: Int, offset: Int) { if (limit !in 1..50 || offset !in 0..1_000_000) invalid("Некорректная страница") }
    private fun ResultSet.uuid(name: String): UUID = getObject(name, UUID::class.java)
    private fun ResultSet.instant(name: String): Instant = getTimestamp(name).toInstant()
    private fun invalid(message: String): Nothing = throw AppException(ErrorCode.VALIDATION_FAILED, message)
    private fun conflict(message: String): Nothing = throw AppException(ErrorCode.CONFLICT, message, HttpStatus.CONFLICT)
    private fun forbidden(): Nothing = throw AppException(ErrorCode.FORBIDDEN, "Нет проверенного полномочия или личности исполнителя", HttpStatus.FORBIDDEN)
    private fun notFound(): Nothing = throw AppException(ErrorCode.NOT_FOUND, "Задание или доказательство не найдено", HttpStatus.NOT_FOUND)
}
