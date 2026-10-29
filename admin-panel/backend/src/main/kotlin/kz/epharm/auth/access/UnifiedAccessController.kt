package kz.epharm.auth.access

import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.entity.AdminUserEntity
import kz.epharm.auth.policy.*
import kz.epharm.auth.repository.AdminUserRepository
import kz.epharm.auth.security.AdminPrincipal
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import kz.epharm.shared.identity.*
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.util.UUID

// Regular classes deliberately avoid data-class toString() containing passwords.
class CreateManagedAccountRequest(
    @field:Email @field:NotBlank @field:Size(max = 255) val email: String,
    @field:NotBlank @field:Size(max = 255) val name: String,
    @field:Size(min = 12, max = 72) val password: String,
    val role: AdminRole,
    val verifiedPersonId: UUID,
    @field:Size(max = 255) val jobTitle: String = "",
    @field:Size(max = 128) val currentPassword: String,
)
class ReplaceGrantsRequest(
    val expectedVersion: Long,
    @field:Size(max = 200) val grants: List<StoredGrant>,
    @field:Size(max = 128) val currentPassword: String,
)
class VerifyPersonRequest(
    @field:NotBlank @field:Size(max=255) val name: String,
    @field:NotBlank @field:Size(min=3,max=1000) val verificationReference: String,
    @field:Size(max=128) val currentPassword: String,
)
class BindPharmacistPersonRequest(
    @field:NotBlank @field:Size(max=64) val pharmacistId: String,
    val verifiedPersonId: UUID,
    @field:NotBlank @field:Size(min=3,max=1000) val verificationReference: String,
    @field:Size(max=128) val currentPassword: String,
)
class BindSourceAccountRequest(
    val targetAccountId: UUID,
    val verifiedPersonId: UUID,
    @field:NotBlank @field:Size(min=3,max=1000) val verificationReference: String,
    @field:Size(max=128) val currentPassword: String,
)
class ProvisionSourceAccountRequest(
    val role: AdminRole,
    val verifiedPersonId: UUID,
    @field:Email @field:Size(max=255) val email: String? = null,
    @field:Size(max=255) val jobTitle: String = "",
    @field:NotBlank @field:Size(min=3,max=1000) val verificationReference: String,
    @field:Size(max=128) val currentPassword: String,
)

@RestController
@RequestMapping("/api/admin/access")
class UnifiedAccessController(private val service: UnifiedAccountAccessService) {
    @GetMapping("/me")
    fun capabilities(@AuthenticationPrincipal principal: AdminPrincipal) = service.capabilities(principal)

    @GetMapping("/catalog")
    fun catalog(@AuthenticationPrincipal principal: AdminPrincipal): Map<String, Any> {
        return service.catalog(principal)
    }

    @GetMapping("/accounts")
    fun accounts(@AuthenticationPrincipal principal: AdminPrincipal) = service.accounts(principal)

    @GetMapping("/people")
    fun people(@AuthenticationPrincipal principal: AdminPrincipal) = service.people(principal)

    @PostMapping("/people")
    @ResponseStatus(HttpStatus.CREATED)
    fun verifyPerson(@AuthenticationPrincipal principal: AdminPrincipal, @Valid @RequestBody body: VerifyPersonRequest) =
        service.verifyPerson(principal, body)

    @GetMapping("/pharmacist-options")
    fun pharmacistOptions(@AuthenticationPrincipal principal: AdminPrincipal,
        @RequestParam(defaultValue = "") q: String, @RequestParam(defaultValue = "50") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int) = service.pharmacistOptions(principal, q, limit, offset)

    @GetMapping("/source-accounts")
    fun sourceAccounts(@AuthenticationPrincipal principal: AdminPrincipal) = service.sourceAccounts(principal)

    @PostMapping("/source-accounts/{id}/bind")
    fun bindSource(@AuthenticationPrincipal principal: AdminPrincipal, @PathVariable id: UUID,
        @Valid @RequestBody body: BindSourceAccountRequest) = service.bindSourceAccount(principal, id, body)

    @PostMapping("/source-accounts/{id}/provision")
    @ResponseStatus(HttpStatus.CREATED)
    fun provisionSource(@AuthenticationPrincipal principal: AdminPrincipal, @PathVariable id: UUID,
        @Valid @RequestBody body: ProvisionSourceAccountRequest) = service.provisionSourceAccount(principal, id, body)

    @PostMapping("/pharmacist-person-links")
    fun bindPharmacist(@AuthenticationPrincipal principal: AdminPrincipal, @Valid @RequestBody body: BindPharmacistPersonRequest) =
        service.bindPharmacistPerson(principal, body)

    @GetMapping("/accounts/{id}")
    fun account(@AuthenticationPrincipal principal: AdminPrincipal, @PathVariable id: UUID): AccessProfile {
        service.requireManagementView(principal)
        return service.profile(id)
    }

    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@AuthenticationPrincipal principal: AdminPrincipal, @Valid @RequestBody req: CreateManagedAccountRequest) =
        service.create(principal, req)

    @PutMapping("/accounts/{id}/grants")
    fun replace(@AuthenticationPrincipal principal: AdminPrincipal, @PathVariable id: UUID,
        @Valid @RequestBody req: ReplaceGrantsRequest) = service.replace(principal, id, req)
}

data class AccessCapabilities(
    val managed: Boolean, val version: Long?, val grants: List<StoredGrant>,
    val roleCeiling: AdminRole,
    val canViewAccounts: Boolean,
    val canManageAccounts: Boolean,
    val canViewMerchInventory: Boolean = false,
    val canViewMerchPlacements: Boolean = false,
    val canViewMerchTasks: Boolean = false,
    val canManageCampaignProducer: Boolean = false,
    val canBindMerchProducer: Boolean = false,
    val canCreateMerchTasks: Boolean = false,
    val canExecuteMerchTasks: Boolean = false,
    val canReviewMerchTasks: Boolean = false,
    val canExportMerchInventory: Boolean = false,
    val canExportMerchPlacements: Boolean = false,
    val canExportMerchTasks: Boolean = false,
    val canExportTrainingAssignments: Boolean = false,
    val canExportTrainingResults: Boolean = false,
    val canExportTrainingCourses: Boolean = false,
    val canExportTrainingPrograms: Boolean = false,
    val canViewLedger: Boolean = false,
    val canExportLedger: Boolean = false,
    val canExportPayouts: Boolean = false,
    val canExportPharmacists: Boolean = false,
    val canExportCampaignReports: Boolean = false,
    val note: String = "Explicit grants also require existing role checks; NOT_READY routes deny managed accounts",
)

@Service
class UnifiedAccountAccessService(
    private val store: UnifiedAccessStore,
    private val catalog: UnifiedAdminCatalog,
    private val users: AdminUserRepository,
    private val jdbc: JdbcTemplate,
    private val encoder: PasswordEncoder,
) {
    fun capabilities(principal: AdminPrincipal): AccessCapabilities {
        val access = store.load(principal) ?: AdministrativeScope.deny()
        return AccessCapabilities(access.managed, access.profile?.version, access.profile?.grants.orEmpty(), access.role,
            canViewAccounts = canView(access), canManageAccounts = canManage(access),
            canViewMerchInventory = canUseExplicit(access, catalog.inventoryView),
            canViewMerchPlacements = canUseExplicit(access, catalog.placementView),
            canViewMerchTasks = canUseExplicit(access, catalog.taskView),
            canManageCampaignProducer = canUseExplicit(access, catalog.producerBindingManage),
            canBindMerchProducer = canUseExplicit(access, catalog.placementBindProducer),
            canCreateMerchTasks = canUseExplicit(access, catalog.taskCreate),
            canExecuteMerchTasks = canUseExplicit(access, catalog.taskExecute),
            canReviewMerchTasks = canUseExplicit(access, catalog.taskReview),
            canExportMerchInventory = canUseExplicit(access, catalog.inventoryExport),
            canExportMerchPlacements = canUseExplicit(access, catalog.placementExport),
            canExportMerchTasks = canUseExplicit(access, catalog.taskExport),
            canExportTrainingAssignments = canUseExplicit(access, catalog.trainingAssignmentExport),
            canExportTrainingResults = canUseExplicit(access, catalog.trainingResultExport),
            canExportTrainingCourses = canUseExplicit(access, catalog.trainingCourseExport),
            canExportTrainingPrograms = canUseExplicit(access, catalog.trainingProgramExport),
            canViewLedger = canUseExplicit(access, catalog.accrualView),
            canExportLedger = canUseExplicit(access, catalog.accrualExport),
            canExportPayouts = canUseExplicit(access, catalog.payoutExport),
            canExportPharmacists = canUseExplicit(access, catalog.pharmacistExport),
            canExportCampaignReports = canUseExplicit(access, catalog.campaignReportExport))
    }

    private fun canUseExplicit(access: LoadedAccess, key: PermissionKey): Boolean {
        if (access.profile == null || access.role !in catalog.availableRoles(key)) return false
        val scope = if (access.actor.primaryHqOwner) catalog.allScope(key)
            else access.actor.grants.singleOrNull { it.permission == key }?.scope ?: return false
        if (catalog.integration(key) == ScopeIntegration.ALL_ONLY && !catalog.isAllScope(key, scope)) return false
        // Menu eligibility only; item review still requires the current executor and ReviewContext.
        return catalog.policy.authorizeScope(access.actor, AccessRequest(key, scope), Instant.now()).allowed
    }

    private fun canView(access: LoadedAccess) = access.role in CORE_ROLES && catalog.policy.decide(
        access.actor, AccessRequest(catalog.accountView, DataScope(PharmacyScope.All)), Instant.now()).allowed

    private fun canManage(access: LoadedAccess) = access.actor.hqAccount && access.profile != null &&
        access.actor.grants.any { it.permission == catalog.accountManage && it.scope.pharmacies is PharmacyScope.All }

    fun requireManagementView(principal: AdminPrincipal) {
        val access = store.load(principal) ?: AdministrativeScope.deny()
        if (!canView(access)) AdministrativeScope.deny()
    }

    @Transactional
    fun verifyPerson(principal: AdminPrincipal, req: VerifyPersonRequest): VerifiedPersonRow {
        store.lockAccounts(setOf(principal.userId))
        passwordProof(principal, req.currentPassword)
        if (req.verificationReference.isBlank()) throw AppException(ErrorCode.VALIDATION_FAILED, "Укажите основание проверки личности")
        val id = UUID.randomUUID()
        jdbc.update("insert into unified_persons(id,verification_reference,display_name) values(?,?,?)",
            id, req.verificationReference.trim(), req.name.trim())
        identityAudit(principal.userId, "PERSON", id.toString(), "VERIFY_NEW_PERSON")
        return VerifiedPersonRow(id, req.name.trim())
    }

    @Transactional(readOnly = true)
    fun pharmacistOptions(principal: AdminPrincipal, query: String, limit: Int, offset: Int): PharmacistIdentityOptions {
        requireManagementView(principal)
        if (query.length > 120 || limit !in 1..100 || offset !in 0..1_000_000)
            throw AppException(ErrorCode.VALIDATION_FAILED, "Некорректный поиск фармацевта")
        val escaped = query.trim().replace("!", "!!").replace("%", "!%").replace("_", "!_")
        val pattern = "%$escaped%"
        val rows = jdbc.query("""select p.id,p.name,p.pharmacy_name,l.person_id from pharmacists p
            left join pharmacist_person_links l on l.pharmacist_id=p.id
            where p.id ilike ? escape '!' or p.name ilike ? escape '!' or p.pharmacy_name ilike ? escape '!'
            order by lower(p.name),p.id limit ? offset ?""", { rs, _ ->
            PharmacistIdentityOption(rs.getString(1),rs.getString(2),rs.getString(3),rs.getObject(4,UUID::class.java))
        },pattern,pattern,pattern,limit+1,offset)
        return PharmacistIdentityOptions(rows.take(limit),limit,offset,rows.size>limit)
    }

    @Transactional(readOnly = true)
    fun sourceAccounts(principal: AdminPrincipal): List<SourceAccountRow> {
        requireManagementView(principal)
        return jdbc.query("""select s.id,s.full_name,s.email,s.source_role,s.active,
            (s.password_hash is not null and length(s.password_hash)>0),b.admin_account_id,b.person_id
            from unified_external_accounts s left join unified_external_account_bindings b on b.source_account_id=s.id
            order by s.full_name,s.id limit 2000""", { rs, _ ->
            SourceAccountRow(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getBoolean(5), rs.getBoolean(6), rs.getObject(7, UUID::class.java), rs.getObject(8, UUID::class.java))
        })
    }

    @Transactional
    fun bindSourceAccount(principal: AdminPrincipal, sourceId: UUID, req: BindSourceAccountRequest): Map<String, Any> {
        store.lockAccounts(setOf(principal.userId, req.targetAccountId))
        passwordProof(principal, req.currentPassword)
        verifiedPerson(req.verifiedPersonId)
        val target = store.profile(req.targetAccountId) ?: throw AppException(ErrorCode.VALIDATION_FAILED, "Сначала создайте проверенный профиль аккаунта")
        if (target.personId != req.verifiedPersonId) throw AppException(ErrorCode.VALIDATION_FAILED, "Личность не совпадает с профилем аккаунта")
        val role = jdbc.queryForList("select source_role from unified_external_accounts where id=? for update", String::class.java, sourceId).singleOrNull()
            ?: throw AppException(ErrorCode.NOT_FOUND, "Исходная запись не найдена", HttpStatus.NOT_FOUND)
        if (role == "advertiser") throw AppException(ErrorCode.VALIDATION_FAILED, "Это аккаунт кабинета производителя")
        if (jdbc.queryForObject("select count(*) from unified_external_account_bindings where source_account_id=?", Long::class.java, sourceId) != 0L)
            throw AppException(ErrorCode.CONFLICT, "Исходный аккаунт уже сопоставлен", HttpStatus.CONFLICT)
        jdbc.update("""insert into unified_external_account_bindings(source_account_id,admin_account_id,person_id,
            verification_reference,verified_by) values(?,?,?,?,?)""", sourceId, req.targetAccountId, req.verifiedPersonId,
            req.verificationReference.trim(), principal.userId)
        identityAudit(principal.userId, "ACCOUNT", sourceId.toString(), "BIND_SOURCE_ACCOUNT")
        return mapOf("sourceAccountId" to sourceId, "accountId" to req.targetAccountId, "personId" to req.verifiedPersonId)
    }

    @Transactional
    fun provisionSourceAccount(principal: AdminPrincipal, sourceId: UUID, req: ProvisionSourceAccountRequest): AccessProfile {
        store.lockAccounts(setOf(principal.userId))
        passwordProof(principal, req.currentPassword)
        verifiedPerson(req.verifiedPersonId)
        val source = jdbc.queryForList("select email,full_name,password_hash,source_role,active from unified_external_accounts where id=? for update", sourceId).singleOrNull()
            ?: throw AppException(ErrorCode.NOT_FOUND, "Исходная запись не найдена", HttpStatus.NOT_FOUND)
        if (source["source_role"] == "advertiser") throw AppException(ErrorCode.VALIDATION_FAILED, "Производитель входит в отдельный кабинет по SMS")
        val hash = source["password_hash"] as? String
        if (hash == null || !Regex("\\$2[aby]\\$(0[4-9]|1[0-6])\\$[./A-Za-z0-9]{53}").matches(hash))
            throw AppException(ErrorCode.VALIDATION_FAILED, "Нет поддерживаемого исходного пароля; создайте обычный аккаунт и сопоставьте его")
        val email = (req.email ?: source["email"] as? String)?.trim()?.lowercase()
        if (email.isNullOrBlank() || email.length > 255 || !email.contains('@'))
            throw AppException(ErrorCode.VALIDATION_FAILED, "Укажите email нового аккаунта")
        if (users.findByEmailIgnoreCase(email).isPresent) throw AppException(ErrorCode.CONFLICT, "Email уже занят: используйте явное сопоставление существующего аккаунта", HttpStatus.CONFLICT)
        if (jdbc.queryForObject("select count(*) from unified_external_account_bindings where source_account_id=?", Long::class.java, sourceId) != 0L)
            throw AppException(ErrorCode.CONFLICT, "Исходный аккаунт уже сопоставлен", HttpStatus.CONFLICT)
        val user = users.saveAndFlush(AdminUserEntity(email=email,name=source["full_name"] as String,passwordHash=hash,roleRaw=req.role.name))
        jdbc.update("update admin_users set access_mode='MANAGED',status=? where id=?", if (source["active"] == true) "active" else "blocked", user.id)
        jdbc.update("insert into unified_admin_profiles(account_id,person_id,job_title) values(?,?,?)", user.id, req.verifiedPersonId, req.jobTitle)
        jdbc.update("insert into unified_external_account_bindings(source_account_id,admin_account_id,person_id,verification_reference,verified_by) values(?,?,?,?,?)",
            sourceId,user.id,req.verifiedPersonId,req.verificationReference.trim(),principal.userId)
        store.audit(principal.userId,user.id,"PROVISION_EMPTY_FROM_SOURCE",0)
        identityAudit(principal.userId,"ACCOUNT",sourceId.toString(),"PROVISION_SOURCE_ACCOUNT")
        return profile(user.id)
    }

    @Transactional
    fun bindPharmacistPerson(principal: AdminPrincipal, req: BindPharmacistPersonRequest): Map<String, Any> {
        store.lockAccounts(setOf(principal.userId))
        passwordProof(principal, req.currentPassword)
        verifiedPerson(req.verifiedPersonId)
        if (jdbc.queryForList("select id from pharmacists where id=? for update", String::class.java, req.pharmacistId).isEmpty())
            throw AppException(ErrorCode.NOT_FOUND, "Фармацевт не найден", HttpStatus.NOT_FOUND)
        if (jdbc.queryForObject("select count(*) from pharmacist_person_links where pharmacist_id=?", Long::class.java, req.pharmacistId) != 0L)
            throw AppException(ErrorCode.CONFLICT, "Личность фармацевта уже сопоставлена", HttpStatus.CONFLICT)
        jdbc.update("insert into pharmacist_person_links(pharmacist_id,person_id,verification_reference,verified_by) values(?,?,?,?)",
            req.pharmacistId, req.verifiedPersonId, req.verificationReference.trim(), principal.userId)
        identityAudit(principal.userId, "PHARMACIST", req.pharmacistId, "BIND_PHARMACIST_PERSON")
        return mapOf("pharmacistId" to req.pharmacistId, "personId" to req.verifiedPersonId)
    }

    private fun verifiedPerson(id: UUID) {
        if (jdbc.queryForObject("select count(*) from unified_persons where id=? and length(btrim(verification_reference))>0", Long::class.java, id) != 1L)
            throw AppException(ErrorCode.VALIDATION_FAILED, "Требуется проверенная личность")
    }

    private fun identityAudit(actor: UUID, type: String, id: String, operation: String) {
        jdbc.update("insert into unified_identity_audit(id,actor_id,entity_type,entity_id,operation) values(?,?,?,?,?)",
            UUID.randomUUID(), actor, type, id, operation)
    }

    @Transactional(readOnly = true)
    fun accounts(principal: AdminPrincipal): List<ManagedAccountRow> {
        requireManagementView(principal)
        val access = store.load(principal)!!
        return jdbc.query("""select a.id,a.name,a.email,a.role,a.status,a.access_mode,
            p.person_id,p.job_title,p.version from admin_users a
            left join unified_admin_profiles p on p.account_id=a.id order by lower(a.name),a.id""", { rs, _ ->
            val id = rs.getObject(1, UUID::class.java)
            val person = rs.getObject(7, UUID::class.java)
            val managed = rs.getString(6) == "MANAGED"
            val owner = store.isDesignatedOwner(id)
            ManagedAccountRow(id, person, rs.getString(2), rs.getString(3), rs.getString(8).orEmpty(),
                AdminRole.valueOf(rs.getString(4)), rs.getString(5), managed,
                rs.getObject(9)?.let { (it as Number).toLong() }, owner,
                canManage(access) && managed && person != null && id != principal.userId &&
                    person.toString() != access.actor.person?.id && !owner && rs.getString(5) == "active")
        })
    }

    @Transactional(readOnly = true)
    fun people(principal: AdminPrincipal): List<VerifiedPersonRow> {
        requireManagementView(principal)
        // Proof text can contain private source references; the selector only receives a generic label.
        val person = store.load(principal)?.actor?.person?.id.orEmpty()
        return jdbc.query("""select p.id,coalesce(p.display_name,nullif(min(a.name),''),'Проверенная личность '||left(p.id::text,8))
            from unified_persons p left join unified_admin_profiles ap on ap.person_id=p.id
            left join admin_users a on a.id=ap.account_id where length(btrim(p.verification_reference))>0 and p.id::text<>?
            group by p.id,p.display_name order by 2,p.id""", { rs, _ ->
            VerifiedPersonRow(rs.getObject(1, UUID::class.java), rs.getString(2))
        }, person)
    }

    @Transactional(readOnly = true)
    fun catalog(principal: AdminPrincipal): Map<String, Any> {
        requireManagementView(principal)
        val access = store.load(principal)!!
        val presentation = UnifiedAccessPresentation(catalog)
        val permissions = presentation.permissions(access, canManage(access))
        val scopes = permissions.filter { it.mode == ScopeIntegration.SCOPED }.mapNotNull { it.delegationScope }
        val all = scopes.any { it.allPharmacies }
        val ids = scopes.flatMap { it.pharmacyIds }.toSet()
        val pharmacies = if (!all && ids.isEmpty()) emptyList() else jdbc.query(
            "select id,name,city,addr from pharmacies" + (if (all) "" else " where id in (${ids.joinToString(",") { "?" }})") + " order by city,name,id",
            { rs, _ -> AccessPharmacyRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)) },
            *(if (all) emptyArray() else ids.toTypedArray()),
        )
        val allBranches = scopes.any { it.allMerchBranches }
        val branchIds = scopes.flatMap { it.merchBranchIds }.toSet()
        val branches = if (!allBranches && branchIds.isEmpty()) emptyList() else jdbc.query(
            "select id,name,city,address from merch_branches" + (if (allBranches) "" else
                " where id in (${branchIds.joinToString(",") { "?::uuid" }})") + " order by city,name,id",
            { rs, _ -> AccessPharmacyRow(rs.getString(1), rs.getString(2), rs.getString(3).orEmpty(), rs.getString(4).orEmpty()) },
            *(if (allBranches) emptyArray() else branchIds.toTypedArray()),
        )
        val allProducers = scopes.any { it.allProducers }
        val producerIds = scopes.flatMap { it.producerIds }.toSet()
        val producers = if (!allProducers && producerIds.isEmpty()) emptyList() else jdbc.queryForList(
            "select id,name from merch_producers" + (if (allProducers) "" else
                " where id in (${producerIds.joinToString(",") { "?::uuid" }})") + " order by name,id",
            *(if (allProducers) emptyArray() else producerIds.toTypedArray()))
        return mapOf("routes" to catalog.routes, "permissions" to permissions, "pharmacies" to pharmacies,
            "merchBranches" to branches,
            "producers" to producers,
            "assignableRoles" to if (canManage(access)) AdminRole.entries else emptyList<AdminRole>())
    }

    fun profile(id: UUID) = store.profile(id) ?: throw AppException(ErrorCode.NOT_FOUND, "Аккаунт доступа не найден", HttpStatus.NOT_FOUND)

    private fun passwordProof(principal: AdminPrincipal, password: String): Pair<LoadedAccess, ReauthenticationEvidence> {
        val access = store.load(principal) ?: AdministrativeScope.deny()
        if (access.profile == null) AdministrativeScope.deny()
        val hash = jdbc.queryForObject("select password_hash from admin_users where id=?", String::class.java, principal.userId)
        if (!access.actor.hqAccount || !encoder.matches(password, hash)) AdministrativeScope.deny()
        val proof = ReauthenticationEvidence(access.actor.account, ReauthenticationMethod.PASSWORD, Instant.now())
        // This permission is intentionally not granted by HQ title, legacy role or primary-owner bypass.
        if (access.actor.grants.none { it.permission == catalog.accountManage && it.scope.pharmacies is PharmacyScope.All }) {
            AdministrativeScope.deny()
        }
        return access to proof
    }

    @Transactional
    fun create(principal: AdminPrincipal, req: CreateManagedAccountRequest): AccessProfile {
        store.lockAccounts(setOf(principal.userId))
        val (access, _) = passwordProof(principal, req.currentPassword)
        val actor = access.actor
        if (actor.person?.id == req.verifiedPersonId.toString()) AdministrativeScope.deny()
        if (req.password.toByteArray(Charsets.UTF_8).size > 72) {
            throw AppException(ErrorCode.VALIDATION_FAILED, "Пароль превышает допустимую длину UTF-8")
        }
        // Human links must be verified before provisioning; a name/email match cannot create one.
        if (jdbc.queryForObject("select count(*) from unified_persons where id=? and length(btrim(verification_reference))>0",
                Long::class.java, req.verifiedPersonId) != 1L) {
            throw AppException(ErrorCode.VALIDATION_FAILED, "Требуется проверенная связь с человеком")
        }
        if (users.findByEmailIgnoreCase(req.email).isPresent) throw AppException(ErrorCode.CONFLICT, "Email уже используется", HttpStatus.CONFLICT)
        // Persisted legacy role is a ceiling, not an authority borrowed from the delegator.
        if (req.role != access.role && access.role !in setOf(AdminRole.SYSTEM_ADMIN, AdminRole.HQ_HEAD)) AdministrativeScope.deny()
        val user = users.saveAndFlush(AdminUserEntity(email = req.email.trim().lowercase(), name = req.name.trim(),
            passwordHash = encoder.encode(req.password), roleRaw = req.role.name))
        jdbc.update("update admin_users set access_mode='MANAGED' where id=?", user.id)
        jdbc.update("insert into unified_admin_profiles(account_id,person_id,job_title) values(?,?,?)", user.id, req.verifiedPersonId, req.jobTitle)
        store.audit(principal.userId, user.id, "CREATE_EMPTY", 0)
        return profile(user.id) // Empty permissions: no accidental access at first login.
    }

    @Transactional
    fun replace(principal: AdminPrincipal, target: UUID, req: ReplaceGrantsRequest): AccessProfile {
        store.lockAccounts(setOf(principal.userId, target))
        val (access, proof) = passwordProof(principal, req.currentPassword)
        val recipientUser = users.findById(target).orElse(null) ?: AdministrativeScope.deny()
        val recipient = store.load(AdminPrincipal(target, recipientUser.email, recipientUser.name, recipientUser.role, recipientUser.company))
            ?: AdministrativeScope.deny()
        if (!recipient.managed || recipient.profile == null || recipient.actor.primaryHqOwner ||
            recipient.actor.account == access.actor.account || recipient.actor.person == access.actor.person) AdministrativeScope.deny()
        val grants = try { req.grants.map { it.toGrant() } } catch (_: IllegalArgumentException) {
            throw AppException(ErrorCode.VALIDATION_FAILED, "Некорректное разрешение или область аптек")
        }
        if (grants.map { it.permission }.distinct().size != grants.size || grants.any { !catalog.supported(it.permission) }) {
            throw AppException(ErrorCode.VALIDATION_FAILED, "Неизвестное, повторное или ещё не подключённое разрешение")
        }
        if (grants.any { recipient.role !in catalog.availableRoles(it.permission) ||
                catalog.integration(it.permission) == ScopeIntegration.ALL_ONLY && !catalog.isAllScope(it.permission, it.scope) }) {
            throw AppException(ErrorCode.VALIDATION_FAILED, "Разрешение несовместимо с ролью или областью аккаунта")
        }
        val ids = req.grants.flatMap { it.pharmacyIds }.toSet()
        if (ids.size > 2000) throw AppException(ErrorCode.VALIDATION_FAILED, "Слишком большая область аптек")
        val known = if (ids.isEmpty()) 0L else jdbc.queryForObject(
            "select count(*) from pharmacies where id in (${ids.joinToString(",") { "?" }})", Long::class.java, *ids.toTypedArray(),
        )
        if (known != ids.size.toLong()) {
            throw AppException(ErrorCode.VALIDATION_FAILED, "Аптеки не подтверждены")
        }
        val branchIds = req.grants.flatMap { it.merchBranchIds }.toSet()
        if (branchIds.size > 2000) throw AppException(ErrorCode.VALIDATION_FAILED, "Слишком большая область аптек мерча")
        val knownBranches = if (branchIds.isEmpty()) 0L else jdbc.queryForObject(
            "select count(*) from merch_branches where id in (${branchIds.joinToString(",") { "?::uuid" }})",
            Long::class.java, *branchIds.toTypedArray())
        if (knownBranches != branchIds.size.toLong()) throw AppException(ErrorCode.VALIDATION_FAILED, "Аптеки мерча не подтверждены")
        val producerIds = req.grants.flatMap { it.producerIds }.toSet()
        if (producerIds.size > 2000) throw AppException(ErrorCode.VALIDATION_FAILED, "Слишком большая область производителей")
        val knownProducers = if (producerIds.isEmpty()) 0L else jdbc.queryForObject(
            "select count(*) from merch_producers where id in (${producerIds.joinToString(",") { "?::uuid" }})", Long::class.java, *producerIds.toTypedArray())
        if (knownProducers != producerIds.size.toLong()) throw AppException(ErrorCode.VALIDATION_FAILED, "Производители не подтверждены")
        for (grant in grants) {
            if (!catalog.policy.delegate(access.actor, recipient.actor, grant, catalog.accountManage, Instant.now(), proof).allowed) AdministrativeScope.deny()
        }
        // Empty replacement still cannot change oneself or bypass the separate control/password check above.
        if (!store.replace(principal.userId, target, req.expectedVersion, req.grants)) {
            throw AppException(ErrorCode.CONFLICT, "Права уже изменились: обновите страницу", HttpStatus.CONFLICT)
        }
        return profile(target)
    }

    companion object {
        private val CORE_ROLES = setOf(AdminRole.SYSTEM_ADMIN, AdminRole.HQ_HEAD, AdminRole.CATEGORY_LEAD,
            AdminRole.BRAND_MANAGER, AdminRole.FINANCE_REVIEWER)
    }
}

data class ManagedAccountRow(
    val accountId: UUID, val personId: UUID?, val name: String, val email: String, val jobTitle: String,
    val roleCeiling: AdminRole, val status: String, val managed: Boolean, val version: Long?,
    val primaryHqOwner: Boolean, val editable: Boolean,
)
data class VerifiedPersonRow(val personId: UUID, val displayName: String, val referenceLabel: String = "Связь подтверждена")
data class SourceAccountRow(val sourceAccountId: UUID, val name: String, val email: String?, val sourceRole: String,
    val active: Boolean, val hasPassword: Boolean, val accountId: UUID?, val personId: UUID?)
data class AccessPharmacyRow(val id: String, val name: String, val city: String, val address: String)

data class PharmacistIdentityOption(val id: String, val name: String, val pharmacyName: String?, val personId: UUID?)
data class PharmacistIdentityOptions(val items: List<PharmacistIdentityOption>, val limit: Int, val offset: Int, val hasMore: Boolean)
