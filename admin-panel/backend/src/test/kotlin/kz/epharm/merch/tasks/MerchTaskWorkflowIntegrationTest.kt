package kz.epharm.merch.tasks

import com.fasterxml.jackson.databind.ObjectMapper
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import javax.imageio.ImageIO
import kz.epharm.merch.delivery.NativeCapabilityAuthorization
import kz.epharm.auth.access.StoredGrant
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.entity.AdminUserEntity
import kz.epharm.auth.repository.AdminUserRepository
import kz.epharm.auth.service.JwtService
import kz.epharm.finance.service.PharmacistWalletService
import kz.epharm.finance.service.WalletCreditSource
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class MerchTaskWorkflowIntegrationTest {
    companion object {
        private val files = Files.createTempDirectory("synthetic-merch-evidence-").toRealPath().also {
            Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rwx------")) }
        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("merch_native_tasks").withUsername("epharm").withPassword("synthetic-only").apply { start() }
        @JvmStatic @DynamicPropertySource fun props(reg: DynamicPropertyRegistry) {
            reg.add("spring.datasource.url") { postgres.jdbcUrl }; reg.add("spring.datasource.username") { postgres.username }
            reg.add("spring.datasource.password") { postgres.password }
            reg.add("app.merch-tasks.evidence-root") { files.toString() }
            reg.add("app.merch-tasks.legacy-files-root") { files.toString() }
        }
    }
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var users: AdminUserRepository
    @Autowired lateinit var jwt: JwtService
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var wallet: PharmacistWalletService
    @Autowired lateinit var transactionManager: PlatformTransactionManager
    private lateinit var admin: AdminUserEntity
    private lateinit var reviewer: AdminUserEntity
    private lateinit var adminPerson: UUID
    private lateinit var pharmacistPerson: UUID
    private lateinit var branch: UUID
    private val adminAuth get() = "Bearer ${jwt.issueAccessToken(admin)}"
    private val reviewerAuth get() = "Bearer ${jwt.issueAccessToken(reviewer)}"
    private val mobileAuth get() = "Bearer ${jwt.issuePharmacistToken("task-pharmacist", "Synthetic", "+77000000001")}"
    private val photo get() = ByteArrayOutputStream().use { out -> ImageIO.write(BufferedImage(32,32,BufferedImage.TYPE_INT_RGB), "png", out); out.toByteArray() }

    @BeforeEach fun seed() {
        // This class owns its disposable PG16 and synthetic files; no source/import/production connection is used.
        jdbc.execute("TRUNCATE merch_task_dispatches,merch_branches,merch_organizations,merch_executor_bindings,merch_reward_events,pharmacist_wallet_entries,pharmacist_wallet_checkpoints,pharmacist_person_links,pharmacists,unified_admin_profiles,unified_persons,admin_users,pharmacies,chains CASCADE")
        admin = users.saveAndFlush(AdminUserEntity(email="task-author@example.invalid",name="Synthetic author",passwordHash="unused-synthetic",roleRaw=AdminRole.HQ_HEAD.name))
        reviewer = users.saveAndFlush(AdminUserEntity(email="task-reviewer@example.invalid",name="Synthetic reviewer",passwordHash="unused-synthetic",roleRaw=AdminRole.HQ_HEAD.name))
        adminPerson = UUID.randomUUID(); pharmacistPerson = UUID.randomUUID()
        val reviewerPerson = UUID.randomUUID()
        for (person in listOf(adminPerson,pharmacistPerson,reviewerPerson)) jdbc.update("insert into unified_persons values(?,?)",person,"synthetic-reviewed-proof")
        jdbc.update("insert into unified_admin_profiles(account_id,person_id) values(?,?),(?,?)",admin.id,adminPerson,reviewer.id,reviewerPerson)
        jdbc.update("update admin_users set access_mode='MANAGED' where id in (?,?)",admin.id,reviewer.id)
        jdbc.update("""insert into chains(id,name,color,"group",points) values('task-chain','Synthetic','#000000','pilot',0)""")
        for (id in listOf("task-pharmacy","task-other")) jdbc.update("""insert into pharmacies(id,name,chain_id,chain_name,city,district,addr,"group")
            values(?,?,'task-chain','Synthetic','Synthetic','','','pilot')""",id,id)
        jdbc.update("""insert into pharmacists(id,name,iin,phone,pharmacy_id,pharmacy_name,city,tier,status,joined_at,balance)
            values('task-pharmacist','Synthetic','000000000001','+77000000001','task-pharmacy','Synthetic','Synthetic','Silver','active',current_date,500)""")
        jdbc.update("insert into pharmacist_person_links(pharmacist_id,person_id,verification_reference,verified_by) values('task-pharmacist',?,'synthetic-proof',?)",pharmacistPerson,admin.id)
        val mapping=UUID.randomUUID()
        jdbc.update("""insert into posm_pharmacist_mappings(id,pharmacy_id,external_user_id,pharmacist_id,created_by,updated_by)
            values(?,'task-pharmacy','synthetic-posm-id','task-pharmacist',?,?)""",mapping,admin.id,admin.id)
        jdbc.update("""insert into posm_pharmacist_mapping_audit(mapping_id,action,pharmacy_id,external_user_id,pharmacist_id,actor_id)
            values(?,'created','task-pharmacy','synthetic-posm-id','task-pharmacist',?)""",mapping,admin.id)
        val org = UUID.randomUUID(); branch=UUID.randomUUID()
        jdbc.update("""insert into merch_organizations(id,source_system,source_origin,source_id,source_payload,name,legal_name,active,created_at,updated_at)
            values(?,'EPHARM','https://epharm.inkar.kz',?,'{}','Synthetic','Synthetic',true,now(),now())""",org,org)
        jdbc.update("""insert into merch_branches(id,source_system,source_origin,source_id,source_payload,organization_id,epharm_pharmacy_id,pharmacy_binding_verified,
            code,name,address,city,active,created_at,updated_at) values(?,'EPHARM','https://epharm.inkar.kz',?,'{}',?,'task-pharmacy',true,'001','Synthetic','','Synthetic',true,now(),now())""",branch,branch,org)
        grants(admin.id, "view","create","execute","review","financial_data"); grants(reviewer.id,"view","review","financial_data")
    }
    private fun grants(account: UUID, vararg actions: String) {
        jdbc.update("update unified_admin_profiles set grants_json=?::jsonb,version=version+1 where account_id=?", json.writeValueAsString(actions.map {
            StoredGrant("merchandising","tasks",it,merchBranchIds=setOf(branch.toString())) }), account)
    }
    private fun create(amount: Long=300, count: Int=2): MerchTaskDispatchDto {
        val req=CreateMerchTaskDispatch(branch,"Synthetic photo packet",Instant.now().plusSeconds(3600),amount,"task-pharmacist",
            (1..count).map { CreateMerchTaskItem("Concrete shelf $it",requiredPhotos=1,checklist=listOf("Check display")) })
        return json.readValue(mvc.perform(post("/api/admin/merch/tasks").header("Authorization",adminAuth).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsBytes(req))).andExpect(status().isOk).andReturn().response.contentAsByteArray,MerchTaskDispatchDto::class.java)
    }
    private fun get(id: UUID): MerchTaskDispatchDto = json.readValue(mvc.perform(get("/api/admin/merch/tasks/$id").header("Authorization",adminAuth))
        .andExpect(status().isOk).andReturn().response.contentAsByteArray,MerchTaskDispatchDto::class.java)
    private fun binding(d: MerchTaskDispatchDto, amount: Long=d.rewardAmountKzt!!) {
        // Synthetic trusted fixture for the existing OTP service's immutable binding, never an alternative runtime proof.
        jdbc.update("""insert into merch_executor_bindings(id,dispatch_id,pharmacist_id,pharmacy_id,reward_kzt,task_due_at,verified_at)
            values(?,?,'task-pharmacist','task-pharmacy',?,?,now())""",UUID.randomUUID(),d.id,amount,Timestamp.from(d.dueAt!!))
    }
    private fun start(item: UUID, auth: String=mobileAuth, mobile: Boolean=true): MerchTaskAttemptDto = json.readValue(mvc.perform(post(
        "/api/${if(mobile) "mobile" else "admin"}/merch/tasks/items/$item/attempts").header("Authorization",auth)).andExpect(status().isOk)
        .andReturn().response.contentAsByteArray,MerchTaskAttemptDto::class.java)
    private fun upload(item: UUID, upload: UUID=UUID.randomUUID(), auth: String=mobileAuth, mobile: Boolean=true): MerchTaskEvidenceDto = json.readValue(mvc.perform(multipart(
        "/api/${if(mobile) "mobile" else "admin"}/merch/tasks/items/$item/evidence").file(MockMultipartFile("file","synthetic.png","image/png",photo))
        .param("clientUploadId",upload.toString()).header("Authorization",auth)).andExpect(status().isOk).andReturn().response.contentAsByteArray,MerchTaskEvidenceDto::class.java)
    private fun submit(item: MerchTaskItemDto, attempt: MerchTaskAttemptDto, auth: String=mobileAuth, mobile: Boolean=true) = mvc.perform(post(
        "/api/${if(mobile) "mobile" else "admin"}/merch/tasks/items/${item.id}/submit").header("Authorization",auth).contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsBytes(SubmitMerchTaskAttempt(attempt.number,item.checklist.map { it.id }.toSet())))).andExpect(status().isOk)
    private fun review(d: UUID, itemId: UUID, decision: String="approve", auth: String=adminAuth): MerchTaskDispatchDto {
        val item=get(d).items.single { it.id==itemId }
        val req=ReviewMerchTaskItem(item.currentAttempt!!.id,item.version,decision,"Manually verified original photo")
        return json.readValue(mvc.perform(post("/api/admin/merch/tasks/items/$itemId/reviews").header("Authorization",auth).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsBytes(req))).andExpect(status().isOk).andReturn().response.contentAsByteArray,MerchTaskDispatchDto::class.java)
    }

    private fun mine(limit: Int = 20, offset: Int = 0, auth: String = adminAuth, query: String? = null): MerchTaskDispatchPage {
        val request = get("/api/admin/merch/tasks").param("queue", "mine").param("limit", limit.toString())
            .param("offset", offset.toString()).header("Authorization", auth)
        query?.let { request.param("q", it) }
        return json.readValue(mvc.perform(request).andExpect(status().isOk).andReturn().response.contentAsByteArray,
            MerchTaskDispatchPage::class.java)
    }

    private fun personallyAssignedSource(count: Int = 1): Pair<MerchTaskDispatchDto, UUID> {
        val task = create(amount = 0, count = count)
        val source = UUID.randomUUID()
        jdbc.update("""insert into unified_external_accounts(id,source_system,source_origin,source_payload,full_name,source_role,active,created_at,updated_at)
            values(?,'MERCH_CRM','https://crm.inkar.kz','{}','Synthetic author','mass_manager',true,now(),now())""", source)
        jdbc.update("""insert into unified_external_account_bindings(source_account_id,admin_account_id,person_id,verification_reference,verified_by)
            values(?,?,?,'synthetic-explicit-person-binding',?)""", source, admin.id, adminPerson, admin.id)
        jdbc.update("update merch_task_dispatches set source_system='MERCH_CRM',source_origin='https://crm.inkar.kz',source_mass_owner_id=? where id=?", source, task.id)
        jdbc.update("update merch_task_items set source_assignee_id=? where dispatch_id=?", source, task.id)
        return task to source
    }

    @Test fun `mine uses explicit source assignee binding and never creator branch owner or matching name`() {
        val unassigned = create(amount = 0, count = 1)
        val (assigned, source) = personallyAssignedSource()
        assertEquals(listOf(assigned.id), mine().items.map { it.id })
        assertEquals(assigned.items.single().id, mine().items.single().nextExecutionItemId)
        assertNull(get(assigned.id).nextExecutionItemId)
        val ordinary = json.readValue(mvc.perform(get("/api/admin/merch/tasks").header("Authorization", adminAuth))
            .andExpect(status().isOk).andReturn().response.contentAsByteArray, MerchTaskDispatchPage::class.java)
        assertTrue(ordinary.items.all { it.nextExecutionItemId == null })
        grants(reviewer.id, "view", "execute")
        assertTrue(mine(auth = reviewerAuth).items.isEmpty())
        jdbc.update("update merch_task_items set source_assignee_id=null where dispatch_id=?", assigned.id)
        assertTrue(mine().items.isEmpty()) // source_mass_owner_id and creator are still this actor.
        jdbc.update("update merch_task_items set source_assignee_id=? where dispatch_id=?", source, unassigned.id)
        assertTrue(mine().items.isEmpty()) // A source UUID on an EPHARM task is not a CRM assignment.
    }

    @Test fun `mine fails closed for inactive revoked or mismatched source binding without changing ordinary view`() {
        val (assigned, source) = personallyAssignedSource()
        jdbc.update("update unified_external_accounts set active=false where id=?", source)
        assertTrue(mine().items.isEmpty())
        assertEquals(assigned.id, get(assigned.id).id)
        jdbc.update("update unified_external_accounts set active=true where id=?", source)
        jdbc.update("update unified_external_account_bindings set person_id=? where source_account_id=?", pharmacistPerson, source)
        assertTrue(mine().items.isEmpty())
        jdbc.update("update unified_external_account_bindings set person_id=?,admin_account_id=? where source_account_id=?", adminPerson, reviewer.id, source)
        assertTrue(mine().items.isEmpty()) // Bound account's actual profile belongs to a different person.
        jdbc.update("update unified_admin_profiles set person_id=? where account_id=?", adminPerson, reviewer.id)
        assertEquals(assigned.id, mine().items.single().id) // Same verified human, not an inferred account-name match.
        jdbc.update("update admin_users set status='blocked' where id=?", reviewer.id)
        assertTrue(mine().items.isEmpty())
        jdbc.update("update admin_users set status='active' where id=?", reviewer.id)
        jdbc.update("delete from unified_external_account_bindings where source_account_id=?", source)
        assertTrue(mine().items.isEmpty())
    }

    @Test fun `mine requires verified person and execution permission independently of assigned source identity`() {
        val (assigned, _) = personallyAssignedSource()
        grants(admin.id, "view")
        assertTrue(mine().items.isEmpty())
        assertEquals(assigned.id, get(assigned.id).id)
        grants(admin.id, "view", "execute")
        jdbc.update("update unified_persons set verification_reference=' ' where id=?", adminPerson)
        assertTrue(mine().items.isEmpty())
        jdbc.update("update unified_persons set verification_reference='synthetic-proof' where id=?", adminPerson)
        assertEquals(assigned.id, mine().items.single().id)
    }

    @Test fun `mine scopes execution before pagination including ALL view and narrower execute grants`() {
        val otherBranch = UUID.randomUUID()
        jdbc.update("""insert into merch_branches(id,source_system,source_origin,source_id,source_payload,organization_id,epharm_pharmacy_id,
            pharmacy_binding_verified,code,name,address,city,active,created_at,updated_at)
            select ?,'EPHARM','https://epharm.inkar.kz',?,'{}',organization_id,null,
            false,'002','Other synthetic','','Synthetic',true,now(),now() from merch_branches where id=?""", otherBranch, otherBranch, branch)
        val first = personallyAssignedSource().first
        val second = personallyAssignedSource().first
        val outside = personallyAssignedSource().first
        jdbc.update("update merch_task_dispatches set created_at=now()-interval '2 hours',title='Personal first' where id=?", first.id)
        jdbc.update("update merch_task_dispatches set created_at=now()-interval '1 hour',title='Personal second' where id=?", second.id)
        jdbc.update("update merch_task_dispatches set branch_id=?,title='Personal outside' where id=?", otherBranch, outside.id)
        fun scopes(viewAll: Boolean, executeAll: Boolean) {
            val permissions = listOf(
                StoredGrant("merchandising", "tasks", "view", allMerchBranches = viewAll,
                    merchBranchIds = if (viewAll) emptySet() else setOf(branch.toString())),
                StoredGrant("merchandising", "tasks", "execute", allMerchBranches = executeAll,
                    merchBranchIds = if (executeAll) emptySet() else setOf(branch.toString())),
            )
            jdbc.update("update unified_admin_profiles set grants_json=?::jsonb,version=version+1 where account_id=?", json.writeValueAsString(permissions), admin.id)
        }
        scopes(viewAll = true, executeAll = false)
        val pageOne = mine(limit = 1)
        assertEquals(listOf(second.id), pageOne.items.map { it.id }); assertTrue(pageOne.hasMore)
        val pageTwo = mine(limit = 1, offset = 1)
        assertEquals(listOf(first.id), pageTwo.items.map { it.id }); assertFalse(pageTwo.hasMore)
        assertTrue(mine(limit = 1, offset = 2).items.isEmpty())
        assertEquals(listOf(first.id), mine(query = "first").items.map { it.id })
        scopes(viewAll = false, executeAll = true)
        assertEquals(setOf(first.id, second.id), mine().items.map { it.id }.toSet())
        scopes(viewAll = true, executeAll = true)
        assertEquals(3, mine().items.size)
    }

    @Test fun `mine uses only the current attempt and never past participation or another current executor`() {
        val (task, _) = personallyAssignedSource(count = 2)
        val item = task.items.first()
        val own = start(item.id, adminAuth, false)
        jdbc.update("update merch_task_items set source_assignee_id=null where dispatch_id=?", task.id)
        assertEquals(item.id, mine().items.single().nextExecutionItemId)
        jdbc.update("update merch_task_attempts set status='revision_required' where id=?", own.id)
        jdbc.update("""insert into merch_task_attempts(id,item_id,attempt_no,executor_person_id,status,created_at)
            values(?,?,2,?,'draft',now())""", UUID.randomUUID(), item.id, pharmacistPerson)
        jdbc.update("update merch_task_items set current_attempt=2 where id=?", item.id)
        assertTrue(mine().items.isEmpty())
        // A source assignment must not select an item whose current attempt belongs to someone else.
        val source = jdbc.queryForObject("select source_mass_owner_id from merch_task_dispatches where id=?", UUID::class.java, task.id)
        jdbc.update("update merch_task_items set source_assignee_id=?,status='revision_required' where id=?", source, item.id)
        assertTrue(mine().items.isEmpty())
        val secondItem = task.items.last()
        start(secondItem.id, adminAuth, false)
        assertEquals(secondItem.id, mine().items.single().nextExecutionItemId)
        jdbc.update("update merch_task_items set status='blocked' where id=?", secondItem.id)
        assertEquals(secondItem.id, mine().items.single().nextExecutionItemId)
        jdbc.update("update merch_task_items set status='submitted' where id=?", secondItem.id)
        assertTrue(mine().items.isEmpty())
    }

    @Test fun `mine includes only the confirmed personal monthly executor and respects foreign monthly ownership`() {
        val task = create(amount = 0, count = 1)
        jdbc.update("""insert into merch_monthly_visits(branch_id,placement_month,executor_person_id,dispatch_id,created_at)
            values(?,date_trunc('month',current_date)::date,?,?,now())""", branch, pharmacistPerson, task.id)
        assertTrue(mine().items.isEmpty())
        jdbc.update("update merch_monthly_visits set executor_person_id=? where dispatch_id=?", adminPerson, task.id)
        assertEquals(task.items.single().id, mine().items.single().nextExecutionItemId)
        grants(reviewer.id, "view", "execute")
        assertTrue(mine(auth = reviewerAuth).items.isEmpty())
        val (sourceTask, _) = personallyAssignedSource()
        jdbc.update("""insert into merch_monthly_visits(branch_id,placement_month,executor_person_id,dispatch_id,created_at)
            values(?,date_trunc('month',current_date)::date,?,?,now())""", branch, pharmacistPerson, sourceTask.id)
        assertEquals(listOf(task.id), mine().items.map { it.id })
    }

    @Test fun `mine never adopts anonymous QR attempts or shared staff visits as personal work`() {
        val (task, _) = personallyAssignedSource()
        val item = task.items.single().id
        val anonymous = capStart(item, nativeLink(task.id))
        assertNull(anonymous.executorPersonId)
        assertTrue(mine().items.isEmpty())
        jdbc.update("update merch_task_attempts set status='revision_required' where id=?", anonymous.id)
        jdbc.update("update merch_task_items set status='revision_required' where id=?", item)
        assertTrue(mine().items.isEmpty()) // Source assignment does not claim a prior anonymous attempt.

        val (shared, _) = personallyAssignedSource()
        jdbc.update("""insert into merch_staff_visits(id,branch_id,placement_month,dispatch_id,employee_name,created_at,updated_at)
            values(?,?,date_trunc('month',current_date)::date,?,'Synthetic author',now(),now())""",
            UUID.randomUUID(), branch, shared.id)
        assertTrue(mine().items.isEmpty()) // A matching display name and branch are not verified identity.
    }

    @Test fun `mine excludes terminal future and unresolved legacy work and selects the next eligible item`() {
        val (task, _) = personallyAssignedSource(count = 2)
        jdbc.update("update merch_task_items set status='completed' where id=?", task.items.first().id)
        val actionable = task.items.last().id
        assertEquals(actionable, mine().items.single().nextExecutionItemId)
        for (status in listOf("in_progress", "submitted", "blocked", "cancelled")) {
            jdbc.update("update merch_task_items set status=? where id=?", status, actionable)
            assertTrue(mine().items.isEmpty(), "Raw legacy $status cannot become a fresh personal attempt")
        }
        jdbc.update("update merch_task_items set status='assigned',available_from=now()+interval '1 hour' where id=?", actionable)
        assertTrue(mine().items.isEmpty())
        jdbc.update("update merch_task_items set available_from=null where id=?", actionable)
        for (status in listOf("completed", "cancelled")) {
            jdbc.update("update merch_task_dispatches set status=? where id=?", status, task.id)
            assertTrue(mine().items.isEmpty())
        }
        jdbc.update("update merch_task_dispatches set status='ready',available_from=now()+interval '1 hour' where id=?", task.id)
        assertTrue(mine().items.isEmpty())
        jdbc.update("update merch_task_dispatches set available_from=null where id=?", task.id)
        jdbc.update("update merch_branches set active=false where id=?", branch)
        assertTrue(mine().items.isEmpty())
    }

    @Test fun `mine can navigate an explicitly resumed source assignment without claiming historical executor identity`() {
        val (task, _) = personallyAssignedSource()
        val item = task.items.single().id
        val preserved = UUID.randomUUID()
        jdbc.update("""insert into merch_task_attempts(id,item_id,attempt_no,status,created_at,source_payload)
            values(?,?,1,'source_preserved',now(),'{"kind":"epharm.source-task-continuation.v1"}')""", preserved, item)
        jdbc.update("update merch_task_items set current_attempt=1,status='revision_required' where id=?", item)
        assertEquals(item, mine().items.single().nextExecutionItemId)
        val fresh = start(item, adminAuth, false)
        assertEquals(2, fresh.number)
        assertNull(jdbc.queryForObject("select executor_person_id from merch_task_attempts where id=?", UUID::class.java, preserved))
        assertEquals(adminPerson, fresh.executorPersonId)
    }

    @Test fun `creator can review another verified executor and only complete required packet credits one dispatch once`() {
        val d=create(); binding(d)
        for ((index,item) in d.items.withIndex()) {
            val a=start(item.id); upload(item.id); submit(item,a)
            val before=get(d.id).items.single { it.id==item.id }
            val req=ReviewMerchTaskItem(a.id,before.version,"approve","Verified this concrete shelf")
            repeat(2) { mvc.perform(post("/api/admin/merch/tasks/items/${item.id}/reviews").header("Authorization",adminAuth).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(req))).andExpect(status().isOk) }
            assertEquals(if(index==0) 500L else 800L,jdbc.queryForObject("select balance from pharmacists where id='task-pharmacist'",Long::class.java))
        }
        assertEquals(1,jdbc.queryForObject("select count(*) from pharmacist_wallet_entries where source='merch_task'",Int::class.java))
        assertEquals(1,jdbc.queryForObject("select count(*) from merch_task_rewards where state='credited'",Int::class.java))
        assertEquals("completed",get(d.id).status)
    }

    @Test fun `revision keeps prior photos and review immutable and next attempt cannot reuse old evidence`() {
        val d=create(count=1); binding(d); val item=d.items.single()
        val a=start(item.id); val oldPhoto=upload(item.id); submit(item,a); review(d.id,item.id,"revision",reviewerAuth)
        val next=start(item.id)
        mvc.perform(post("/api/mobile/merch/tasks/items/${item.id}/submit").header("Authorization",mobileAuth).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsBytes(SubmitMerchTaskAttempt(next.number,item.checklist.map { it.id }.toSet())))).andExpect(status().isBadRequest)
        val newPhoto=upload(item.id); assertNotEquals(oldPhoto.id,newPhoto.id); submit(item,next); review(d.id,item.id)
        val history=json.readValue(mvc.perform(get("/api/admin/merch/tasks/items/${item.id}/attempts").header("Authorization",adminAuth))
            .andExpect(status().isOk).andReturn().response.contentAsByteArray,MerchTaskAttemptPage::class.java)
        assertEquals(2,history.items.size); assertEquals(setOf("approved","revision_required"),history.items.map { it.status }.toSet())
        assertEquals(2,jdbc.queryForObject("select count(*) from merch_task_evidence",Int::class.java))
        assertEquals(2,jdbc.queryForObject("select count(*) from merch_task_reviews",Int::class.java))
    }

    @Test fun `MAS can execute but own person or account alias cannot review and authoring itself does not block another reviewer`() {
        val d=create(amount=0,count=1); val item=d.items.single()
        val a=start(item.id,adminAuth,false); upload(item.id,auth=adminAuth,mobile=false); submit(item,a,adminAuth,false)
        val current=get(d.id).items.single(); val req=ReviewMerchTaskItem(a.id,current.version,"approve","Reviewed concrete evidence")
        mvc.perform(post("/api/admin/merch/tasks/items/${item.id}/reviews").header("Authorization",adminAuth).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsBytes(req))).andExpect(status().isForbidden)
        mvc.perform(post("/api/admin/merch/tasks/items/${item.id}/reviews").header("Authorization",adminAuth).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsBytes(req.copy(manualUnverifiedExecutor=true)))).andExpect(status().isForbidden)
        jdbc.update("update unified_admin_profiles set person_id=? where account_id=?",adminPerson,reviewer.id)
        mvc.perform(post("/api/admin/merch/tasks/items/${item.id}/reviews").header("Authorization",reviewerAuth).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsBytes(req))).andExpect(status().isForbidden)
    }

    @Test fun `JWT names QR possession and wrong financial binding do not prove executor identity`() {
        val d=create(count=1); val item=d.items.single()
        mvc.perform(post("/api/mobile/merch/tasks/items/${item.id}/attempts").header("Authorization",mobileAuth)).andExpect(status().isForbidden)
        binding(d,amount=299)
        mvc.perform(post("/api/mobile/merch/tasks/items/${item.id}/attempts").header("Authorization",mobileAuth)).andExpect(status().isForbidden)
        assertEquals(0,jdbc.queryForObject("select count(*) from merch_task_attempts",Int::class.java))
        assertEquals(500L,jdbc.queryForObject("select balance from pharmacists where id='task-pharmacist'",Long::class.java))
    }

    @Test fun `transfer after on time submission holds only this reward without rewriting recipient or other wallet money`() {
        val d=create(count=1); binding(d); val item=d.items.single(); val a=start(item.id); upload(item.id); submit(item,a)
        jdbc.update("update pharmacists set pharmacy_id='task-other' where id='task-pharmacist'")
        val result=review(d.id,item.id)
        assertEquals("completed",result.status); assertEquals("held",result.reward!!.state)
        assertEquals(500L,jdbc.queryForObject("select balance from pharmacists where id='task-pharmacist'",Long::class.java))
        assertEquals(0,jdbc.queryForObject("select count(*) from pharmacist_wallet_entries",Int::class.java))
    }

    @Test fun `source preserved approval never replays wallet credit or an old outbox`() {
        val d=create(count=1); binding(d)
        jdbc.update("update merch_task_dispatches set source_system='MERCH_CRM',source_origin='https://crm.inkar.kz',historical_reward_preserved=true where id=?",d.id)
        val item=d.items.single(); val a=start(item.id); upload(item.id); submit(item,a)
        assertEquals("held",review(d.id,item.id).reward!!.state)
        assertEquals(0,jdbc.queryForObject("select count(*) from pharmacist_wallet_entries",Int::class.java))
    }

    @Test fun `paid final approval rechecks the same current HQ audited recipient mapping and holds revoked proof without credit`() {
        val d=create(count=1);binding(d);val item=d.items.single();val a=start(item.id);upload(item.id);submit(item,a)
        jdbc.update("update posm_pharmacist_mappings set active=false,revoked_at=now() where pharmacist_id='task-pharmacist'")
        review(d.id,item.id)
        assertEquals("recipient_HQ_mapping_no_longer_verified",get(d.id).reward!!.reason)
        assertEquals("held",get(d.id).reward!!.state)
        assertEquals(500L,jdbc.queryForObject("select balance from pharmacists where id='task-pharmacist'",Long::class.java))
        assertEquals(0,jdbc.queryForObject("select count(*) from pharmacist_wallet_entries where source='merch_task'",Int::class.java))
    }

    @Test fun `upload replay has one private file record and scoped download authenticates before returning exact bytes`() {
        val d=create(count=1); binding(d); val item=d.items.single(); start(item.id)
        val id=UUID.randomUUID(); val first=upload(item.id,id); assertEquals(first.id,upload(item.id,id).id)
        assertEquals(1,jdbc.queryForObject("select count(*) from merch_task_evidence",Int::class.java))
        mvc.perform(get("/api/admin/merch/tasks/evidence/${first.id}").header("Authorization",adminAuth)).andExpect(status().isOk)
            .andExpect(header().string("Cache-Control","no-store")).andExpect(header().string("X-Content-SHA256",TaskEvidenceStorage.hash(photo)))
            .andExpect(content().bytes(photo))
        grants(admin.id,"create")
        mvc.perform(get("/api/admin/merch/tasks/evidence/${first.id}").header("Authorization",adminAuth)).andExpect(status().isForbidden)
        mvc.perform(get("/api/mobile/merch/tasks/evidence/${first.id}").header("Authorization",adminAuth)).andExpect(status().isForbidden)
    }

    @Test fun `unfinished CRM packet can earn once after a new verified review in its original dispatch namespace`() {
        val d=create(count=1); binding(d)
        jdbc.update("update merch_task_dispatches set source_system='MERCH_CRM',source_origin='https://crm.inkar.kz' where id=?",d.id)
        val item=d.items.single();val a=start(item.id);upload(item.id);submit(item,a)
        val result=review(d.id,item.id)
        assertEquals("credited",result.reward!!.state)
        assertEquals(d.id.toString(),jdbc.queryForObject("select source_id from pharmacist_wallet_entries where source='merch_task'",String::class.java))
        assertEquals(800L,jdbc.queryForObject("select balance from pharmacists where id='task-pharmacist'",Long::class.java))
    }

    @Test fun `matching prior wallet posting is verified without another credit or rewriting its legacy context`() {
        val d=create(count=1);binding(d)
        val old=wallet.credit(WalletCreditSource.MERCH_TASK,d.id.toString(),"task-pharmacist",300,"approval:synthetic-legacy-context")
        val item=d.items.single();val a=start(item.id);upload(item.id);submit(item,a)
        val result=review(d.id,item.id)
        assertEquals("already_credited",result.reward!!.state);assertEquals(old.entryId,result.reward!!.walletEntryId)
        assertEquals(800L,jdbc.queryForObject("select balance from pharmacists where id='task-pharmacist'",Long::class.java))
        assertEquals(1,jdbc.queryForObject("select count(*) from pharmacist_wallet_entries",Int::class.java))
        assertEquals("approval:synthetic-legacy-context",jdbc.queryForObject("select context_key from pharmacist_wallet_entries",String::class.java))
    }

    @Test fun `conflicting prior wallet recipient is held without adopting or crediting another persons money`() {
        val d=create(count=1);binding(d)
        jdbc.update("""insert into pharmacists(id,name,iin,phone,pharmacy_id,city,tier,status,joined_at)
            values('task-foreign','Synthetic other','000000000002','+77000000002','task-other','Synthetic','Silver','active',current_date)""")
        wallet.credit(WalletCreditSource.MERCH_TASK,d.id.toString(),"task-foreign",300,"approval:synthetic-foreign")
        val item=d.items.single();val a=start(item.id);upload(item.id);submit(item,a)
        val result=review(d.id,item.id)
        assertEquals("held",result.reward!!.state);assertNull(result.reward!!.walletEntryId)
        assertEquals(500L,jdbc.queryForObject("select balance from pharmacists where id='task-pharmacist'",Long::class.java))
        assertEquals(300L,jdbc.queryForObject("select balance from pharmacists where id='task-foreign'",Long::class.java))
        assertEquals(1,jdbc.queryForObject("select count(*) from pharmacist_wallet_entries",Int::class.java))
    }

    @Test fun `conflicting prior wallet amount is held and original posting remains unchanged`() {
        val d=create(count=1);binding(d)
        wallet.credit(WalletCreditSource.MERCH_TASK,d.id.toString(),"task-pharmacist",299,"approval:synthetic-old-amount")
        val item=d.items.single();val a=start(item.id);upload(item.id);submit(item,a)
        assertEquals("held",review(d.id,item.id).reward!!.state)
        assertEquals(799L,jdbc.queryForObject("select balance from pharmacists where id='task-pharmacist'",Long::class.java))
        assertEquals(299L,jdbc.queryForObject("select amount_kzt from pharmacist_wallet_entries",Long::class.java))
    }

    @Test fun `packet accommodates observed source maximum of 27 items without a lower native limit`() {
        val d=create(amount=0,count=27)
        assertEquals(27,d.itemCount);assertEquals(27,d.items.size)
    }

    @Test fun `packet keeps all 500 placement items and rejects 501 without splitting or truncating`() {
        val packet=create(amount=0,count=500)
        assertEquals(500,packet.itemCount);assertEquals(500,packet.items.size)
        val tooMany=CreateMerchTaskDispatch(branch,"Oversized native packet",Instant.now().plusSeconds(3600),items=
            (1..501).map {CreateMerchTaskItem("Concrete placement $it")})
        mvc.perform(post("/api/admin/merch/tasks").header("Authorization",adminAuth).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsBytes(tooMany))).andExpect(status().isBadRequest)
        assertEquals(1,jdbc.queryForObject("select count(*) from merch_task_dispatches",Int::class.java))
        assertEquals(500,jdbc.queryForObject("select count(*) from merch_task_items",Int::class.java))
    }

    @Test fun `changed legacy evidence size fails closed without deleting its original file`() {
        val d=create(amount=0,count=1);val item=d.items.single();val id=UUID.randomUUID()
        val key="synthetic-legacy-$id.png";Files.write(files.resolve(key),photo)
        jdbc.update("""insert into merch_task_evidence(id,item_id,storage_backend,object_key,mime_type,byte_size,created_at)
            values(?,?,'legacy_reference',?,'image/png',?,now())""",id,item.id,key,photo.size+1L)
        mvc.perform(get("/api/admin/merch/tasks/evidence/$id").header("Authorization",adminAuth)).andExpect(status().isConflict)
        assertTrue(Files.exists(files.resolve(key)));assertArrayEquals(photo,Files.readAllBytes(files.resolve(key)))
    }

    @Test fun `tiny PNG with gigantic header dimensions is rejected before bitmap allocation or storage`() {
        val d=create(count=1);binding(d);val item=d.items.single();start(item.id)
        val bomb=photo.copyOf()
        java.nio.ByteBuffer.wrap(bomb,16,8).putInt(100_000).putInt(100_000)
        val crc=CRC32().apply { update(bomb,12,17) }
        java.nio.ByteBuffer.wrap(bomb,29,4).putInt(crc.value.toInt())
        assertTrue(bomb.size<1024)
        mvc.perform(multipart("/api/mobile/merch/tasks/items/${item.id}/evidence")
            .file(MockMultipartFile("file","tiny-header.png","image/png",bomb)).param("clientUploadId",UUID.randomUUID().toString())
            .header("Authorization",mobileAuth)).andExpect(status().isPayloadTooLarge)
        assertEquals(0,jdbc.queryForObject("select count(*) from merch_task_evidence",Int::class.java))
    }

    @Test fun `PNG header without image data is rejected before becoming photo evidence`() {
        val d=create(count=1);binding(d);val item=d.items.single();start(item.id)
        val storedBefore=Files.walk(files).use { paths -> paths.filter { Files.isRegularFile(it) }.count() }
        val truncated=photo.copyOf(33) // Complete signature and IHDR, no IDAT pixels or IEND.
        mvc.perform(multipart("/api/mobile/merch/tasks/items/${item.id}/evidence")
            .file(MockMultipartFile("file","truncated.png","image/png",truncated)).param("clientUploadId",UUID.randomUUID().toString())
            .header("Authorization",mobileAuth)).andExpect(status().isBadRequest)
        assertEquals(0,jdbc.queryForObject("select count(*) from merch_task_evidence",Int::class.java))
        assertEquals(storedBefore,Files.walk(files).use { paths -> paths.filter { Files.isRegularFile(it) }.count() })
        // A failed decode releases admission and leaves the same attempt usable.
        upload(item.id)
        assertEquals(1,jdbc.queryForObject("select count(*) from merch_task_evidence",Int::class.java))
    }

    @Test fun `revocation committed before actor lock defeats a request with a previously loaded permission snapshot`() {
        val (item,req)=submittedReview()
        val held=CountDownLatch(1);val release=CountDownLatch(1);val executor=Executors.newFixedThreadPool(2)
        val revoke=executor.submit { TransactionTemplate(transactionManager).executeWithoutResult {
            jdbc.queryForList("select id from admin_users where id=? for update",admin.id)
            held.countDown();check(release.await(15,TimeUnit.SECONDS))
            jdbc.update("update unified_admin_profiles set grants_json='[]',version=version+1 where account_id=?",admin.id)
        } }
        try {
            assertTrue(held.await(10,TimeUnit.SECONDS))
            val request=executor.submit<Int> { sendReview(item,req) }
            awaitLock("admin_users")
            release.countDown();revoke.get(15,TimeUnit.SECONDS)
            assertEquals(403,request.get(15,TimeUnit.SECONDS))
            assertEquals(0,jdbc.queryForObject("select count(*) from merch_task_reviews",Int::class.java))
            assertEquals(500L,jdbc.queryForObject("select balance from pharmacists where id='task-pharmacist'",Long::class.java))
        } finally { release.countDown();executor.shutdownNow() }
    }

    @Test fun `writer holding actor lock commits once before a subsequent revocation and revocation then denies later review`() {
        val (item,req)=submittedReview()
        val dispatch=jdbc.queryForObject("select dispatch_id from merch_task_items where id=?",UUID::class.java,item)!!
        val held=CountDownLatch(1);val release=CountDownLatch(1);val executor=Executors.newFixedThreadPool(3)
        val domain=executor.submit { TransactionTemplate(transactionManager).executeWithoutResult {
            jdbc.queryForList("select id from merch_task_dispatches where id=? for update",dispatch)
            held.countDown();check(release.await(15,TimeUnit.SECONDS))
        } }
        try {
            assertTrue(held.await(10,TimeUnit.SECONDS))
            val request=executor.submit<Int> { sendReview(item,req) }
            awaitLock("merch_task_dispatches")
            val revoke=executor.submit { TransactionTemplate(transactionManager).executeWithoutResult {
                jdbc.queryForList("select id from admin_users where id=? for update",admin.id)
                jdbc.update("update unified_admin_profiles set grants_json='[]',version=version+1 where account_id=?",admin.id)
            } }
            awaitLock("admin_users");assertFalse(revoke.isDone)
            release.countDown();domain.get(15,TimeUnit.SECONDS)
            assertEquals(200,request.get(15,TimeUnit.SECONDS));revoke.get(15,TimeUnit.SECONDS)
            assertEquals(1,jdbc.queryForObject("select count(*) from merch_task_reviews",Int::class.java))
            assertEquals(800L,jdbc.queryForObject("select balance from pharmacists where id='task-pharmacist'",Long::class.java))
            assertEquals(403,sendReview(item,req))
        } finally { release.countDown();executor.shutdownNow() }
    }

    @Test fun `mobile execution rereads pharmacist status after waiting on a domain lock`() {
        val d=create(count=1);binding(d);val held=CountDownLatch(1);val release=CountDownLatch(1)
        val executor=Executors.newFixedThreadPool(2)
        val domain=executor.submit { TransactionTemplate(transactionManager).executeWithoutResult {
            jdbc.queryForList("select id from merch_task_dispatches where id=? for update",d.id)
            held.countDown();check(release.await(15,TimeUnit.SECONDS))
        } }
        try {
            assertTrue(held.await(10,TimeUnit.SECONDS))
            val request=executor.submit<Int> { mvc.perform(post("/api/mobile/merch/tasks/items/${d.items.single().id}/attempts")
                .header("Authorization",mobileAuth)).andReturn().response.status }
            awaitLock("merch_task_dispatches")
            jdbc.update("update pharmacists set status='blocked' where id='task-pharmacist'")
            release.countDown();domain.get(15,TimeUnit.SECONDS)
            assertEquals(403,request.get(15,TimeUnit.SECONDS))
            assertEquals(0,jdbc.queryForObject("select count(*) from merch_task_attempts",Int::class.java))
        } finally { release.countDown();executor.shutdownNow() }
    }

    @Test fun `branch picker scopes by task view and create eligibility requires branch permission verified person and active branch`() {
        mvc.perform(get("/api/admin/merch/tasks/branches").header("Authorization",adminAuth)).andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(branch.toString()))
            .andExpect(jsonPath("$[0].createEligible").value(true)).andExpect(jsonPath("$[0].canConfigureReward").value(true))
        grants(admin.id,"view","create")
        mvc.perform(get("/api/admin/merch/tasks/branches").header("Authorization",adminAuth)).andExpect(status().isOk)
            .andExpect(jsonPath("$[0].createEligible").value(true)).andExpect(jsonPath("$[0].canConfigureReward").value(false))
        grants(admin.id,"view")
        mvc.perform(get("/api/admin/merch/tasks/branches").header("Authorization",adminAuth)).andExpect(status().isOk)
            .andExpect(jsonPath("$[0].createEligible").value(false))
        jdbc.update("delete from unified_admin_profiles where account_id=?",admin.id)
        mvc.perform(get("/api/admin/merch/tasks/branches").header("Authorization",adminAuth)).andExpect(status().isForbidden)
        jdbc.update("insert into unified_admin_profiles(account_id,person_id) values(?,?)",admin.id,adminPerson)
        grants(admin.id,"view","create")
        jdbc.update("update merch_branches set active=false where id=?",branch)
        mvc.perform(get("/api/admin/merch/tasks/branches").header("Authorization",adminAuth)).andExpect(status().isOk)
            .andExpect(jsonPath("$[0].active").value(false)).andExpect(jsonPath("$[0].createEligible").value(false))
    }

    @Test fun `per item capabilities follow current executor person policy status and retain submitted checklist and comment`() {
        val d=create(count=1);binding(d);val item=d.items.single()
        assertTrue(get(d.id).items.single().canStart)
        fun mobile()=json.readValue(mvc.perform(get("/api/mobile/merch/tasks/${d.id}").header("Authorization",mobileAuth))
            .andExpect(status().isOk).andReturn().response.contentAsByteArray,MerchTaskDispatchDto::class.java).items.single()
        assertTrue(mobile().canStart)
        val a=start(item.id)
        assertFalse(get(d.id).items.single().canUpload);assertTrue(mobile().canUpload);assertFalse(mobile().canSubmit)
        upload(item.id);assertTrue(mobile().canSubmit)
        val completed=item.checklist.map { it.id }.toSet()
        mvc.perform(post("/api/mobile/merch/tasks/items/${item.id}/submit").header("Authorization",mobileAuth).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsBytes(SubmitMerchTaskAttempt(a.number,completed,"Concrete shelf checked")))).andExpect(status().isOk)
        val submitted=get(d.id).items.single()
        assertTrue(submitted.canReview);assertFalse(submitted.canUpload);assertFalse(submitted.canSubmit);assertFalse(submitted.canStart)
        assertEquals(completed,submitted.currentAttempt!!.completedChecklistIds);assertEquals("Concrete shelf checked",submitted.currentAttempt.comment)
        assertFalse(mobile().canReview)
        jdbc.update("update unified_admin_profiles set person_id=? where account_id=?",pharmacistPerson,admin.id)
        assertFalse(get(d.id).items.single().canReview)
        jdbc.update("update unified_admin_profiles set person_id=? where account_id=?",adminPerson,admin.id)
        grants(admin.id,"view");assertFalse(get(d.id).items.single().canReview)
    }

    @Test fun `native wallet posting enters scoped ledger once and conflicting legacy pharmacy cannot be guessed or double summed`() {
        val d=create(count=1);binding(d);val item=d.items.single();val a=start(item.id);upload(item.id);submit(item,a);review(d.id,item.id)
        fun ledger(all:Boolean)=get("/api/admin/reports/ledger").header("Authorization",adminAuth).param("from",java.time.LocalDate.now(java.time.ZoneId.of("Asia/Almaty")).toString())
            .param("to",java.time.LocalDate.now(java.time.ZoneId.of("Asia/Almaty")).toString()).param("source","merch_task").param("pharmacistId","task-pharmacist").also {
                jdbc.update("update unified_admin_profiles set grants_json=?::jsonb,version=version+1 where account_id=?",json.writeValueAsString(listOf(
                    StoredGrant("motivation","accruals","view",all,if(all) emptySet() else setOf("task-pharmacy")))),admin.id)
            }
        mvc.perform(ledger(false)).andExpect(status().isOk).andExpect(jsonPath("$.entries.length()").value(1))
            .andExpect(jsonPath("$.entries[0].amountKzt").value(300)).andExpect(jsonPath("$.entries[0].historicalPharmacyId").value("task-pharmacy"))
            .andExpect(jsonPath("$.entries[0].basisStatus").value("credited")).andExpect(jsonPath("$.entries[0].balanceAfterKzt").isEmpty)
            .andExpect(jsonPath("$.unresolvedHistoricalRows").isEmpty)
        val walletId=jdbc.queryForObject("select wallet_entry_id from merch_task_rewards where dispatch_id=?",UUID::class.java,d.id)
        val bindingId=jdbc.queryForObject("select id from merch_executor_bindings where dispatch_id=?",UUID::class.java,d.id)
        jdbc.update("""insert into merch_reward_events(event_id,dispatch_id,binding_id,pharmacist_id,pharmacy_id,amount_kzt,
            submitted_at,approved_at,approved_by_crm_user_id,payload_hash,wallet_entry_id)
            values(?,?,?,'task-pharmacist','task-other',300,now(),now(),?,repeat('a',64),?)""",UUID.randomUUID(),d.id,bindingId,UUID.randomUUID(),walletId)
        mvc.perform(ledger(true)).andExpect(status().isOk).andExpect(jsonPath("$.entries.length()").value(1))
            .andExpect(jsonPath("$.entries[0].historicalPharmacyId").isEmpty).andExpect(jsonPath("$.entries[0].historicalPharmacyStatus").value("conflicting_source_bases"))
            .andExpect(jsonPath("$.totals[0].amountKzt").value(300)).andExpect(jsonPath("$.unresolvedHistoricalRows").value(1))
        mvc.perform(ledger(false)).andExpect(status().isOk).andExpect(jsonPath("$.entries").isEmpty).andExpect(jsonPath("$.unresolvedHistoricalRows").isEmpty)
        assertEquals(1,jdbc.queryForObject("select count(*) from pharmacist_wallet_entries where source='merch_task'",Int::class.java))
    }

    @Test fun `free capability submits real photo without person and HQ manual revision retains history and never credits money`() {
        val d=create(amount=0,count=1);val item=d.items.single();val token=nativeLink(d.id)
        fun publicTask()=json.readValue(mvc.perform(get("/api/public/merch/tasks").header("X-Merch-Task-Token",token))
            .andExpect(status().isOk).andReturn().response.contentAsByteArray,MerchTaskDispatchDto::class.java)
        assertTrue(publicTask().items.single().canStart)
        val a=capStart(item.id,token);assertNull(a.executorPersonId);assertEquals("capability_unverified",a.executorIdentityStatus)
        val file=capUpload(item.id,token)
        mvc.perform(get("/api/public/merch/tasks/evidence/${file.id}").header("X-Merch-Task-Token",token))
            .andExpect(status().isOk).andExpect(header().string("X-Content-SHA256",file.sha256!!)).andExpect(content().bytes(photo))
        capSubmit(item,a,token)
        var current=get(d.id).items.single()
        val revise=ReviewMerchTaskItem(a.id,current.version,"revision","Retake the concrete shelf photo",true)
        mvc.perform(post("/api/admin/merch/tasks/items/${item.id}/reviews").header("Authorization",adminAuth).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsBytes(revise))).andExpect(status().isOk)
        val publicReview=publicTask().items.single().currentAttempt!!.review!!
        assertNull(publicReview.reviewerAccountId);assertNull(publicReview.reviewerPersonId);assertEquals(revise.reason,publicReview.reason)
        val next=capStart(item.id,token);assertEquals(2,next.number)
        capUpload(item.id,token);capSubmit(item,next,token);current=get(d.id).items.single()
        mvc.perform(post("/api/admin/merch/tasks/items/${item.id}/reviews").header("Authorization",adminAuth).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsBytes(ReviewMerchTaskItem(next.id,current.version,"approve","HQ manually accepts unverified executor",true)))).andExpect(status().isOk)
        assertEquals(2,jdbc.queryForObject("select count(*) from merch_task_attempts where executor_person_id is null AND executor_account_id is null AND pharmacist_id is null AND capability_link_id is not null",Int::class.java))
        assertEquals(2,jdbc.queryForObject("select count(*) from merch_task_events where details->>'reviewMode'='manual_unverified_executor'",Int::class.java))
        assertEquals(2,jdbc.queryForObject("select count(*) from merch_task_evidence",Int::class.java))
        assertEquals(0,jdbc.queryForObject("select count(*) from pharmacist_wallet_entries",Int::class.java))
        assertEquals("not_monetary",jdbc.queryForObject("select state from merch_task_rewards",String::class.java))
    }

    @Test fun `paid capability cannot replace pharmacist proof or start upload submit or read private evidence`() {
        val d=create(count=1);val item=d.items.single();val token=nativeLink(d.id)
        mvc.perform(get("/api/public/merch/tasks").header("X-Merch-Task-Token",token)).andExpect(status().isForbidden)
        mvc.perform(post("/api/public/merch/tasks/items/${item.id}/attempts").header("X-Merch-Task-Token",token)).andExpect(status().isForbidden)
        mvc.perform(multipart("/api/public/merch/tasks/items/${item.id}/evidence").file(MockMultipartFile("file","photo.png","image/png",photo))
            .param("clientUploadId",UUID.randomUUID().toString()).header("X-Merch-Task-Token",token)).andExpect(status().isForbidden)
        mvc.perform(post("/api/public/merch/tasks/items/${item.id}/submit").header("X-Merch-Task-Token",token).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsBytes(SubmitMerchTaskAttempt(1,item.checklist.map { it.id }.toSet())))).andExpect(status().isForbidden)
        assertEquals(0,jdbc.queryForObject("select count(*) from merch_task_attempts",Int::class.java))
        assertEquals(0,jdbc.queryForObject("select count(*) from pharmacist_wallet_entries",Int::class.java))
    }

    @Test fun `capability cannot mutate another link attempt access neighbouring files or silently become a mobile executor`() {
        val d=create(amount=0,count=1);val item=d.items.single();val token=nativeLink(d.id);val second=nativeLink(d.id)
        capStart(item.id,token);val evidence=capUpload(item.id,token)
        mvc.perform(post("/api/public/merch/tasks/items/${item.id}/attempts").header("X-Merch-Task-Token",second)).andExpect(status().isForbidden)
        mvc.perform(get("/api/public/merch/tasks/evidence/${evidence.id}").header("X-Merch-Task-Token",second)).andExpect(status().isNotFound)
        mvc.perform(post("/api/mobile/merch/tasks/items/${item.id}/attempts").header("Authorization",mobileAuth)).andExpect(status().isForbidden)
        val neighbour=create(amount=0,count=1);val other=nativeLink(neighbour.id)
        mvc.perform(post("/api/public/merch/tasks/items/${item.id}/attempts").header("X-Merch-Task-Token",other)).andExpect(status().isNotFound)
        mvc.perform(get("/api/public/merch/tasks/evidence/${evidence.id}").header("X-Merch-Task-Token",other)).andExpect(status().isNotFound)
        mvc.perform(get("/api/public/merch/tasks").header("X-Merch-Task-Token","not-a-valid-capability")).andExpect(status().isUnauthorized)
        val secondView=json.readValue(mvc.perform(get("/api/public/merch/tasks").header("X-Merch-Task-Token",second))
            .andExpect(status().isOk).andReturn().response.contentAsByteArray,MerchTaskDispatchDto::class.java).items.single()
        assertNull(secondView.currentAttempt);assertFalse(secondView.canUpload);assertFalse(secondView.canSubmit)
        assertEquals(1,jdbc.queryForObject("select count(*) from merch_task_attempts",Int::class.java))
    }

    @Test fun `anonymous free review requires explicit manual choice HQ and grant rather than ordinary MAS or owner inference`() {
        val d=create(amount=0,count=1);val item=d.items.single();val token=nativeLink(d.id);val a=capStart(item.id,token)
        capUpload(item.id,token);capSubmit(item,a,token);val current=get(d.id).items.single()
        val request=ReviewMerchTaskItem(a.id,current.version,"approve","Manual review needs explicit HQ",true)
        mvc.perform(post("/api/admin/merch/tasks/items/${item.id}/reviews").header("Authorization",adminAuth).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsBytes(request.copy(manualUnverifiedExecutor=false)))).andExpect(status().isForbidden)
        jdbc.update("update admin_users set role='CATEGORY_LEAD' where id=?",admin.id)
        mvc.perform(post("/api/admin/merch/tasks/items/${item.id}/reviews").header("Authorization",adminAuth).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsBytes(request))).andExpect(status().isForbidden)
        jdbc.update("update admin_users set role='HQ_HEAD' where id=?",admin.id);grants(admin.id,"view")
        mvc.perform(post("/api/admin/merch/tasks/items/${item.id}/reviews").header("Authorization",adminAuth).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsBytes(request))).andExpect(status().isForbidden)
        assertEquals(0,jdbc.queryForObject("select count(*) from merch_task_reviews",Int::class.java))
    }

    @Test fun `capability mutation rereads revocation after waiting for dispatch lock before creating an attempt`() {
        val d=create(amount=0,count=1);val item=d.items.single();val token=nativeLink(d.id)
        val held=CountDownLatch(1);val release=CountDownLatch(1);val executor=Executors.newFixedThreadPool(2)
        val domain=executor.submit { TransactionTemplate(transactionManager).executeWithoutResult {
            jdbc.queryForList("select id from merch_task_dispatches where id=? for update",d.id)
            held.countDown();check(release.await(15,TimeUnit.SECONDS))
        } }
        try {
            assertTrue(held.await(10,TimeUnit.SECONDS))
            val request=executor.submit<Int> { mvc.perform(post("/api/public/merch/tasks/items/${item.id}/attempts")
                .header("X-Merch-Task-Token",token)).andReturn().response.status }
            awaitLock("merch_task_dispatches")
            jdbc.update("update merch_task_links set revoked_at=now() where token_hash=?",NativeCapabilityAuthorization.tokenHash(token))
            release.countDown();domain.get(15,TimeUnit.SECONDS)
            assertEquals(401,request.get(15,TimeUnit.SECONDS))
            assertEquals(0,jdbc.queryForObject("select count(*) from merch_task_attempts",Int::class.java))
        } finally { release.countDown();executor.shutdownNow() }
    }

    @Test fun `explicit source resume preserves original actor checklist file and attempt snapshot before real new executor starts`() {
        val source=sourceTask("in_progress",300)
        val before=get(source.first.id).items.single()
        assertTrue(before.canResumeSource);assertFalse(before.canAdoptSourceReview)
        val request=ContinueSourceTask(before.version,"resume","HQ preserves unfinished source attempt")
        val adopted=continueSource(before.id,request)
        assertEquals("revision_required",adopted.status)
        val preserved=get(source.first.id).items.single().currentAttempt!!
        assertEquals("source_preserved",preserved.status);assertNull(preserved.executorPersonId)
        assertEquals("source_preserved_unverified",preserved.executorIdentityStatus)
        assertEquals("Original source employee comment",preserved.comment)
        assertEquals(before.checklist.map {it.id}.toSet(),preserved.completedChecklistIds)
        assertEquals(source.second,jdbc.queryForObject("select source_payload::text from merch_task_items where id=?",String::class.java,before.id))
        assertEquals(0,jdbc.queryForObject("select count(*) from merch_task_evidence where attempt_id is not null",Int::class.java))
        assertArrayEquals(photo,Files.readAllBytes(source.third))
        binding(source.first)
        val fresh=start(before.id)
        assertEquals(2,fresh.number);assertEquals(pharmacistPerson,fresh.executorPersonId)
        assertEquals(2,jdbc.queryForObject("select count(*) from merch_task_attempts where item_id=?",Int::class.java,before.id))
        assertEquals(0,jdbc.queryForObject("select count(*) from pharmacist_wallet_entries",Int::class.java))
    }

    @Test fun `manual source submitted review is explicit HQ unknown identity and paid acceptance is always held without credit`() {
        for(amount in listOf(0L,300L)) {
            val source=sourceTask("submitted",amount);val item=get(source.first.id).items.single()
            assertTrue(item.canAdoptSourceReview)
            val adopted=continueSource(item.id,ContinueSourceTask(item.version,"review","HQ adopts preserved submitted evidence"))
            val current=get(source.first.id).items.single()
            assertTrue(current.canReview)
            val request=ReviewMerchTaskItem(adopted.attemptId,current.version,"approve","HQ accepts source evidence with unknown executor",manualSourcePreserved=true)
            mvc.perform(post("/api/admin/merch/tasks/items/${item.id}/reviews").header("Authorization",adminAuth).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(request.copy(manualSourcePreserved=false)))).andExpect(status().isForbidden)
            repeat(2) { mvc.perform(post("/api/admin/merch/tasks/items/${item.id}/reviews").header("Authorization",adminAuth).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(request))).andExpect(status().isOk) }
            val result=get(source.first.id)
            assertEquals("completed",result.items.single().status)
            assertEquals(if(amount==0L) "not_monetary" else "held",result.reward!!.state)
            if(amount>0) assertEquals("source_preserved_approval_requires_HQ_reconciliation",result.reward!!.reason)
            assertEquals(source.second,jdbc.queryForObject("select source_payload::text from merch_task_items where id=?",String::class.java,item.id))
            assertArrayEquals(photo,Files.readAllBytes(source.third))
        }
        assertEquals(0,jdbc.queryForObject("select count(*) from pharmacist_wallet_entries",Int::class.java))
        assertEquals(2,jdbc.queryForObject("select count(*) from merch_task_events where details->>'reviewMode'='manual_source_preserved'",Int::class.java))
    }

    @Test fun `source continuation denies MAS stale CAS invalid mode and modified preserved snapshot without inventing an executor`() {
        val source=sourceTask("submitted",300);val item=get(source.first.id).items.single()
        val request=ContinueSourceTask(item.version,"review","HQ must inspect original evidence")
        jdbc.update("update admin_users set role='CATEGORY_LEAD' where id=?",admin.id)
        sourceRequest(item.id,request).andExpect(status().isForbidden)
        jdbc.update("update admin_users set role='HQ_HEAD' where id=?",admin.id)
        sourceRequest(item.id,request.copy(expectedItemVersion=999)).andExpect(status().isConflict)
        sourceRequest(item.id,request.copy(mode="guess-person")).andExpect(status().isBadRequest)
        val adopted=continueSource(item.id,request)
        jdbc.update("update merch_task_attempts set source_payload=source_payload||'{\"tampered\":true}'::jsonb where id=?",adopted.attemptId)
        mvc.perform(post("/api/admin/merch/tasks/items/${item.id}/reviews").header("Authorization",adminAuth).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsBytes(ReviewMerchTaskItem(adopted.attemptId,adopted.itemVersion,"approve","Manual source review",manualSourcePreserved=true))))
            .andExpect(status().isConflict)
        assertEquals(0,jdbc.queryForObject("select count(*) from merch_task_reviews",Int::class.java))
        assertEquals(0,jdbc.queryForObject("select count(*) from merch_task_attempts where executor_person_id is not null",Int::class.java))
    }

    @Test fun `parallel source adoption has one history snapshot one CAS conflict and exact retry is idempotent`() {
        val source=sourceTask("submitted",0);val item=get(source.first.id).items.single()
        val requests=listOf(ContinueSourceTask(item.version,"review","HQ submission review first"),ContinueSourceTask(item.version,"resume","HQ asks for fresh execution"))
        val ready=CountDownLatch(2);val release=CountDownLatch(1);val executor=Executors.newFixedThreadPool(2)
        try {
            val futures=requests.map { request -> executor.submit<Pair<Int,Int>> {
                ready.countDown();check(release.await(10,TimeUnit.SECONDS));requests.indexOf(request) to sourceRequest(item.id,request).andReturn().response.status } }
            assertTrue(ready.await(10,TimeUnit.SECONDS));release.countDown()
            val results=futures.map {it.get(15,TimeUnit.SECONDS)}
            assertEquals(listOf(200,409),results.map {it.second}.sorted())
            val winner=requests[results.single {it.second==200}.first]
            assertTrue(continueSource(item.id,winner).duplicate)
            assertEquals(1,jdbc.queryForObject("select count(*) from merch_task_attempts where item_id=?",Int::class.java,item.id))
            assertEquals(1,jdbc.queryForObject("select count(*) from merch_task_events where item_id=? AND event_type='source_continuation'",Int::class.java,item.id))
        } finally {release.countDown();executor.shutdownNow()}
    }

    @Test fun `first authority gate serializes cross audited HQ reviewers before actor rows and both paid reviews commit without deadlock`() {
        val first=create(count=1);binding(first);val item1=first.items.single();val attempt1=start(item1.id);upload(item1.id);submit(item1,attempt1)
        val originalMapping=jdbc.queryForObject("select id from posm_pharmacist_mappings where pharmacist_id='task-pharmacist'",UUID::class.java)
        jdbc.update("""insert into posm_pharmacist_mapping_audit(mapping_id,action,pharmacy_id,external_user_id,pharmacist_id,actor_id)
            values(?,'reactivated','task-pharmacy','synthetic-posm-id','task-pharmacist',?)""",originalMapping,reviewer.id)
        val secondPerson=UUID.randomUUID();jdbc.update("insert into unified_persons values(?,?)",secondPerson,"synthetic-reviewed-proof")
        jdbc.update("""insert into pharmacists(id,name,iin,phone,pharmacy_id,pharmacy_name,city,tier,status,joined_at,balance)
            values('task-second','Synthetic','000000000002','+77000000002','task-pharmacy','Synthetic','Synthetic','Silver','active',current_date,100)""")
        jdbc.update("insert into pharmacist_person_links(pharmacist_id,person_id,verification_reference,verified_by) values('task-second',?,'synthetic-proof',?)",secondPerson,admin.id)
        val mapping2=UUID.randomUUID()
        jdbc.update("""insert into posm_pharmacist_mappings(id,pharmacy_id,external_user_id,pharmacist_id,created_by,updated_by)
            values(?,'task-pharmacy','synthetic-second-posm','task-second',?,?)""",mapping2,admin.id,admin.id)
        jdbc.update("""insert into posm_pharmacist_mapping_audit(mapping_id,action,pharmacy_id,external_user_id,pharmacist_id,actor_id)
            values(?,'created','task-pharmacy','synthetic-second-posm','task-second',?)""",mapping2,admin.id)
        val second=json.readValue(mvc.perform(post("/api/admin/merch/tasks").header("Authorization",adminAuth).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsBytes(CreateMerchTaskDispatch(branch,"Second independent paid task",Instant.now().plusSeconds(3600),300,"task-second",listOf(CreateMerchTaskItem("Second shelf"))))))
            .andExpect(status().isOk).andReturn().response.contentAsByteArray,MerchTaskDispatchDto::class.java)
        jdbc.update("""insert into merch_executor_bindings(id,dispatch_id,pharmacist_id,pharmacy_id,reward_kzt,task_due_at,verified_at)
            values(?,?,'task-second','task-pharmacy',300,?,now())""",UUID.randomUUID(),second.id,Timestamp.from(second.dueAt))
        val auth2="Bearer ${jwt.issuePharmacistToken("task-second","Synthetic","+77000000002")}"
        val item2=second.items.single();val attempt2=start(item2.id,auth2);upload(item2.id,auth=auth2);submit(item2,attempt2,auth2)
        val req1=ReviewMerchTaskItem(attempt1.id,get(first.id).items.single().version,"approve","HQ A checks mapping audited by HQ B")
        val req2=ReviewMerchTaskItem(attempt2.id,get(second.id).items.single().version,"approve","HQ B checks mapping audited by HQ A")
        val held=CountDownLatch(1);val release=CountDownLatch(1);val executor=Executors.newFixedThreadPool(3)
        val blocker=executor.submit {TransactionTemplate(transactionManager).executeWithoutResult {
            jdbc.queryForList("select id from merch_task_dispatches where id=? for update",first.id)
            held.countDown();check(release.await(20,TimeUnit.SECONDS))
        }}
        try {
            assertTrue(held.await(10,TimeUnit.SECONDS))
            val a=executor.submit<Int> {sendReview(item1.id,req1)}
            awaitLock("merch_task_dispatches")
            val b=executor.submit<Int> {mvc.perform(post("/api/admin/merch/tasks/items/${item2.id}/reviews").header("Authorization",reviewerAuth)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(req2))).andReturn().response.status}
            val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
            while(jdbc.queryForObject("""select count(*) from pg_stat_activity where datname=current_database() AND pid<>pg_backend_pid()
                AND wait_event_type='Lock' AND query ILIKE '%pg_advisory_xact_lock(817109%'""",Int::class.java)==0) {
                if(System.nanoTime()>until) fail<Unit>("Second reviewer did not wait on the first authority gate")
                Thread.sleep(20)
            }
            TransactionTemplate(transactionManager).executeWithoutResult {
                jdbc.queryForList("select id from admin_users where id=? for no key update nowait",reviewer.id)
            }
            assertFalse(b.isDone)
            release.countDown();blocker.get(15,TimeUnit.SECONDS)
            assertEquals(200,a.get(15,TimeUnit.SECONDS));assertEquals(200,b.get(15,TimeUnit.SECONDS))
            assertEquals(2,jdbc.queryForObject("select count(*) from pharmacist_wallet_entries where source='merch_task'",Int::class.java))
            assertEquals(800L,jdbc.queryForObject("select balance from pharmacists where id='task-pharmacist'",Long::class.java))
            assertEquals(400L,jdbc.queryForObject("select balance from pharmacists where id='task-second'",Long::class.java))
        } finally {release.countDown();executor.shutdownNow()}
    }

    private fun sourceRequest(item:UUID,request:ContinueSourceTask)=mvc.perform(post("/api/admin/merch/tasks/items/$item/source-continuation")
        .header("Authorization",adminAuth).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request)))
    private fun continueSource(item:UUID,request:ContinueSourceTask)=json.readValue(sourceRequest(item,request).andExpect(status().isOk)
        .andReturn().response.contentAsByteArray,SourceTaskContinuation::class.java)
    private fun sourceTask(status:String,reward:Long):Triple<MerchTaskDispatchDto,String,java.nio.file.Path> {
        val dispatch=UUID.randomUUID();val item=UUID.randomUUID();val checklist=UUID.randomUUID();val actor=UUID.randomUUID()
        val original=json.writeValueAsString(mapOf("id" to item,"status" to status,"assignee_id" to actor,"reviewer_id" to UUID.randomUUID(),
            "started_at" to "2026-10-01T10:00:00Z","submitted_at" to if(status=="submitted") "2026-10-01T10:01:00Z" else null,
            "employee_comment" to "Original source employee comment","_unification_checklist_source" to listOf(mapOf("id" to checklist,
                "title" to "Source checklist","required" to true,"completed_at" to "2026-10-01T10:00:30Z","completed_by" to actor))))
        jdbc.update("""insert into merch_task_dispatches(id,source_system,source_origin,source_id,source_payload,branch_id,title,status,due_at,
            reward_amount_kzt,source_mass_owner_id,created_at,updated_at) values(?,'MERCH_CRM','https://crm.inkar.kz',?,'{}',?,'Source preserved packet',?,now()+interval '1 hour',?,?,now(),now())""",
            dispatch,dispatch,branch,status,reward,actor)
        jdbc.update("""insert into merch_task_items(id,dispatch_id,source_id,source_payload,title,task_type,status,required,required_photo_count,
            checklist,source_assignee_id,created_at,updated_at) values(?,?,?,?::jsonb,'Source item','photo_report',?,true,1,?::jsonb,?,now(),now())""",
            item,dispatch,item,original,status,json.writeValueAsString(listOf(TaskChecklistItem(checklist,"Source checklist"))),actor)
        val key="source-preserved/${UUID.randomUUID()}.png";val path=files.resolve(key);Files.createDirectories(path.parent);Files.write(path,photo)
        jdbc.update("""insert into merch_task_evidence(id,item_id,storage_backend,object_key,mime_type,byte_size,sha256,source_uploaded_by,created_at)
            values(?,?,'legacy_reference',?,'image/png',?,?,?,now())""",UUID.randomUUID(),item,key,photo.size,TaskEvidenceStorage.hash(photo),actor)
        val canonical=jdbc.queryForObject("select source_payload::text from merch_task_items where id=?",String::class.java,item)!!
        return Triple(get(dispatch),canonical,path)
    }

    private fun nativeLink(dispatch:UUID):String {
        val token="synthetic_capability_"+UUID.randomUUID().toString().replace("-","")
        jdbc.update("""insert into merch_task_links(id,dispatch_id,token_hash,issued_channel,expires_at,native_owned,source_payload)
            values(?,?,?,'pharmapay_posm',now()+interval '1 day',true,'{}')""",UUID.randomUUID(),dispatch,NativeCapabilityAuthorization.tokenHash(token))
        return token
    }
    @Test fun `separate comments are person authored idempotent scoped and independent of submitted comment`() {
        val d=create(0,1);val item=d.items.single();grants(admin.id,"view","comment")
        val request=AddTaskComment(UUID.randomUUID(),"Проверьте верхнюю полку")
        fun postComment(auth:String=adminAuth,body:AddTaskComment=request)=mvc.perform(post("/api/admin/merch/tasks/items/${item.id}/comments").header("Authorization",auth).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)))
        repeat(2){postComment().andExpect(status().isOk)}
        postComment(body=request.copy(body="Другой текст")).andExpect(status().isConflict)
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM merch_task_comments",Int::class.java))
        assertEquals(adminPerson,jdbc.queryForObject("SELECT author_person_id FROM merch_task_comments",UUID::class.java))
        val mobile=mvc.perform(post("/api/mobile/merch/tasks/items/${item.id}/comments").header("Authorization",mobileAuth).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsBytes(AddTaskComment(UUID.randomUUID(),"Принято, проверяю")))).andExpect(status().isOk).andReturn().response.contentAsString
        assertFalse(mobile.contains(pharmacistPerson.toString()));assertFalse(mobile.contains("task-pharmacist"))
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM merch_task_attempts",Int::class.java))
        grants(admin.id,"view");postComment(body=AddTaskComment(UUID.randomUUID(),"Нет права")).andExpect(status().isForbidden)
        mvc.perform(get("/api/admin/merch/tasks/items/${item.id}/comments").header("Authorization",adminAuth)).andExpect(status().isOk).andExpect(jsonPath("$.items.length()").value(2))
    }
    @Test fun `personal block resumes same attempt and retains evidence while aliases cannot steal ownership`() {
        val d=create(0,1);val item=d.items.single();val a=start(item.id);val evidence=upload(item.id)
        val version=get(d.id).items.single().version
        val req=BlockTaskItem(version,"На полке отсутствует материал")
        mvc.perform(post("/api/mobile/merch/tasks/items/${item.id}/block").header("Authorization",mobileAuth).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(req))).andExpect(status().isOk)
        assertEquals("blocked",get(d.id).items.single().status)
        mvc.perform(post("/api/admin/merch/tasks/items/${item.id}/attempts").header("Authorization",adminAuth)).andExpect(status().isForbidden)
        mvc.perform(post("/api/mobile/merch/tasks/items/${item.id}/block").header("Authorization",mobileAuth).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(req))).andExpect(status().isConflict)
        val resumed=start(item.id);assertEquals(a.id,resumed.id);assertEquals(listOf(evidence.id),resumed.evidence.map{it.id})
        assertNull(get(d.id).items.single().blockedReason)
    }
    @Test fun `manager cancel is versioned requires a reason and never removes evidence or rewinds money`() {
        val d=create(0,1);val item=d.items.single();start(item.id);val e=upload(item.id)
        grants(admin.id,"view","cancel");val current=get(d.id)
        fun cancel(version:Long,reason:String)=mvc.perform(post("/api/admin/merch/tasks/${d.id}/cancel").header("Authorization",adminAuth).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(CancelTaskDispatch(version,reason))))
        cancel(current.version,"x").andExpect(status().isBadRequest);cancel(current.version-1,"Не актуально").andExpect(status().isConflict)
        cancel(current.version,"Размещение отменено").andExpect(status().isOk).andExpect(jsonPath("$.status").value("cancelled"))
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM merch_task_evidence WHERE id=?",Int::class.java,e.id))
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM pharmacist_wallet_entries WHERE source='merch_task'",Int::class.java))
        cancel(current.version,"Повтор отмены").andExpect(status().isConflict)
        mvc.perform(post("/api/mobile/merch/tasks/items/${item.id}/attempts").header("Authorization",mobileAuth)).andExpect(status().isConflict)
    }
    private fun planningShelf(occupied:Boolean=true):UUID {
        val shelf=UUID.randomUUID()
        jdbc.update("""INSERT INTO merch_shelves(id,source_system,source_origin,source_id,source_payload,branch_id,code,category,width_m,base_monthly_price,active,place_type,position_index,specifications,sale_mode,created_at,updated_at)
            VALUES(?,'EPHARM','https://epharm.inkar.kz',?,'{}',?,?,'AA',0.8,30000,true,'shelf',0,'{}'::jsonb,'whole_only',now(),now())""",shelf,shelf,branch,shelf.toString())
        if(occupied) {
            val plan=UUID.randomUUID();val item=UUID.randomUUID()
            jdbc.update("""INSERT INTO merch_media_plans(id,source_system,source_origin,source_id,source_payload,advertiser_name,placement_month,status,origin,total_amount,submitted_at,created_at,updated_at)
                VALUES(?,'EPHARM','https://epharm.inkar.kz',?,'{}','Synthetic',?,'approved','request',30000,now(),now(),now())""",plan,plan,java.time.LocalDate.now().withDayOfMonth(1))
            jdbc.update("""INSERT INTO merch_media_plan_items(id,source_system,source_origin,source_id,source_payload,media_plan_id,shelf_id,placement_month,monthly_price,status,sale_portion,created_at)
                VALUES(?,'EPHARM','https://epharm.inkar.kz',?,'{}',?,?,?,30000,'approved','whole',now())""",item,item,plan,shelf,java.time.LocalDate.now().withDayOfMonth(1))
        }
        return shelf
    }
    @Test fun `templates resolve occupied all and none as a transaction snapshot without replaying assignments`() {
        val occupied=planningShelf();planningShelf(false);val month=java.time.LocalDate.now().withDayOfMonth(1)
        fun make(mode:String,type:String="maintenance",id:UUID=UUID.randomUUID())=CreateTemplatedTasks(id,setOf(branch),type,mode,month,Instant.now().plusSeconds(3600))
        fun send(req:CreateTemplatedTasks)=mvc.perform(post("/api/admin/merch/tasks/templated").header("Authorization",adminAuth).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(req)))
        val request=make("occupied");val first=send(request).andExpect(status().isOk).andReturn().response.contentAsString
        val ids=json.readTree(first)["dispatchIds"].map{UUID.fromString(it.asText())};assertEquals(1,ids.size)
        assertEquals(1,get(ids.single()).itemCount);assertEquals("maintenance",get(ids.single()).items.single().taskType);assertEquals(2,get(ids.single()).items.single().requiredPhotos)
        assertEquals(occupied,jdbc.queryForObject("SELECT shelf_id FROM merch_task_items WHERE dispatch_id=?",UUID::class.java,ids.single()))
        planningShelf();assertEquals(first,send(request).andExpect(status().isOk).andReturn().response.contentAsString)
        send(request.copy(shelfMode="all")).andExpect(status().isConflict)
        val all=json.readTree(send(make("all")).andExpect(status().isOk).andReturn().response.contentAsString)["dispatchIds"][0].asText()
        assertEquals(3,get(UUID.fromString(all)).itemCount)
        val none=json.readTree(send(make("none","general")).andExpect(status().isOk).andReturn().response.contentAsString)["dispatchIds"][0].asText()
        assertEquals(1,get(UUID.fromString(none)).itemCount);assertEquals(0,get(UUID.fromString(none)).items.single().requiredPhotos)
    }
    @Test fun `template rejects unknown types foreign branches and empty occupied selection atomically`() {
        val month=java.time.LocalDate.now().withDayOfMonth(1)
        val req=CreateTemplatedTasks(UUID.randomUUID(),setOf(branch),"photo_report","occupied",month,Instant.now().plusSeconds(3600))
        fun send(req:CreateTemplatedTasks)=mvc.perform(post("/api/admin/merch/tasks/templated").header("Authorization",adminAuth).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(req)))
        send(req).andExpect(status().isBadRequest);send(req.copy(templateType="unknown")).andExpect(status().isBadRequest)
        send(req.copy(branchIds=setOf(UUID.randomUUID()))).andExpect(status().isForbidden)
        send(req.copy(month=month.plusDays(1))).andExpect(status().isBadRequest)
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM merch_task_dispatches",Int::class.java))
    }
    @Test fun `monthly visit start and resume reuse verified person packet and source placements without rewards`() {
        planningShelf();val req=StartMonthlyVisit(branch,java.time.LocalDate.now().withDayOfMonth(1))
        fun send()=mvc.perform(post("/api/mobile/merch/tasks/visits").header("Authorization",mobileAuth).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(req))).andExpect(status().isOk).andReturn()
        val first=json.readTree(send().response.contentAsString);val second=json.readTree(send().response.contentAsString)
        assertEquals(first["dispatchId"],second["dispatchId"]);assertTrue(second["resumed"].asBoolean())
        val d=get(UUID.fromString(first["dispatchId"].asText()));assertEquals(0L,d.rewardAmountKzt);assertEquals("monthly_visit",d.taskType)
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM merch_monthly_visits",Int::class.java))
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM merch_task_items WHERE media_plan_item_id IS NOT NULL",Int::class.java))
        val a=start(d.items.single().id);assertEquals(pharmacistPerson,a.executorPersonId)
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM merch_task_rewards",Int::class.java))
    }
    @Test fun `monthly visit wrong pharmacy missing proof and missing placements fail closed`() {
        val req=StartMonthlyVisit(branch,java.time.LocalDate.now().withDayOfMonth(1))
        fun send(req:StartMonthlyVisit,auth:String=mobileAuth)=mvc.perform(post("/api/mobile/merch/tasks/visits").header("Authorization",auth).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(req)))
        send(req).andExpect(status().isBadRequest);planningShelf()
        send(req.copy(branchId=UUID.randomUUID())).andExpect(status().isForbidden)
        jdbc.update("""INSERT INTO pharmacists(id,name,iin,phone,pharmacy_id,pharmacy_name,city,tier,status,joined_at,balance)
            VALUES('task-unlinked','Synthetic unlinked','000000000002','+77000000002','task-pharmacy','Synthetic','Synthetic','Silver','active',current_date,0)""")
        val unlinked="Bearer ${jwt.issuePharmacistToken("task-unlinked","Synthetic unlinked","+77000000002")}"
        send(req,unlinked).andExpect(status().isForbidden)
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM merch_monthly_visits",Int::class.java))
    }

    @Test fun `cancel cannot erase an existing wallet posting even if operational status is still ready`() {
        val d=create(300,1);wallet.credit(WalletCreditSource.MERCH_TASK,d.id.toString(),"task-pharmacist",300,"synthetic-prior-credit")
        grants(admin.id,"view","cancel")
        mvc.perform(post("/api/admin/merch/tasks/${d.id}/cancel").header("Authorization",adminAuth).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsBytes(CancelTaskDispatch(d.version,"Отмена после начисления")))).andExpect(status().isConflict)
        assertEquals("ready",get(d.id).status);assertEquals(800L,jdbc.queryForObject("SELECT balance FROM pharmacists WHERE id='task-pharmacist'",Long::class.java))
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM pharmacist_wallet_entries WHERE source='merch_task'",Int::class.java))
    }
    @Test fun `concurrent monthly starts and canonical account alias return a single existing person visit`() {
        planningShelf();val req=StartMonthlyVisit(branch,java.time.LocalDate.now().withDayOfMonth(1));val pool=Executors.newFixedThreadPool(2)
        fun send(auth:String=mobileAuth,mobile:Boolean=true)=mvc.perform(post("/api/${if(mobile) "mobile" else "admin"}/merch/tasks/visits").header("Authorization",auth).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(req))).andExpect(status().isOk).andReturn().response.contentAsString
        try {
            val ids=(1..2).map{pool.submit<String>{json.readTree(send())["dispatchId"].asText()}}.map{it.get(15,TimeUnit.SECONDS)}
            assertEquals(1,ids.distinct().size)
            jdbc.update("UPDATE unified_admin_profiles SET person_id=? WHERE account_id=?",pharmacistPerson,admin.id)
            assertEquals(ids.first(),json.readTree(send(adminAuth,false))["dispatchId"].asText())
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM merch_monthly_visits",Int::class.java))
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM merch_task_dispatches",Int::class.java))
        } finally{pool.shutdownNow()}
    }
    @Test fun `monthly report preserves two separately approved half placements on the same shelf`() {
        val shelf=planningShelf();val plan=UUID.randomUUID();val item=UUID.randomUUID();val month=java.time.LocalDate.now().withDayOfMonth(1)
        jdbc.update("UPDATE merch_media_plan_items SET sale_portion='left' WHERE shelf_id=?",shelf)
        jdbc.update("""INSERT INTO merch_media_plans(id,source_system,source_origin,source_id,source_payload,advertiser_name,placement_month,status,origin,total_amount,submitted_at,created_at,updated_at)
            VALUES(?,'EPHARM','https://epharm.inkar.kz',?,'{}','Other half',?,'approved','request',15000,now(),now(),now())""",plan,plan,month)
        jdbc.update("""INSERT INTO merch_media_plan_items(id,source_system,source_origin,source_id,source_payload,media_plan_id,shelf_id,placement_month,monthly_price,status,sale_portion,created_at)
            VALUES(?,'EPHARM','https://epharm.inkar.kz',?,'{}',?,?,?,15000,'approved','right',now())""",item,item,plan,shelf,month)
        mvc.perform(post("/api/mobile/merch/tasks/visits").header("Authorization",mobileAuth).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(StartMonthlyVisit(branch,month)))).andExpect(status().isOk)
        assertEquals(2,jdbc.queryForObject("SELECT count(DISTINCT media_plan_item_id) FROM merch_task_items",Int::class.java))
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM merch_task_items WHERE shelf_id=?",Int::class.java,shelf))
    }

    @Test fun `inactive parent organization closes existing task execution and new authoring without rewriting history`() {
        val d=create(0,1);val item=d.items.single();val original=jdbc.queryForObject("SELECT source_payload::text FROM merch_task_dispatches WHERE id=?",String::class.java,d.id)
        jdbc.update("UPDATE merch_organizations SET active=false WHERE id=(SELECT organization_id FROM merch_branches WHERE id=?)",branch)
        assertFalse(get(d.id).items.single().canStart)
        mvc.perform(post("/api/mobile/merch/tasks/items/${item.id}/attempts").header("Authorization",mobileAuth)).andExpect(status().isForbidden)
        mvc.perform(post("/api/admin/merch/tasks/items/${item.id}/attempts").header("Authorization",adminAuth)).andExpect(status().isConflict)
        mvc.perform(post("/api/admin/merch/tasks").header("Authorization",adminAuth).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(CreateMerchTaskDispatch(branch,"New disabled task",Instant.now().plusSeconds(3600),items=listOf(CreateMerchTaskItem("Shelf")))))).andExpect(status().isBadRequest)
        assertEquals(original,jdbc.queryForObject("SELECT source_payload::text FROM merch_task_dispatches WHERE id=?",String::class.java,d.id))
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM merch_task_attempts",Int::class.java))
    }

    @Test fun `monthly reference snapshot retains archived version and rejects mismatched parent or corrupt bytes`() {
        val shelf=planningShelf();val placement=jdbc.queryForObject("SELECT id FROM merch_media_plan_items WHERE shelf_id=?",UUID::class.java,shelf)!!
        fun reference(parent:UUID):Pair<UUID,java.nio.file.Path> {
            val id=UUID.randomUUID();val key="placement-reference/$parent/$id";val path=files.resolve(key);Files.createDirectories(path.parent);Files.write(path,photo)
            jdbc.update("""INSERT INTO merch_placement_reference_photos(id,media_plan_item_id,storage_backend,object_key,mime_type,byte_size,sha256,file_name,created_by_admin,created_at)
                VALUES(?,?,'private_host',?,'image/png',?,?,'synthetic.png',?,now()-interval '1 minute')""",id,parent,key,photo.size.toLong(),TaskEvidenceStorage.hash(photo),admin.id)
            return id to path
        }
        val (oldRef,path)=reference(placement)
        val start=mvc.perform(post("/api/mobile/merch/tasks/visits").header("Authorization",mobileAuth).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(StartMonthlyVisit(branch,java.time.LocalDate.now().withDayOfMonth(1))))).andExpect(status().isOk).andReturn()
        val id=UUID.fromString(json.readTree(start.response.contentAsByteArray)["dispatchId"].asText());val item=get(id).items.single()
        assertEquals(oldRef,item.execution!!.clientReferencePhotoId)
        fun download()=mvc.perform(get("/api/mobile/merch/tasks/items/${item.id}/reference").header("Authorization",mobileAuth))
        download().andExpect(status().isOk).andExpect(content().bytes(photo)).andExpect(header().string("X-Content-SHA256",TaskEvidenceStorage.hash(photo)))
        // Model a PostgreSQL clock just behind the JVM clock which stamped the task. The photo was
        // active when the native writer pinned it; replacing it must not invalidate that snapshot.
        jdbc.update("UPDATE merch_placement_reference_photos SET archived_at=(SELECT created_at FROM merch_task_items WHERE id=?)-interval '1 microsecond',archived_by_admin=? WHERE id=?",item.id,admin.id,oldRef)
        assertEquals(true,jdbc.queryForObject("SELECT r.archived_at<i.created_at FROM merch_task_items i JOIN merch_placement_reference_photos r ON r.id=i.client_reference_photo_id WHERE i.id=?",Boolean::class.java,item.id))
        val newer=reference(placement).first;assertNotEquals(newer,get(id).items.single().execution!!.clientReferencePhotoId)
        download().andExpect(status().isOk).andExpect(content().bytes(photo))
        val foreignShelf=planningShelf();val foreignParent=jdbc.queryForObject("SELECT id FROM merch_media_plan_items WHERE shelf_id=?",UUID::class.java,foreignShelf)!!
        val wrong=reference(foreignParent).first
        assertThrows(org.springframework.dao.DataIntegrityViolationException::class.java) {
            jdbc.update("UPDATE merch_task_items SET client_reference_photo_id=? WHERE id=?",wrong,item.id)
        }
        assertEquals(oldRef,jdbc.queryForObject("SELECT client_reference_photo_id FROM merch_task_items WHERE id=?",UUID::class.java,item.id))
        download().andExpect(status().isOk).andExpect(content().bytes(photo))
        Files.write(path,byteArrayOf(1,2,3))
        download().andExpect(status().isConflict)
        assertEquals(3,jdbc.queryForObject("SELECT count(*) FROM merch_placement_reference_photos",Int::class.java))
    }

    @Test fun `cancel one concrete item preserves sibling evidence and holds paid packet without ledger effects`() {
        val d=create(300,2);val first=d.items.first();val sibling=d.items.last();binding(d);start(sibling.id);val photo=upload(sibling.id)
        grants(admin.id,"view","cancel","financial_data")
        fun send(version:Long=first.version,reason:String="Полка снята с задания")=mvc.perform(post("/api/admin/merch/tasks/items/${first.id}/cancel").header("Authorization",adminAuth).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(CancelTaskItem(version,reason))))
        send(reason="x").andExpect(status().isBadRequest);send(version=99).andExpect(status().isConflict)
        send().andExpect(status().isOk);val next=get(d.id)
        assertEquals("cancelled",next.items.first{it.id==first.id}.status);assertEquals("in_progress",next.items.first{it.id==sibling.id}.status)
        assertEquals(photo.id,next.items.first{it.id==sibling.id}.currentAttempt!!.evidence.single().id)
        assertEquals("held",next.reward!!.state);assertEquals("cancelled_required_item_requires_HQ",next.reward.reason)
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM pharmacist_wallet_entries WHERE source='merch_task'",Int::class.java))
        send().andExpect(status().isConflict)
    }
    @Test fun `item cancel requires separate permission and cannot undo an already posted reward`() {
        val d=create(300,1);val item=d.items.single();val request=CancelTaskItem(item.version,"Снятие пункта")
        fun send()=mvc.perform(post("/api/admin/merch/tasks/items/${item.id}/cancel").header("Authorization",adminAuth).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request)))
        send().andExpect(status().isForbidden);grants(admin.id,"view","cancel")
        wallet.credit(WalletCreditSource.MERCH_TASK,d.id.toString(),"task-pharmacist",300,"synthetic-prior-item-credit")
        send().andExpect(status().isConflict)
        assertEquals("assigned",get(d.id).items.single().status);assertEquals(800L,jdbc.queryForObject("SELECT balance FROM pharmacists WHERE id='task-pharmacist'",Long::class.java))
    }

    private fun capStart(item:UUID,token:String)=json.readValue(mvc.perform(post("/api/public/merch/tasks/items/$item/attempts")
        .header("X-Merch-Task-Token",token)).andExpect(status().isOk).andReturn().response.contentAsByteArray,MerchTaskAttemptDto::class.java)
    private fun capUpload(item:UUID,token:String)=json.readValue(mvc.perform(multipart("/api/public/merch/tasks/items/$item/evidence")
        .file(MockMultipartFile("file","photo.png","image/png",photo)).param("clientUploadId",UUID.randomUUID().toString()).header("X-Merch-Task-Token",token))
        .andExpect(status().isOk).andReturn().response.contentAsByteArray,MerchTaskEvidenceDto::class.java)
    private fun capSubmit(item:MerchTaskItemDto,attempt:MerchTaskAttemptDto,token:String)=mvc.perform(post("/api/public/merch/tasks/items/${item.id}/submit")
        .header("X-Merch-Task-Token",token).contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsBytes(SubmitMerchTaskAttempt(attempt.number,item.checklist.map { it.id }.toSet(),"Synthetic free evidence")))).andExpect(status().isOk)

    @Test fun `stock is required for every configured product and accepts only bounded integers with an explicit shortage reason`() {
        val d=stockPacket();binding(d);val item=d.items.single();val a=start(item.id);upload(item.id)
        stockSubmit(item,a).andExpect(status().isBadRequest)
        for(counts in listOf(mapOf("a" to 5),mapOf("a" to 5,"b" to 6,"extra" to 8),mapOf("a" to 5.5,"b" to 5),
            mapOf("a" to "5","b" to 5),mapOf("a" to -1,"b" to 5),mapOf("a" to 100001,"b" to 5),mapOf("a" to 0,"b" to 5))) {
            stock(item.id,a.number,counts).andExpect(status().isBadRequest)
        }
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM merch_task_stock_observations",Int::class.java))
        stock(item.id,a.number,mapOf("a" to 0,"b" to 100000),"Awaiting the next delivery").andExpect(status().isOk)
        stockSubmit(item,a).andExpect(status().isOk)
        val saved=json.readTree(jdbc.queryForObject("SELECT counts::text FROM merch_task_stock_observations WHERE attempt_id=?",String::class.java,a.id))
        assertEquals(0,saved["a"].asInt());assertEquals(100000,saved["b"].asInt())
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM pharmacist_wallet_entries",Int::class.java))
    }

    @Test fun `revision keeps old stock as history and requires a fresh observation for the new attempt`() {
        val d=stockPacket();binding(d);val item=d.items.single();val first=start(item.id);upload(item.id)
        stock(item.id,first.number,mapOf("a" to 8,"b" to 9)).andExpect(status().isOk);stockSubmit(item,first).andExpect(status().isOk)
        review(d.id,item.id,"revision")
        val second=start(item.id);assertEquals(2,second.number);upload(item.id)
        assertNull(get(d.id).items.single().execution!!.stock)
        stockSubmit(item,second).andExpect(status().isBadRequest)
        stock(item.id,first.number,mapOf("a" to 10,"b" to 10)).andExpect(status().isConflict)
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM merch_task_stock_observations",Int::class.java))
        stock(item.id,second.number,mapOf("a" to 10,"b" to 11)).andExpect(status().isOk)
        stockSubmit(item,second).andExpect(status().isOk)
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM merch_task_stock_observations",Int::class.java))
        assertEquals(8,jdbc.queryForObject("SELECT (counts->>'a')::int FROM merch_task_stock_observations WHERE attempt_id=?",Int::class.java,first.id))
    }

    @Test fun `stock cannot cross a capability dispatch another pharmacist or a revoked administrative execute grant`() {
        val first=stockPacket(0);val second=stockPacket(0);val token=nativeLink(first.id)
        val item=first.items.single();val a=capStart(item.id,token)
        mvc.perform(post("/api/public/merch/tasks/items/${second.items.single().id}/stock").header("X-Merch-Task-Token",token)
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(mapOf("expectedAttempt" to a.number,"counts" to mapOf("a" to 5,"b" to 5)))))
            .andExpect(status().is4xxClientError)
        val otherAuth="Bearer ${jwt.issuePharmacistToken("missing-pharmacist","Synthetic other","+77000000999")}"
        stock(item.id,a.number,mapOf("a" to 5,"b" to 5),auth=otherAuth).andExpect(status().isForbidden)
        grants(admin.id,"view")
        mvc.perform(post("/api/admin/merch/tasks/items/${item.id}/stock").header("Authorization",adminAuth)
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(mapOf("expectedAttempt" to a.number,"counts" to mapOf("a" to 5,"b" to 5)))))
            .andExpect(status().isForbidden)
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM merch_task_stock_observations",Int::class.java))
        // The original link still owns its draft; the forbidden calls did not change its attempt.
        mvc.perform(post("/api/public/merch/tasks/items/${item.id}/stock").header("X-Merch-Task-Token",token)
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(mapOf("expectedAttempt" to a.number,"counts" to mapOf("a" to 5,"b" to 5)))))
            .andExpect(status().isOk)
    }

    @Test fun `nonfinite and out of range location evidence is rejected before file persistence and nearby coordinates never auto approve`() {
        val d=create(count=1);binding(d);val item=d.items.single();start(item.id)
        jdbc.update("UPDATE merch_branches SET latitude=43.25,longitude=76.95 WHERE id=?",branch)
        for(lat in listOf(Double.NaN,Double.POSITIVE_INFINITY,91.0)) {
            captureUpload(item.id,mapOf("status" to "available","latitude" to lat,"longitude" to 76.95,"accuracy" to 15,
                "measuredAt" to Instant.now())).andExpect(status().isBadRequest)
        }
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM merch_task_evidence",Int::class.java))
        val result=captureUpload(item.id,mapOf("status" to "available","latitude" to 43.25,"longitude" to 76.95,"accuracy" to 15,
            "measuredAt" to Instant.now(),"captureSource" to "browser_camera")).andExpect(status().isOk).andReturn()
        val evidence=json.readValue(result.response.contentAsByteArray,MerchTaskEvidenceDto::class.java)
        assertEquals("location_nearby_manual_review",evidence.verification)
        assertEquals("in_progress",get(d.id).items.single().status)
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM pharmacist_wallet_entries",Int::class.java))
    }

    @Test fun `stale far and unavailable capture remain explicit manual evidence without inventing a successful location check`() {
        val d=create(count=1);binding(d);val item=d.items.single();start(item.id)
        jdbc.update("UPDATE merch_branches SET latitude=43.25,longitude=76.95 WHERE id=?",branch)
        val stale=captureUpload(item.id,mapOf("status" to "available","latitude" to 43.25,"longitude" to 76.95,"accuracy" to 15,
            "measuredAt" to Instant.now().minusSeconds(601))).andExpect(status().isOk).andReturn()
        val old=json.readValue(stale.response.contentAsByteArray,MerchTaskEvidenceDto::class.java)
        assertEquals("manual_review_required",old.verification)
        assertEquals("unavailable",(old.capture["location"] as Map<*,*>)["status"])
        val far=captureUpload(item.id,mapOf("status" to "available","latitude" to 44.25,"longitude" to 76.95,"accuracy" to 15,
            "measuredAt" to Instant.now())).andExpect(status().isOk).andReturn()
        val distant=json.readValue(far.response.contentAsByteArray,MerchTaskEvidenceDto::class.java)
        assertEquals("manual_review_required",distant.verification)
        assertTrue((distant.capture["distanceMeters"] as Number).toLong()>200)
        val unavailable=captureUpload(item.id,mapOf("status" to "unavailable","reason" to "Permission denied"))
            .andExpect(status().isOk).andReturn()
        assertEquals("manual_review_required",json.readValue(unavailable.response.contentAsByteArray,MerchTaskEvidenceDto::class.java).verification)
        assertEquals("in_progress",get(d.id).items.single().status)
    }

    @Test fun `a training instruction requires a newly uploaded video while an ordinary photo task cannot accept that video`() {
        val ordinary=create(count=1);binding(ordinary);val plain=ordinary.items.single();start(plain.id)
        val video=Files.readAllBytes(java.nio.file.Path.of("../frontend/public/training/fire-safety-training-video.mp4"))
        fun videoUpload(item:UUID)=mvc.perform(multipart("/api/mobile/merch/tasks/items/$item/evidence")
            .file(MockMultipartFile("file","synthetic-training.mp4","video/mp4",video)).param("clientUploadId",UUID.randomUUID().toString()).header("Authorization",mobileAuth))
        videoUpload(plain.id).andExpect(status().isBadRequest)
        val d=create(count=1);binding(d);val item=d.items.single()
        val material=UUID.randomUUID();val key="synthetic-instructions/$material.pdf";val pdf="%PDF-1.4\n% synthetic test instruction\n%%EOF".toByteArray()
        val path=files.resolve(key);Files.createDirectories(path.parent);Files.write(path,pdf)
        jdbc.update("""INSERT INTO merch_instruction_materials(id,source_system,source_id,source_payload,title,instructions,due_at,status,
            storage_backend,object_key,sha256,byte_size,original_filename,created_at,updated_at)
            VALUES(?,'EPHARM',?,'{}','Synthetic instruction','Read the synthetic instruction',now()+interval '1 day','approved',
            'legacy_reference',?,?,?,'synthetic.pdf',now(),now())""",material,material,key,TaskEvidenceStorage.hash(pdf),pdf.size)
        jdbc.update("UPDATE merch_task_items SET training_material_id=? WHERE id=?",material,item.id)
        val a=start(item.id)
        mvc.perform(get("/api/mobile/merch/tasks/items/${item.id}/instruction").header("Authorization",mobileAuth))
            .andExpect(status().isOk).andExpect(content().bytes(pdf))
        mvc.perform(multipart("/api/mobile/merch/tasks/items/${item.id}/evidence")
            .file(MockMultipartFile("file","photo.png","image/png",photo)).param("clientUploadId",UUID.randomUUID().toString()).header("Authorization",mobileAuth))
            .andExpect(status().isBadRequest)
        stockSubmit(item,a).andExpect(status().isBadRequest)
        videoUpload(item.id).andExpect(status().isOk)
        stockSubmit(item,a).andExpect(status().isOk)
        review(d.id,item.id,"revision")
        val second=start(item.id);stockSubmit(item,second).andExpect(status().isBadRequest)
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM merch_task_evidence WHERE item_id=?",Int::class.java,item.id))
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM pharmacist_wallet_entries",Int::class.java))
    }

    private fun stockPacket(amount:Long=300):MerchTaskDispatchDto {
        val request=CreateMerchTaskDispatch(branch,"Synthetic stock packet",Instant.now().plusSeconds(3600),amount,"task-pharmacist",
            listOf(CreateMerchTaskItem("Synthetic stock shelf",requiredPhotos=1,stockProducts=listOf(TaskStockProduct("a","Synthetic A"),TaskStockProduct("b","Synthetic B")))))
        return json.readValue(mvc.perform(post("/api/admin/merch/tasks").header("Authorization",adminAuth).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsBytes(request))).andExpect(status().isOk).andReturn().response.contentAsByteArray,MerchTaskDispatchDto::class.java)
    }
    private fun stock(item:UUID,attempt:Int,counts:Map<String,Any>,reason:String="",auth:String=mobileAuth)=mvc.perform(
        post("/api/mobile/merch/tasks/items/$item/stock").header("Authorization",auth).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsBytes(mapOf("expectedAttempt" to attempt,"counts" to counts,"shortageReason" to reason))))
    private fun stockSubmit(item:MerchTaskItemDto,attempt:MerchTaskAttemptDto)=mvc.perform(
        post("/api/mobile/merch/tasks/items/${item.id}/submit").header("Authorization",mobileAuth).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsBytes(SubmitMerchTaskAttempt(attempt.number,item.checklist.map {it.id}.toSet()))))
    private fun captureUpload(item:UUID,capture:Map<String,Any>)=mvc.perform(multipart("/api/mobile/merch/tasks/items/$item/evidence")
        .file(MockMultipartFile("file","synthetic.png","image/png",photo))
        .file(MockMultipartFile("capture","","application/json",json.writeValueAsBytes(capture)))
        .param("clientUploadId",UUID.randomUUID().toString()).header("Authorization",mobileAuth))

    private fun submittedReview(): Pair<UUID,ReviewMerchTaskItem> {
        val d=create(count=1);binding(d);val item=d.items.single();val a=start(item.id);upload(item.id);submit(item,a)
        val current=get(d.id).items.single()
        return item.id to ReviewMerchTaskItem(a.id,current.version,"approve","Verified current original evidence")
    }
    private fun sendReview(item: UUID,req: ReviewMerchTaskItem): Int = mvc.perform(post("/api/admin/merch/tasks/items/$item/reviews")
        .header("Authorization",adminAuth).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(req))).andReturn().response.status
    private fun awaitLock(table: String) {
        val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
        while(System.nanoTime()<until) {
            val waiting=jdbc.queryForObject("""select count(*) from pg_stat_activity where datname=current_database() AND pid<>pg_backend_pid()
                AND wait_event_type='Lock' AND query ILIKE ? AND query ILIKE '%for%update%'""",Int::class.java,"%$table%")!!
            if(waiting>0) return
            Thread.sleep(20)
        }
        fail<Unit>("Expected real PostgreSQL lock wait on $table")
    }
    @Test fun `bulk creation is atomic exact retry safe and rechecks revoked scope before returning IDs`() {
        val request=CreateMerchTaskBulk(UUID.randomUUID(),setOf(branch),"Bulk synthetic",Instant.now().plusSeconds(3600),0,
            listOf(CreateMerchTaskItem("Required proof",requiredPhotos=0)))
        fun send(body:CreateMerchTaskBulk)=mvc.perform(post("/api/admin/merch/tasks/bulk").header("Authorization",adminAuth)
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)))
        val first=json.readTree(send(request).andExpect(status().isOk).andReturn().response.contentAsByteArray)
        assertEquals(first,json.readTree(send(request).andExpect(status().isOk).andReturn().response.contentAsByteArray))
        assertEquals(1,jdbc.queryForObject("select count(*) from merch_task_dispatches",Int::class.java))
        send(request.copy(title="Changed request")).andExpect(status().isConflict)
        grants(admin.id,"view")
        send(request).andExpect(status().isForbidden)
        assertEquals(1,jdbc.queryForObject("select count(*) from merch_task_bulk_receipts",Int::class.java))
    }

    @Test fun `bulk never leaves a partial assignment when any selected branch is inactive or outside grant`() {
        val second=UUID.randomUUID()
        jdbc.update("""INSERT INTO merch_branches(id,source_system,source_origin,source_id,source_payload,organization_id,
            code,name,address,city,active,created_at,updated_at)
            SELECT ?,'EPHARM',source_origin,?,'{}',organization_id,'BULK2','Other branch','','',false,now(),now()
            FROM merch_branches WHERE id=?""",second,second,branch)
        val request=CreateMerchTaskBulk(UUID.randomUUID(),setOf(branch,second),"Atomic synthetic",Instant.now().plusSeconds(3600),0,
            listOf(CreateMerchTaskItem("Required proof",requiredPhotos=0)))
        fun send()=mvc.perform(post("/api/admin/merch/tasks/bulk").header("Authorization",adminAuth).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request)))
        send().andExpect(status().isForbidden)
        jdbc.update("update unified_admin_profiles set grants_json=?::jsonb where account_id=?",json.writeValueAsString(listOf("view","create").map {
            StoredGrant("merchandising","tasks",it,merchBranchIds=setOf(branch.toString(),second.toString())) }),admin.id)
        send().andExpect(status().isBadRequest)
        assertEquals(0,jdbc.queryForObject("select count(*) from merch_task_dispatches",Int::class.java))
        assertEquals(0,jdbc.queryForObject("select count(*) from merch_task_bulk_receipts",Int::class.java))
    }

    @Test fun `review queue includes pending siblings even when dispatch already requires revision and escapes literal search`() {
        val task=create(0,2)
        jdbc.update("update merch_task_dispatches set status='revision_required',title='Mixed 100% packet' where id=?",task.id)
        jdbc.update("update merch_task_items set status='submitted' where id=?",task.items[0].id)
        jdbc.update("update merch_task_items set status='revision_required' where id=?",task.items[1].id)
        fun query(queue:String,q:String="")=json.readTree(mvc.perform(get("/api/admin/merch/tasks").header("Authorization",adminAuth)
            .param("queue",queue).param("q",q)).andExpect(status().isOk).andReturn().response.contentAsByteArray)
        assertEquals(1,query("review")["items"].size())
        val summary=query("review")["items"][0]
        assertEquals(1,summary["pendingReviewCount"].asInt())
        assertEquals(task.items[0].id.toString(),summary["nextPendingReviewItemId"].asText())
        assertFalse(summary["detailsIncluded"].asBoolean())
        assertEquals(0,summary["items"].size())
        assertEquals(1,query("revision")["items"].size())
        assertEquals(1,query("review","100%")["items"].size())
        assertEquals(0,query("review","100_")["items"].size())
        assertEquals(0,query("completed")["items"].size())
        mvc.perform(get("/api/admin/merch/tasks").header("Authorization",adminAuth).param("queue","invented")).andExpect(status().isBadRequest)
    }

    @Test fun `pending review navigation follows sibling decisions and does not authorize a view only reader`() {
        val task=create(0,2)
        assertEquals(0,task.pendingReviewCount);assertNull(task.nextPendingReviewItemId)
        task.items.forEach { item -> val attempt=start(item.id);upload(item.id);submit(item,attempt) }
        val submitted=get(task.id)
        assertEquals(2,submitted.pendingReviewCount)
        assertEquals(task.items.first().id,submitted.nextPendingReviewItemId)
        assertTrue(submitted.items.all { it.canReview })
        val revised=review(task.id,task.items.first().id,"revision")
        assertEquals("revision_required",revised.status)
        assertEquals(1,revised.pendingReviewCount)
        assertEquals(task.items.last().id,revised.nextPendingReviewItemId)
        grants(admin.id,"view")
        val readOnly=get(task.id)
        assertEquals(1,readOnly.pendingReviewCount)
        assertEquals(task.items.last().id,readOnly.nextPendingReviewItemId)
        assertTrue(readOnly.items.none { it.canReview })
        val finished=review(task.id,task.items.last().id,auth=reviewerAuth)
        assertEquals(0,finished.pendingReviewCount);assertNull(finished.nextPendingReviewItemId)
        assertEquals("revision_required",finished.status)
    }

    @Test fun `pending review summaries preserve branch scope and page boundaries`() {
        val first=create(0,2);val second=create(0,1);val hidden=create(0,3)
        jdbc.update("update merch_task_items set status='submitted'")
        val outside=UUID.randomUUID()
        jdbc.update("""INSERT INTO merch_branches(id,source_system,source_origin,source_id,source_payload,organization_id,
            code,name,address,city,active,created_at,updated_at)
            SELECT ?,'EPHARM',source_origin,?,'{}',organization_id,'OUTSIDE','Outside scope','','',true,now(),now()
            FROM merch_branches WHERE id=?""",outside,outside,branch)
        jdbc.update("update merch_task_dispatches set branch_id=? where id=?",outside,hidden.id)
        jdbc.update("update merch_task_dispatches set created_at=? where id=?",Timestamp.from(Instant.parse("2026-01-03T00:00:00Z")),hidden.id)
        jdbc.update("update merch_task_dispatches set created_at=? where id=?",Timestamp.from(Instant.parse("2026-01-02T00:00:00Z")),first.id)
        jdbc.update("update merch_task_dispatches set created_at=? where id=?",Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")),second.id)
        grants(admin.id,"view")
        fun page(offset:Int)=json.readValue(mvc.perform(get("/api/admin/merch/tasks").header("Authorization",adminAuth)
            .param("queue","review").param("limit","1").param("offset",offset.toString()))
            .andExpect(status().isOk).andReturn().response.contentAsByteArray,MerchTaskDispatchPage::class.java)
        val firstPage=page(0)
        assertTrue(firstPage.hasMore);assertEquals(first.id,firstPage.items.single().id)
        assertEquals(2,firstPage.items.single().pendingReviewCount)
        assertEquals(first.items.first().id,firstPage.items.single().nextPendingReviewItemId)
        assertTrue(firstPage.items.single().items.isEmpty());assertNull(firstPage.items.single().rewardAmountKzt)
        val lastPage=page(1)
        assertFalse(lastPage.hasMore);assertEquals(second.id,lastPage.items.single().id)
        assertEquals(1,lastPage.items.single().pendingReviewCount)
        assertTrue(page(2).items.isEmpty())
        mvc.perform(get("/api/admin/merch/tasks/${hidden.id}").header("Authorization",adminAuth)).andExpect(status().isForbidden)
    }

    private fun webpFixture(name:String)=javaClass.getResourceAsStream("/placement-reference/$name.webp")!!.use {it.readBytes()}
    private fun uploadWebp(item:UUID,bytes:ByteArray)=mvc.perform(multipart("/api/mobile/merch/tasks/items/$item/evidence")
        .file(MockMultipartFile("file","synthetic.webp","image/webp",bytes)).param("clientUploadId",UUID.randomUUID().toString()).header("Authorization",mobileAuth))
    @Test fun `task evidence keeps original lossy and lossless WebP bytes correct MIME extension and hash`() {
        val d=create(0,1);val item=d.items.single();start(item.id)
        for(name in listOf("lossy","lossless")) {
            val bytes=webpFixture(name)
            val evidence=json.readValue(uploadWebp(item.id,bytes).andExpect(status().isOk).andReturn().response.contentAsByteArray,MerchTaskEvidenceDto::class.java)
            assertEquals("image/webp",evidence.mimeType);assertEquals(TaskEvidenceStorage.hash(bytes),evidence.sha256)
            val key=jdbc.queryForObject("SELECT object_key FROM merch_task_evidence WHERE id=?",String::class.java,evidence.id)!!
            assertTrue(key.endsWith(".webp"));assertArrayEquals(bytes,Files.readAllBytes(files.resolve(key)))
            mvc.perform(get("/api/mobile/merch/tasks/evidence/${evidence.id}").header("Authorization",mobileAuth)).andExpect(status().isOk)
                .andExpect(content().contentType("image/webp")).andExpect(content().bytes(bytes))
        }
    }
    @Test fun `task WebP alpha remains in the original submitted evidence`() {
        val d=create(0,1);val item=d.items.single();start(item.id)
        val bytes=webpFixture("alpha");val response=uploadWebp(item.id,bytes).andExpect(status().isOk).andReturn()
        val id=json.readTree(response.response.contentAsByteArray)["id"].asText()
        mvc.perform(get("/api/mobile/merch/tasks/evidence/$id").header("Authorization",mobileAuth)).andExpect(status().isOk)
            .andExpect(content().contentType("image/webp")).andExpect(content().bytes(bytes))
        val decoded=ImageIO.read(java.io.ByteArrayInputStream(bytes))
        try {assertTrue(decoded.colorModel.hasAlpha());assertEquals(0,decoded.getRGB(0,0) ushr 24)} finally {decoded.flush()}
    }
    @Test fun `task truncated corrupt and excessive pixel WebP evidence is rejected before persistence`() {
        val d=create(0,1);val item=d.items.single();start(item.id)
        uploadWebp(item.id,webpFixture("lossless").copyOf(24)).andExpect(status().isBadRequest)
        uploadWebp(item.id,webpFixture("lossy").also {java.util.Arrays.fill(it,20,it.size,0.toByte())}).andExpect(status().isBadRequest)
        val huge=webpFixture("alpha").also {bytes->for(offset in listOf(24,27)) {bytes[offset]=0xff.toByte();bytes[offset+1]=0x0f;bytes[offset+2]=0}}
        uploadWebp(item.id,huge).andExpect(status().isPayloadTooLarge)
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM merch_task_evidence WHERE item_id=?",Int::class.java,item.id))
    }
}
