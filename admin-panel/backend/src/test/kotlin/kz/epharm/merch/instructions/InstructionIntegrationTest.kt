package kz.epharm.merch.instructions

import com.fasterxml.jackson.databind.ObjectMapper
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.entity.AdminUserEntity
import kz.epharm.auth.repository.AdminUserRepository
import kz.epharm.auth.service.JwtService
import kz.epharm.merch.producer.auth.ProducerPrincipal
import kz.epharm.merch.producer.auth.ProducerTokenCodec
import kz.epharm.merch.tasks.TaskEvidenceStorage
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest(properties=["app.merch.producer-auth-enabled=true","app.merch.native-delivery-enabled=false"])
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class InstructionIntegrationTest {
    companion object {
        @Container @JvmStatic val postgres=PostgreSQLContainer("postgres:16-alpine").withDatabaseName("instructions_test").withUsername("test").withPassword("test").apply{start()}
        val fileRoot=Files.createTempDirectory("epharm-instructions-test-").toRealPath().also{Files.setPosixFilePermissions(it,PosixFilePermissions.fromString("rwx------"))}
        @JvmStatic @DynamicPropertySource fun properties(reg: DynamicPropertyRegistry){
            reg.add("spring.datasource.url"){postgres.jdbcUrl};reg.add("spring.datasource.username"){postgres.username};reg.add("spring.datasource.password"){postgres.password}
            reg.add("app.merch-tasks.evidence-root"){fileRoot.toString()}
        }
        val B1=UUID.fromString("10000000-0000-4000-8000-000000000001");val B2=UUID.fromString("20000000-0000-4000-8000-000000000002")
    }
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var named: NamedParameterJdbcTemplate
    @Autowired lateinit var admins: AdminUserRepository
    @Autowired lateinit var jwt: JwtService
    @Autowired lateinit var producerTokens: ProducerTokenCodec
    @Autowired lateinit var storage: TaskEvidenceStorage
    lateinit var account: AdminUserEntity;lateinit var person: UUID;lateinit var admin: String
    lateinit var producer: UUID;lateinit var other: UUID;lateinit var own: String;lateinit var foreign: String;lateinit var plan: UUID;lateinit var shelf: UUID
    lateinit var pdf: ByteArray
    @BeforeEach fun seed(){
        jdbc.execute("TRUNCATE merch_instruction_events,merch_instruction_materials,merch_organizations,merch_producers,unified_persons,admin_users CASCADE")
        account=admins.saveAndFlush(AdminUserEntity(email="instructions@example.test",name="Synthetic reviewer",passwordHash="unused",roleRaw=AdminRole.HQ_HEAD.name))
        admin="Bearer ${jwt.issueAccessToken(account)}";person=UUID.randomUUID()
        jdbc.update("UPDATE admin_users SET access_mode='MANAGED' WHERE id=?",account.id)
        jdbc.update("INSERT INTO unified_persons(id,verification_reference) VALUES(?,'synthetic-only')",person)
        jdbc.update("INSERT INTO unified_admin_profiles(account_id,person_id) VALUES(?,?)",account.id,person)
        grant(all=true)
        val org=source("merch_organizations",mapOf("name" to "Synthetic","legal_name" to "Synthetic","active" to true))
        jdbc.update("INSERT INTO chains(id,name,\"group\") VALUES('inst-chain','Synthetic','pilot') ON CONFLICT DO NOTHING")
        for((id,pid) in listOf(B1 to "inst-p1",B2 to "inst-p2")){
            jdbc.update("INSERT INTO pharmacies(id,name,chain_id,chain_name,city,\"group\") VALUES(?,?,'inst-chain','Synthetic','Test','pilot') ON CONFLICT DO NOTHING",pid,pid)
            source("merch_branches",mapOf("id" to id,"organization_id" to org,"code" to id.toString(),"name" to "Synthetic branch","address" to "Synthetic address","city" to "Test","active" to true,"epharm_pharmacy_id" to pid,"pharmacy_binding_verified" to true))
        }
        producer=source("merch_producers",mapOf("name" to "Synthetic producer","active" to true))
        other=source("merch_producers",mapOf("name" to "Other producer","active" to true))
        own=bearer(producer,"+77000000001");foreign=bearer(other,"+77000000002")
        shelf=shelf(B1);plan=plan(producer,listOf(shelf))
        pdf=ByteArrayOutputStream().also{out->PDDocument().use{it.addPage(PDPage());it.save(out)}}.toByteArray()
    }
    private fun grant(all: Boolean=false,branches: List<UUID> = listOf(B1),actions: List<String> = listOf("view","edit","approve")){
        jdbc.update("UPDATE unified_admin_profiles SET grants_json=?,version=version+1 WHERE account_id=?",json.writeValueAsString(actions.map{
            mapOf("module" to "merchandising","subsection" to "placements","action" to it,"allMerchBranches" to all,"merchBranchIds" to if(all)emptyList()else branches.map(UUID::toString),"allProducers" to true)}),account.id)
    }
    private fun source(table: String,fields: Map<String,Any?>): UUID{
        val id=fields["id"] as? UUID?:UUID.randomUUID();val row=linkedMapOf<String,Any?>("id" to id,"source_system" to "MERCH_CRM","source_origin" to "https://crm.inkar.kz","source_id" to id,"source_payload" to "{\"synthetic\":true}","created_at" to Timestamp.from(Instant.now()))
        if(table!="merch_media_plan_items")row["updated_at"]=Timestamp.from(Instant.now());row.putAll(fields)
        named.update("INSERT INTO $table(${row.keys.joinToString(",")}) VALUES(${row.keys.joinToString(","){if(it in setOf("source_payload","specifications"))"CAST(:$it AS jsonb)"else":$it"}})",row);return id
    }
    private fun shelf(branch: UUID)=source("merch_shelves",mapOf("branch_id" to branch,"code" to UUID.randomUUID().toString(),"category" to "AA","width_m" to 0.8,"base_monthly_price" to 30000,"active" to true,"place_type" to "shelf","position_index" to 0,"specifications" to "{}","sale_mode" to "whole_only"))
    private fun plan(owner: UUID,shelves: List<UUID>,status: String="approved"): UUID{
        val id=source("merch_media_plans",mapOf("producer_id" to owner,"advertiser_name" to "Synthetic","placement_month" to LocalDate.now().withDayOfMonth(1),"status" to status,"origin" to "request","total_amount" to 30000,"submitted_at" to Timestamp.from(Instant.now())))
        shelves.forEach{source("merch_media_plan_items",mapOf("media_plan_id" to id,"shelf_id" to it,"placement_month" to LocalDate.now().withDayOfMonth(1),"monthly_price" to 30000,"status" to "approved","sale_portion" to "whole"))};return id
    }
    private fun bearer(id: UUID,phone: String): String{
        jdbc.update("UPDATE merch_producers SET phone=?,phone_normalized=?,phone_verified_at=now() WHERE id=?",phone,phone,id)
        val now=Instant.now();val expiry=now.plusSeconds(86400);val principal=ProducerPrincipal(id,UUID.randomUUID());val token=producerTokens.issue(principal,now,expiry)
        jdbc.update("INSERT INTO merch_producer_sessions(id,customer_id,access_token_hash,phone_fingerprint,created_at,expires_at) VALUES(?,?,?,?,?,?)",principal.sessionId,id,ProducerTokenCodec.hash(token),producerTokens.fingerprint(id,phone),Timestamp.from(now),Timestamp.from(expiry));return "Bearer $token"
    }
    private fun metadata(id: UUID=UUID.randomUUID(),plans: List<UUID> = listOf(plan),adminBody: Boolean=false)=linkedMapOf<String,Any>("id" to id,"planIds" to plans,"title" to "Synthetic instruction","instructions" to "Record a demonstration video","dueAt" to Instant.now().plusSeconds(86400)).also{if(adminBody)it["producerId"]=producer}
    private fun upload(body: Any=metadata(),token: String=own,bytes: ByteArray=pdf)=mvc.perform(multipart(if(token==admin)"/api/admin/merch/instructions"else"/api/producer/instructions")
        .file(MockMultipartFile("metadata","metadata.json","application/json",json.writeValueAsBytes(body))).file(MockMultipartFile("file","instructions.pdf","application/pdf",bytes)).header("Authorization",token)).andReturn()
    private fun review(id: String,revision: Long=0,decision: String="approved")=mvc.perform(post("/api/admin/merch/instructions/$id/review").header("Authorization",admin).contentType("application/json")
        .content(json.writeValueAsBytes(mapOf("expectedRevision" to revision,"decision" to decision,"reason" to "Synthetic checked decision")))).andReturn()
    private fun get(path: String,token: String=own)=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).header("Authorization",token)).andReturn()
    private fun body(result: org.springframework.test.web.servlet.MvcResult)=json.readTree(result.response.contentAsByteArray)
    private fun count(table: String)=jdbc.queryForObject("SELECT count(*) FROM $table",Int::class.java)!!

    @Test fun `own producer upload is pending private hashed and exact retry cannot create duplicates`() {
        val data=metadata();val first=upload(data);assertThat(first.response.status).isEqualTo(200)
        val id=body(first)["id"].asText();assertThat(body(first)["status"].asText()).isEqualTo("pending")
        assertThat(body(first)["canReview"].asBoolean()).isFalse();assertThat(body(first).toString()).doesNotContain("object_key","source_payload","created_by")
        assertThat(upload(data).response.status).isEqualTo(200);assertThat(count("merch_instruction_materials")).isEqualTo(1);assertThat(count("merch_instruction_events")).isEqualTo(1)
        assertThat(upload(data+mapOf("title" to "Different intent")).response.status).isEqualTo(409)
        val download=get("/api/producer/instructions/$id/pdf")
        assertThat(download.response.status).isEqualTo(200);assertThat(download.response.contentAsByteArray).isEqualTo(pdf)
        assertThat(download.response.getHeader("X-Content-SHA256")).isEqualTo(TaskEvidenceStorage.hash(pdf));assertThat(download.response.getHeader("Cache-Control")).contains("no-store")
    }
    @Test fun `producer ownership is exact and producer JWT cannot approve or impersonate HQ`() {
        val id=body(upload())["id"].asText()
        assertThat(get("/api/producer/instructions/$id/pdf",foreign).response.status).isEqualTo(403)
        assertThat(body(get("/api/producer/instructions",foreign))["total"].asInt()).isZero()
        assertThat(upload(metadata()+mapOf("producerId" to other)).response.status).isEqualTo(400)
        assertThat(upload(metadata(plans=listOf(plan(other,listOf(shelf))))).response.status).isEqualTo(403)
        assertThat(get("/api/admin/merch/instructions",own).response.status).isIn(401,403)
    }
    @Test fun `approval creates one available zero reward packet per branch and replay cannot duplicate`() {
        val second=shelf(B2);val p2=plan(producer,listOf(second));val id=body(upload(metadata(plans=listOf(plan,p2))))["id"].asText()
        val result=review(id);assertThat(result.response.status).isEqualTo(200);assertThat(body(result)["status"].asText()).isEqualTo("approved")
        assertThat(count("merch_task_dispatches")).isEqualTo(2);assertThat(count("merch_task_items")).isEqualTo(2)
        assertThat(jdbc.queryForObject("SELECT sum(reward_amount_kzt) FROM merch_task_dispatches",Long::class.java)).isZero()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merch_task_items WHERE training_material_id=? AND required_photo_count=0 AND available_from<=now()",Int::class.java,UUID.fromString(id))).isEqualTo(2)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merch_task_dispatches WHERE source_mass_owner_id IS NOT NULL",Int::class.java)).isZero()
        assertThat(review(id).response.status).isEqualTo(409);assertThat(count("merch_task_dispatches")).isEqualTo(2)
    }
    @Test fun `partial branch scope cannot approve whole material or learn aggregate counts`() {
        val id=body(upload(metadata(plans=listOf(plan,plan(producer,listOf(shelf(B2)))))))["id"].asText()
        grant()
        assertThat(body(get("/api/admin/merch/instructions",admin))["total"].asInt()).isZero()
        assertThat(review(id).response.status).isEqualTo(403);assertThat(count("merch_task_dispatches")).isZero()
    }
    @Test fun `unmapped branch ghost is rejected atomically without approving material`() {
        val id=body(upload())["id"].asText();jdbc.update("UPDATE merch_branches SET epharm_pharmacy_id=NULL,pharmacy_binding_verified=false WHERE id=?",B1)
        assertThat(review(id).response.status).isEqualTo(409);assertThat(count("merch_task_dispatches")).isZero()
        assertThat(jdbc.queryForObject("SELECT status FROM merch_instruction_materials",String::class.java)).isEqualTo("pending")
    }
    @Test fun `unapproved parent and expired material cannot produce undeliverable tasks`() {
        val id=body(upload())["id"].asText();jdbc.update("UPDATE merch_media_plans SET status='pending' WHERE id=?",plan)
        assertThat(review(id).response.status).isEqualTo(409)
        jdbc.update("UPDATE merch_media_plans SET status='approved' WHERE id=?",plan);jdbc.update("UPDATE merch_instruction_materials SET due_at=now()-interval '1 minute'")
        assertThat(review(id).response.status).isEqualTo(409);assertThat(count("merch_task_dispatches")).isZero()
    }
    @Test fun `rejection is CAS bounded records reason and never generates a packet`() {
        val id=body(upload())["id"].asText()
        assertThat(review(id,revision=88,decision="rejected").response.status).isEqualTo(409)
        assertThat(review(id,decision="rejected").response.status).isEqualTo(200)
        assertThat(count("merch_task_dispatches")).isZero();assertThat(body(get("/api/producer/instructions"))["items"][0]["reviewNote"].asText()).isEqualTo("Synthetic checked decision")
    }
    @Test fun `fake PDF body and foreign private keys never enter storage metadata`() {
        assertThat(upload(bytes="<html>fake</html>".toByteArray()).response.status).isEqualTo(400)
        assertThat(upload(metadata()+mapOf("objectKey" to "https://example.test/secret.pdf")).response.status).isEqualTo(400)
        assertThat(count("merch_instruction_materials")).isZero()
    }
    @Test fun `concurrent approval single spends decision and emits exactly one packet`() {
        val id=body(upload())["id"].asText();val pool=Executors.newFixedThreadPool(2)
        try { val results=(1..2).map{pool.submit<Int>{review(id).response.status}}.map{it.get(15,TimeUnit.SECONDS)}.sorted();assertThat(results).containsExactly(200,409);assertThat(count("merch_task_dispatches")).isEqualTo(1) }finally{pool.shutdownNow()}
    }
    @Test fun `supplier sees only completed approved video evidence without executor or reviewer PII`() {
        val id=body(upload())["id"].asText();assertThat(review(id).response.status).isEqualTo(200)
        val item=jdbc.queryForObject("SELECT id FROM merch_task_items",UUID::class.java)!!;val executor=UUID.randomUUID();val attempt=UUID.randomUUID();val evidence=UUID.randomUUID()
        jdbc.update("INSERT INTO unified_persons(id,verification_reference) VALUES(?,'synthetic-executor')",executor)
        jdbc.update("INSERT INTO merch_task_attempts(id,item_id,attempt_no,executor_person_id,status,created_at) VALUES(?,?,1,?,'approved',now())",attempt,item,executor)
        jdbc.update("INSERT INTO merch_task_reviews(id,item_id,attempt_id,reviewer_account_id,reviewer_person_id,decision,reason,created_at) VALUES(?,?,?,?,?,'approve','Synthetic checked',now())",UUID.randomUUID(),item,attempt,account.id,person)
        val bytes="synthetic-approved-video".toByteArray();val key="instruction-tests/$evidence.mp4";val sha=storage.write(key,bytes)
        jdbc.update("INSERT INTO merch_task_evidence(id,item_id,attempt_id,client_upload_id,storage_backend,object_key,mime_type,byte_size,sha256,created_at) VALUES(?,?,?,?,'private_host',?,'video/mp4',?,?,now())",evidence,item,attempt,UUID.randomUUID(),key,bytes.size.toLong(),sha)
        assertThat(body(get("/api/producer/instructions/$id/results"))["total"].asInt()).isZero()
        jdbc.update("UPDATE merch_task_items SET status='completed' WHERE id=?",item)
        val result=body(get("/api/producer/instructions/$id/results"));assertThat(result["total"].asInt()).isEqualTo(1)
        assertThat(result.toString()).doesNotContain(executor.toString(),account.id.toString(),"executorPersonId","reviewerAccountId","object_key")
        assertThat(get("/api/producer/instructions/$id/results/$evidence/file").response.contentAsByteArray).isEqualTo(bytes)
        assertThat(get("/api/producer/instructions/$id/results/$evidence/file",foreign).response.status).isEqualTo(403)
        jdbc.update("UPDATE merch_task_attempts SET status='draft' WHERE id=?",attempt)
        assertThat(body(get("/api/producer/instructions/$id/results"))["total"].asInt()).isZero()
        assertThat(get("/api/producer/instructions/$id/results/$evidence/file").response.status).isEqualTo(404)
    }
    @Test fun `corrupt evidence and review parent references cannot expose another item video and remain immutable`() {
        val id=body(upload())["id"].asText();assertThat(review(id).response.status).isEqualTo(200)
        val ownItem=jdbc.queryForObject("SELECT id FROM merch_task_items WHERE training_material_id=?",UUID::class.java,UUID.fromString(id))!!
        val foreignPlan=plan(other,listOf(shelf(B2)))
        val foreignId=body(upload(metadata(plans=listOf(foreignPlan)),foreign))["id"].asText()
        assertThat(review(foreignId).response.status).isEqualTo(200)
        val foreignItem=jdbc.queryForObject("SELECT id FROM merch_task_items WHERE training_material_id=?",UUID::class.java,UUID.fromString(foreignId))!!
        jdbc.update("UPDATE merch_task_items SET status='completed' WHERE id IN (?,?)",ownItem,foreignItem)
        val executor=UUID.randomUUID();jdbc.update("INSERT INTO unified_persons(id,verification_reference) VALUES(?,'synthetic-parent-fence')",executor)
        val evidenceIds=mutableListOf<UUID>()
        for(reviewMismatch in listOf(false,true)) {
            val attempt=UUID.randomUUID();val evidence=UUID.randomUUID();evidenceIds+=evidence
            jdbc.update("INSERT INTO merch_task_attempts(id,item_id,attempt_no,executor_person_id,status,created_at) VALUES(?,?,?,?,'approved',now())",attempt,ownItem,if(reviewMismatch)2 else 1,executor)
            jdbc.update("INSERT INTO merch_task_reviews(id,item_id,attempt_id,reviewer_account_id,reviewer_person_id,decision,reason,created_at) VALUES(?,?,?,?,?,'approve','Synthetic parent fence',now())",UUID.randomUUID(),if(reviewMismatch)foreignItem else ownItem,attempt,account.id,person)
            val key="instruction-tests/$evidence.mp4";val bytes="private-foreign-video-$evidence".toByteArray();val sha=storage.write(key,bytes)
            jdbc.update("INSERT INTO merch_task_evidence(id,item_id,attempt_id,client_upload_id,storage_backend,object_key,mime_type,byte_size,sha256,created_at) VALUES(?,?,?,?,'private_host',?,'video/mp4',?,?,now())",evidence,if(reviewMismatch)ownItem else foreignItem,attempt,UUID.randomUUID(),key,bytes.size.toLong(),sha)
        }
        val before=jdbc.queryForList("SELECT id,item_id,attempt_id,object_key,sha256 FROM merch_task_evidence ORDER BY id")
        val result=body(get("/api/producer/instructions/$id/results"));assertThat(result["total"].asInt()).isZero()
        assertThat(result.toString()).doesNotContain(executor.toString(),account.id.toString(),foreignItem.toString(),"object_key")
        evidenceIds.forEach { assertThat(get("/api/producer/instructions/$id/results/$it/file").response.status).isEqualTo(404) }
        assertThat(jdbc.queryForList("SELECT id,item_id,attempt_id,object_key,sha256 FROM merch_task_evidence ORDER BY id")).isEqualTo(before)
    }
    @Test fun `empty branch plan is scoped to its exact producer rather than requiring all producers`() {
        val emptyPlan=plan(producer,emptyList());val foreignPlan=plan(other,emptyList())
        jdbc.update("UPDATE unified_admin_profiles SET grants_json=?,version=version+1 WHERE account_id=?",json.writeValueAsString(listOf("view","edit","approve").map {
            mapOf("module" to "merchandising","subsection" to "placements","action" to it,"allMerchBranches" to true,"merchBranchIds" to emptyList<String>(),"allProducers" to false,"producerIds" to listOf(producer.toString()))
        }),account.id)
        val targets=body(get("/api/admin/merch/instructions/targets",admin)).toString()
        assertThat(targets).contains(emptyPlan.toString()).doesNotContain(foreignPlan.toString())
        val created=upload(metadata(plans=listOf(emptyPlan),adminBody=true),admin)
        assertThat(created.response.status).isEqualTo(200);assertThat(body(created)["canReview"].asBoolean()).isTrue()
        assertThat(review(body(created)["id"].asText()).response.status).isEqualTo(409)
        assertThat(count("merch_task_dispatches")).isZero()
    }
}
