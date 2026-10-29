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
    private fun capStart(item:UUID,token:String)=json.readValue(mvc.perform(post("/api/public/merch/tasks/items/$item/attempts")
        .header("X-Merch-Task-Token",token)).andExpect(status().isOk).andReturn().response.contentAsByteArray,MerchTaskAttemptDto::class.java)
    private fun capUpload(item:UUID,token:String)=json.readValue(mvc.perform(multipart("/api/public/merch/tasks/items/$item/evidence")
        .file(MockMultipartFile("file","photo.png","image/png",photo)).param("clientUploadId",UUID.randomUUID().toString()).header("X-Merch-Task-Token",token))
        .andExpect(status().isOk).andReturn().response.contentAsByteArray,MerchTaskEvidenceDto::class.java)
    private fun capSubmit(item:MerchTaskItemDto,attempt:MerchTaskAttemptDto,token:String)=mvc.perform(post("/api/public/merch/tasks/items/${item.id}/submit")
        .header("X-Merch-Task-Token",token).contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsBytes(SubmitMerchTaskAttempt(attempt.number,item.checklist.map { it.id }.toSet(),"Synthetic free evidence")))).andExpect(status().isOk)

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
}
