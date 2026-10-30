package kz.epharm.merch.tasks

import com.fasterxml.jackson.databind.ObjectMapper
import kz.epharm.auth.access.StoredGrant
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.entity.AdminUserEntity
import kz.epharm.auth.repository.AdminUserRepository
import kz.epharm.auth.service.JwtService
import kz.epharm.merch.staffaccess.StaffAccessTokens
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

@SpringBootTest(properties=["app.merch.staff-access-enabled=true"])
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class StaffVisitIntegrationTest {
    companion object {
        @Container @JvmStatic val postgres=PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("synthetic_staff_visits").withUsername("test").withPassword("synthetic-only").apply{start()}
        private val files=Files.createTempDirectory("synthetic-staff-visits-").toRealPath().also {
            Files.setPosixFilePermissions(it,PosixFilePermissions.fromString("rwx------")) }
        private val codeHash=BCryptPasswordEncoder(10).encode("765432")
        @JvmStatic @DynamicPropertySource fun properties(reg:DynamicPropertyRegistry) {
            reg.add("spring.datasource.url"){postgres.jdbcUrl};reg.add("spring.datasource.username"){postgres.username}
            reg.add("spring.datasource.password"){postgres.password};reg.add("app.merch-tasks.evidence-root"){files.toString()}
        }
    }
    @Autowired lateinit var visits:StaffVisitService
    @Autowired lateinit var jdbc:JdbcTemplate
    @Autowired lateinit var named:NamedParameterJdbcTemplate
    @Autowired lateinit var tokens:StaffAccessTokens
    @Autowired lateinit var users:AdminUserRepository
    @Autowired lateinit var jwt:JwtService
    @Autowired lateinit var json:ObjectMapper
    @Autowired lateinit var mvc:MockMvc
    private lateinit var organization:UUID
    private lateinit var branch:UUID
    private lateinit var otherBranch:UUID
    private lateinit var session:String
    private lateinit var adminAuth:String
    private lateinit var ordinaryAuth:String
    private val month=LocalDate.now().withDayOfMonth(1)
    private val photo=ByteArrayOutputStream().also {ImageIO.write(BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",it)}.toByteArray()

    @BeforeEach fun seed() {
        jdbc.execute("TRUNCATE merch_organizations,unified_persons,admin_users CASCADE")
        organization=source("merch_organizations",mapOf("name" to "Synthetic organization","legal_name" to "Synthetic","active" to true))
        branch=branch(organization,"01");otherBranch=branch(organization,"02")
        session=session(setOf(branch))
        adminAuth=reviewer(AdminRole.HQ_HEAD);ordinaryAuth=reviewer(AdminRole.CATEGORY_LEAD)
    }

    private fun source(table:String,fields:Map<String,Any?>):UUID {
        val id=UUID.randomUUID();val now=Timestamp.from(Instant.now())
        val row=linkedMapOf<String,Any?>("id" to id,"source_system" to "EPHARM","source_origin" to "https://epharm.inkar.kz",
            "source_id" to id,"source_payload" to "{}","created_at" to now)
        if(table!="merch_media_plan_items") row["updated_at"]=now
        row.putAll(fields)
        named.update("INSERT INTO $table(${row.keys.joinToString(",")}) VALUES(${row.keys.joinToString(","){if(it in setOf("source_payload","specifications")) "CAST(:$it AS jsonb)" else ":$it"}})",row)
        return id
    }
    private fun branch(org:UUID,code:String)=source("merch_branches",mapOf("organization_id" to org,"code" to code,
        "name" to "Synthetic branch $code","address" to "Demo address","city" to "Synthetic","active" to true))
    private fun placement(target:UUID=branch,portion:String="whole",sameShelf:UUID?=null,active:Boolean=true):Pair<UUID,UUID> {
        val shelf=sameShelf?:source("merch_shelves",mapOf("branch_id" to target,"code" to "Synthetic shelf ${UUID.randomUUID()}",
            "category" to "AA","width_m" to 0.8,"base_monthly_price" to 30000,"active" to active,"place_type" to "shelf",
            "position_index" to 0,"specifications" to "{}","sale_mode" to "whole_or_halves","half_monthly_price" to 15000))
        val plan=source("merch_media_plans",mapOf("advertiser_name" to "Synthetic advertiser","placement_month" to month,
            "status" to "approved","origin" to "request","total_amount" to 30000,"submitted_at" to Timestamp.from(Instant.now())))
        val item=source("merch_media_plan_items",mapOf("media_plan_id" to plan,"shelf_id" to shelf,"placement_month" to month,
            "monthly_price" to 30000,"status" to "approved","sale_portion" to portion))
        return item to shelf
    }
    private fun session(branches:Set<UUID>,org:UUID=organization,period:LocalDate=month,deadline:Instant?=null):String {
        val code=UUID.randomUUID();val token=tokens.token()
        jdbc.update("""INSERT INTO merch_staff_access_codes(id,source_system,source_origin,source_id,organization_id,placement_month,
            code_hash,code_lookup_hash,deadline_at,status,created_at,updated_at)
            VALUES(?,'EPHARM','https://epharm.inkar.kz',?,?,?,?,?,?,'active',now(),now())""",
            code,code,org,period,codeHash,tokens.codeLookup("765432"),deadline?.let(Timestamp::from))
        for(id in branches) jdbc.update("INSERT INTO merch_staff_access_code_branches(code_id,branch_id,created_at) VALUES(?,?,now())",code,id)
        jdbc.update("""INSERT INTO merch_staff_access_sessions(id,code_id,token_hash,created_at,expires_at)
            VALUES(?,?,?,now(),now()+interval '12 hours')""",UUID.randomUUID(),code,StaffAccessTokens.sessionHash(token))
        return token
    }
    private fun reviewer(role:AdminRole):String {
        val user=users.saveAndFlush(AdminUserEntity(email="${UUID.randomUUID()}@example.test",name="Synthetic reviewer",
            passwordHash="unused-synthetic",roleRaw=role.name))
        val person=UUID.randomUUID()
        jdbc.update("INSERT INTO unified_persons(id,verification_reference) VALUES(?,'synthetic-reviewed-proof')",person)
        jdbc.update("UPDATE admin_users SET access_mode='MANAGED' WHERE id=?",user.id)
        jdbc.update("INSERT INTO unified_admin_profiles(account_id,person_id,grants_json) VALUES(?,?,?::jsonb)",user.id,person,
            json.writeValueAsString(listOf("view","review","execute").map {StoredGrant("merchandising","tasks",it,merchBranchIds=setOf(branch.toString()))}))
        return "Bearer ${jwt.issueAccessToken(user)}"
    }
    private fun start(raw:String=session,target:UUID=branch,name:String="Synthetic employee")=visits.start(raw,StartStaffVisit(target,month,name))
    private fun upload(v:StaffVisitDto,item:UUID=v.dispatch.items.first().id,raw:String=session,id:UUID=UUID.randomUUID(),bytes:ByteArray=photo)=
        visits.upload(raw,v.id,item,id,MockMultipartFile("file","synthetic.png","image/png",bytes))
    private fun submit(v:StaffVisitDto,name:String="Synthetic submitter",raw:String=session):StaffVisitDto {
        val fresh=visits.get(raw,v.id)
        return visits.submit(raw,v.id,SubmitStaffVisit(fresh.version,name,"Synthetic report notes"))
    }
    private fun review(v:StaffVisitDto,itemId:UUID,decision:String="approve",auth:String=adminAuth,manual:Boolean=true):Int {
        val item=visits.get(session,v.id).dispatch.items.single{it.id==itemId}
        return mvc.perform(post("/api/admin/merch/tasks/items/$itemId/reviews").header("Authorization",auth)
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(ReviewMerchTaskItem(item.currentAttempt!!.id,
                item.version,decision,"Synthetic manual evidence review",manualUnverifiedExecutor=manual)))).andReturn().response.status
    }
    private fun fails(code:ErrorCode,action:()->Unit) {
        assertEquals(code,assertThrows(AppException::class.java,action).code)
    }

    @Test fun `concurrent codes share one branch month report without fabricating a person or deadline`() {
        placement();val second=session(setOf(branch),deadline=Instant.now().plusSeconds(300))
        val pool=Executors.newFixedThreadPool(2);val ready=CountDownLatch(2);val go=CountDownLatch(1)
        try {
            val results=listOf(session,second).map {raw->pool.submit<StaffVisitDto>{ready.countDown();assertTrue(go.await(10,TimeUnit.SECONDS));start(raw)}}
            assertTrue(ready.await(10,TimeUnit.SECONDS));go.countDown()
            val reports=results.map{it.get(20,TimeUnit.SECONDS)}
            assertEquals(reports.first().id,reports.last().id)
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM merch_staff_visits",Int::class.java))
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM merch_monthly_visits",Int::class.java))
            assertNull(reports.first().dispatch.dueAt);assertEquals(0L,reports.first().dispatch.rewardAmountKzt)
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM merch_task_dispatches WHERE created_by IS NOT NULL OR assigned_pharmacist_id IS NOT NULL",Int::class.java))
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM merch_branches WHERE pharmacy_binding_verified",Int::class.java))
        } finally {pool.shutdownNow()}
    }

    @Test fun `workspace scopes counts before pagination and refuses a different month organization or empty scope`() {
        placement();placement(otherBranch);val both=session(setOf(branch,otherBranch))
        val page=visits.workspace(session,month,1,0)
        assertEquals(listOf(branch),page.branches.map{it.id});assertEquals(1,page.branches.single().requiredCount);assertFalse(page.hasMore)
        assertTrue(visits.workspace(both,month,1,0).hasMore);assertEquals(1,visits.workspace(both,month,1,1).branches.size)
        assertTrue(page.tasks.isEmpty())
        fails(ErrorCode.FORBIDDEN){visits.workspace(session,month.plusMonths(1))}
        fails(ErrorCode.FORBIDDEN){start(target=otherBranch)}
        val otherOrg=source("merch_organizations",mapOf("name" to "Other","legal_name" to "Other","active" to true))
        val wrong=session(setOf(branch),org=otherOrg)
        assertThrows(AppException::class.java){visits.get(wrong,start().id)}
        assertThrows(AppException::class.java){visits.workspace(session(emptySet()))}
    }

    @Test fun `halves of an approved shelf remain distinct and archive does not erase an approved report place`() {
        val left=placement(portion="left",active=false);placement(portion="right",sameShelf=left.second)
        val v=start()
        assertEquals(2,v.dispatch.itemCount);assertEquals(setOf("left","right"),v.places.map{it.portion}.toSet())
        assertEquals(1,v.places.map{it.shelfId}.distinct().size)
        assertEquals(2,v.dispatch.items.map{it.title}.distinct().size)
        assertTrue(v.dispatch.items.all{it.execution?.requiresStock==false && it.checklist.isEmpty()})
    }

    @Test fun `camera upload is lazy idempotent and snapshots only unverified staff ownership`() {
        placement();val v=start();val id=UUID.randomUUID();val e=upload(v,id=id)
        assertEquals(e.id,upload(v,id=id).id)
        val a=visits.get(session,v.id).dispatch.items.single().currentAttempt!!
        assertEquals("staff_access_unverified",a.executorIdentityStatus);assertEquals(v.id,a.staffVisitId)
        assertEquals("Synthetic employee",a.staffSubmittedName);assertNull(a.executorPersonId);assertNull(a.pharmacistId)
        assertEquals("web_camera",e.capture["captureSource"])
        assertArrayEquals(photo,visits.evidence(session,v.id,e.id).second)
        val another=ByteArrayOutputStream().also{out->val image=BufferedImage(2,2,BufferedImage.TYPE_INT_RGB);image.setRGB(0,0,123456);ImageIO.write(image,"png",out)}.toByteArray()
        fails(ErrorCode.CONFLICT){upload(v,id=id,bytes=another)}
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM merch_task_evidence",Int::class.java))
    }

    @Test fun `whole report submit is atomic validates every photo and CAS then freezes entered name in each attempt`() {
        placement();placement();val v=start();upload(v)
        val before=visits.get(session,v.id)
        assertThrows(AppException::class.java){visits.submit(session,v.id,SubmitStaffVisit(before.version,"Synthetic sender"))}
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM merch_task_attempts WHERE submitted_at IS NOT NULL",Int::class.java))
        upload(v,v.dispatch.items.last().id)
        fails(ErrorCode.CONFLICT){visits.submit(session,v.id,SubmitStaffVisit(before.version,"Synthetic sender"))}
        val sent=submit(v)
        assertEquals("submitted",sent.dispatch.status);assertFalse(sent.canEdit)
        assertTrue(sent.dispatch.items.all{it.currentAttempt?.staffSubmittedName=="Synthetic submitter" && it.currentAttempt?.status=="submitted"})
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM pharmacist_wallet_entries WHERE source='merch_task'",Int::class.java))
    }

    @Test fun `foreign visit item evidence and reference never cross session branch scope`() {
        placement();placement(otherBranch)
        val own=start();val foreignSession=session(setOf(otherBranch));val foreign=start(foreignSession,otherBranch)
        val e=upload(foreign,raw=foreignSession)
        fails(ErrorCode.FORBIDDEN){visits.get(session,foreign.id)}
        fails(ErrorCode.NOT_FOUND){upload(own,foreign.dispatch.items.single().id)}
        fails(ErrorCode.NOT_FOUND){visits.evidence(session,own.id,e.id)}
        fails(ErrorCode.NOT_FOUND){visits.reference(session,own.id,foreign.dispatch.items.single().id)}
        assertEquals(0,visits.get(session,own.id).dispatch.items.single().currentAttempt?.evidence?.size?:0)
    }

    @Test fun `deadline expiry retains scoped history and rejects all writes before creating attempts`() {
        placement();val v=start();val readOnly=session(setOf(branch),deadline=Instant.now().minusSeconds(1))
        val d=visits.get(readOnly,v.id);assertFalse(d.canEdit);assertFalse(d.dispatch.items.single().canUpload)
        assertTrue(visits.workspace(readOnly).readOnly)
        fails(ErrorCode.FORBIDDEN){start(readOnly)};fails(ErrorCode.FORBIDDEN){upload(v,raw=readOnly)}
        fails(ErrorCode.FORBIDDEN){submit(v,raw=readOnly)}
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM merch_task_attempts",Int::class.java))
    }

    @Test fun `manual revoke denies an already opened report and generic admin cannot become its executor`() {
        placement();val v=start()
        mvc.perform(post("/api/admin/merch/tasks/items/${v.dispatch.items.single().id}/attempts").header("Authorization",adminAuth))
            .andExpect(status().isForbidden)
        jdbc.update("UPDATE merch_staff_access_codes SET manual_revoked_at=now(),status='revoked',revoked_at=now() WHERE id=(SELECT code_id FROM merch_staff_access_sessions WHERE token_hash=?)",
            StaffAccessTokens.sessionHash(session))
        fails(ErrorCode.UNAUTHORIZED){visits.get(session,v.id)}
        fails(ErrorCode.UNAUTHORIZED){upload(v)}
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM merch_task_attempts",Int::class.java))
    }

    @Test fun `unverified reports require explicit HQ manual review and never credit a wallet`() {
        placement();val v=start();upload(v);val sent=submit(v);val item=sent.dispatch.items.single()
        assertEquals(403,review(v,item.id,manual=false));assertEquals(403,review(v,item.id,auth=ordinaryAuth))
        assertEquals(200,review(v,item.id))
        assertEquals("completed",visits.get(session,v.id).dispatch.status)
        assertEquals("not_monetary",jdbc.queryForObject("SELECT state FROM merch_task_rewards WHERE dispatch_id=?",String::class.java,v.dispatch.id))
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM pharmacist_wallet_entries WHERE source='merch_task'",Int::class.java))
    }

    @Test fun `revision creates another native attempt while prior photo review and submitted name remain unchanged`() {
        placement();val v=start();val firstPhoto=upload(v);val sent=submit(v,"First synthetic sender");val item=sent.dispatch.items.single()
        assertEquals(200,review(v,item.id,"revision"))
        val resumed=start(name="Second synthetic employee");assertEquals(v.id,resumed.id)
        upload(resumed);val latest=submit(resumed,"Second synthetic sender")
        assertEquals(2,latest.dispatch.items.single().currentAttempt!!.number)
        assertEquals(listOf("First synthetic sender","Second synthetic sender"),jdbc.query("SELECT staff_submitted_name FROM merch_task_attempts ORDER BY attempt_no",{rs,_->rs.getString(1)}))
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM merch_task_reviews",Int::class.java))
        assertArrayEquals(photo,visits.evidence(session,v.id,firstPhoto.id).second)
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM merch_task_evidence",Int::class.java))
    }

    @Test fun `returned shelf can be resubmitted while sibling remains submitted without rewriting sibling history`() {
        placement();placement();val v=start()
        v.dispatch.items.forEach{upload(v,it.id)}
        val sent=submit(v,"First synthetic sender")
        val returned=sent.dispatch.items.first();val pending=sent.dispatch.items.last()
        val pendingAttempt=pending.currentAttempt!!
        assertEquals(200,review(v,returned.id,"revision"))
        fails(ErrorCode.CONFLICT){submit(v,"Cannot reuse rejected photo")}
        val resumed=start(name="Second synthetic employee")
        upload(resumed,returned.id)
        val again=submit(resumed,"Second synthetic sender")
        val kept=again.dispatch.items.single{it.id==pending.id}.currentAttempt!!
        assertEquals("submitted",again.dispatch.status)
        assertEquals(pendingAttempt.id,kept.id);assertEquals(pendingAttempt.submittedAt,kept.submittedAt)
        assertEquals(pendingAttempt.staffSubmittedName,kept.staffSubmittedName)
        assertEquals(pendingAttempt.evidence.map{it.id},kept.evidence.map{it.id})
        assertEquals(2,again.dispatch.items.single{it.id==returned.id}.currentAttempt!!.number)
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM pharmacist_wallet_entries WHERE source='merch_task'",Int::class.java))
    }

    @Test fun `changed approved placement set conflicts without deleting already captured evidence`() {
        placement();val v=start();val e=upload(v);placement()
        fails(ErrorCode.CONFLICT){submit(v)};fails(ErrorCode.CONFLICT){start()}
        assertArrayEquals(photo,visits.evidence(session,v.id,e.id).second)
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM merch_task_evidence",Int::class.java))
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM merch_task_attempts WHERE submitted_at IS NOT NULL",Int::class.java))
    }

    @Test fun `invalid employee and oversized notes never mutate a shared report`() {
        placement();fails(ErrorCode.VALIDATION_FAILED){start(name="x")}
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM merch_staff_visits",Int::class.java))
        val v=start();upload(v)
        fails(ErrorCode.VALIDATION_FAILED){visits.submit(session,v.id,SubmitStaffVisit(visits.get(session,v.id).version,"Synthetic", "x".repeat(1001)))}
        assertNull(visits.get(session,v.id).submittedAt)
    }
}
