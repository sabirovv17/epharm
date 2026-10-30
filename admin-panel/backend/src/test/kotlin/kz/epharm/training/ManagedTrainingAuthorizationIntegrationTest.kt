package kz.epharm.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kz.epharm.auth.access.StoredGrant
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.entity.AdminUserEntity
import kz.epharm.auth.repository.AdminUserRepository
import kz.epharm.auth.service.JwtService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.MvcResult
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.util.UUID

@SpringBootTest(properties=["app.merch.automation.enabled=false"])
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class ManagedTrainingAuthorizationIntegrationTest {
    companion object {
        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("managed_training_disposable").withUsername("epharm").withPassword("synthetic-only").apply { start() }
        @JvmStatic @DynamicPropertySource fun props(r: DynamicPropertyRegistry) {
            r.add("spring.datasource.url") { postgres.jdbcUrl }; r.add("spring.datasource.username") { postgres.username }; r.add("spring.datasource.password") { postgres.password }
        }
    }
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var users: AdminUserRepository
    @Autowired lateinit var jwt: JwtService
    private lateinit var admin: AdminUserEntity
    private lateinit var token: String
    private lateinit var program: UUID
    private lateinit var event: UUID
    private lateinit var own: UUID
    private lateinit var foreign: UUID
    private lateinit var ownParticipant: UUID
    private lateinit var foreignParticipant: UUID

    @BeforeEach fun seed() {
        jdbc.execute("TRUNCATE training_programs,training_assignments,offline_events,pharmacists,pharmacies,chains,admin_users CASCADE")
        admin = users.saveAndFlush(AdminUserEntity(email="training-scope@example.test",name="Synthetic training manager",
            passwordHash="unused",roleRaw=AdminRole.TRAINING_MANAGER.name))
        token = jwt.issueAccessToken(admin)
        jdbc.update("INSERT INTO chains(id,name,color,\"group\",points) VALUES('training-chain','Synthetic','#000000','pilot',0)")
        for ((id,city) in listOf("training-a" to "City A","training-b" to "City B")) {
            jdbc.update("""INSERT INTO pharmacies(id,name,chain_id,chain_name,city,district,addr,"group")
                VALUES(?,?,'training-chain','Synthetic',?,'','Demo','pilot')""",id,id,city)
            jdbc.update("""INSERT INTO pharmacists(id,name,iin,phone,pharmacy_id,pharmacy_name,city,tier,status)
                VALUES(?,?,?,?,?,?,?,'Silver','active')""", "staff-$id", "Synthetic $id",
                if(id.endsWith("a")) "000000000001" else "000000000002",
                if(id.endsWith("a")) "+77000000001" else "+77000000002",id,id,city)
        }
        val p = send("POST", "/api/admin/training/programs", mapOf("name" to "Synthetic training", "allowedFormats" to listOf("offline"),
            "status" to "published", "completionBonus" to 100))
        assertThat(p.response.status).isEqualTo(200); program=UUID.fromString(body(p)["id"].asText())
        val e=send("POST","/api/admin/training/events",mapOf("programId" to program,"title" to "Synthetic shared event",
            "startsAt" to Instant.now().plusSeconds(86400),"endsAt" to Instant.now().plusSeconds(90000),
            "region" to "City A","city" to "City A","capacity" to 10,"status" to "scheduled"))
        assertThat(e.response.status).isEqualTo(200);event=UUID.fromString(body(e)["id"].asText())
        val a=send("POST","/api/admin/training/assignments",mapOf("programId" to program,"pharmacistIds" to listOf("staff-training-a","staff-training-b"),
            "format" to "offline","eventId" to event))
        assertThat(a.response.status).isEqualTo(200)
        own=jdbc.queryForObject("SELECT id FROM training_assignments WHERE pharmacist_id='staff-training-a'",UUID::class.java)!!
        foreign=jdbc.queryForObject("SELECT id FROM training_assignments WHERE pharmacist_id='staff-training-b'",UUID::class.java)!!
        ownParticipant=jdbc.queryForObject("SELECT id FROM event_participants WHERE assignment_id=?",UUID::class.java,own)!!
        foreignParticipant=jdbc.queryForObject("SELECT id FROM event_participants WHERE assignment_id=?",UUID::class.java,foreign)!!
        val person=UUID.randomUUID()
        jdbc.update("INSERT INTO unified_persons(id,verification_reference) VALUES(?,'synthetic-training-proof')",person)
        jdbc.update("INSERT INTO unified_admin_profiles(account_id,person_id) VALUES(?,?)",admin.id,person)
        jdbc.update("UPDATE admin_users SET access_mode='MANAGED' WHERE id=?",admin.id)
    }

    private fun grant(section: String, action: String="view", all: Boolean=false, ids: Set<String> = setOf("training-a")) =
        StoredGrant("training",section,action,all,if(all) emptySet() else ids)
    private fun grants(vararg values: StoredGrant) { jdbc.update("UPDATE unified_admin_profiles SET grants_json=?,version=version+1 WHERE account_id=?",json.writeValueAsString(values.toList()),admin.id) }
    private fun send(method:String,path:String,payload:Any?=null):MvcResult = mvc.perform(
        when(method) { "POST"->post(path);"PATCH"->patch(path);"DELETE"->delete(path);else->get(path) }
            .header("Authorization","Bearer $token").contentType(MediaType.APPLICATION_JSON)
            .also { if(payload!=null) it.content(json.writeValueAsBytes(payload)) }).andReturn()
    private fun body(result:MvcResult):JsonNode = json.readTree(result.response.contentAsByteArray)
    private fun count(table:String)=jdbc.queryForObject("SELECT count(*) FROM $table",Long::class.java)!!

    @Test fun `assignment rows pagination totals dashboard and event occupancy use the same current pharmacy scope`() {
        grants(grant("assignments"),grant("overview"),grant("events"),grant("attendance"))
        val list=send("GET","/api/admin/training/assignments");assertThat(list.response.status).isEqualTo(200)
        assertThat(body(list).map {it["id"].asText()}).containsExactly(own.toString())
        val page=body(send("GET","/api/admin/training/assignments/page?size=1&page=0"))
        assertThat(page["total"].asInt()).isEqualTo(1);assertThat(page["items"].size()).isEqualTo(1)
        val dashboard=body(send("GET","/api/admin/training/dashboard"))
        assertThat(dashboard["totalAssignments"].asInt()).isEqualTo(1);assertThat(dashboard["rewardsIssued"].isNull).isTrue()
        val events=body(send("GET","/api/admin/training/events"))
        assertThat(events.single()["occupied"].asInt()).isEqualTo(1)
        val participants=body(send("GET","/api/admin/training/events/$event/participants"))
        assertThat(participants.map {it["id"].asText()}).containsExactly(ownParticipant.toString())
    }

    @Test fun `foreign participant cannot be accepted and no grade progress reward or wallet row changes`() {
        grants(grant("attendance","mark"))
        val result=send("PATCH","/api/admin/training/events/$event/participants/$foreignParticipant",mapOf("status" to "attended","method" to "manual","score" to 90))
        assertThat(result.response.status).isEqualTo(403)
        assertThat(jdbc.queryForObject("SELECT status FROM event_participants WHERE id=?",String::class.java,foreignParticipant)).isEqualTo("registered")
        assertThat(count("training_rewards")).isZero();assertThat(count("pharmacist_wallet_entries")).isZero()
        assertThat(jdbc.queryForObject("SELECT progress_pct FROM training_assignments WHERE id=?",Int::class.java,foreign)).isZero()
    }

    @Test fun `bulk assignment and preference mutations reject a mixed foreign target before any write`() {
        grants(grant("assignments","assign"),grant("preferences","edit"))
        val original=count("training_assignments")
        assertThat(send("POST","/api/admin/training/assignments",mapOf("programId" to program,"pharmacistIds" to listOf("staff-training-a","staff-training-b"),"format" to "offline")).response.status).isEqualTo(403)
        assertThat(send("PATCH","/api/admin/training/pharmacists/preferences",mapOf("pharmacistIds" to listOf("staff-training-a","staff-training-b"),"defaultFormat" to "offline","reason" to "Synthetic bulk" )).response.status).isEqualTo(403)
        assertThat(count("training_assignments")).isEqualTo(original);assertThat(count("pharmacist_training_preferences")).isZero()
    }

    @Test fun `individual history profile results and stage result reject foreign IDs and mismatched parents`() {
        grants(grant("assignments"),grant("profiles"),grant("preferences"),grant("results"),grant("results","record_result"))
        for(path in listOf("/api/admin/training/assignments/$foreign/format-history","/api/admin/training/assignments/$foreign/results",
            "/api/admin/training/pharmacists/staff-training-b/profile","/api/admin/training/pharmacists/staff-training-b/preference-history")) {
            assertThat(send("GET",path).response.status).isEqualTo(403)
        }
        val foreignStage=jdbc.queryForList("SELECT id FROM training_assignment_stages WHERE assignment_id=?",UUID::class.java,foreign).first()
        assertThat(send("PATCH","/api/admin/training/assignments/$own/stages/$foreignStage/result",mapOf("score" to 90)).response.status).isEqualTo(400)
        assertThat(count("training_assessment_results")).isZero()
    }

    @Test fun `moving an employee removes their learning history from the former manager without rewriting the assignment`() {
        grants(grant("assignments"),grant("profiles"))
        val before=jdbc.queryForMap("SELECT * FROM training_assignments WHERE id=?",own)
        jdbc.update("UPDATE pharmacists SET pharmacy_id='training-b',city='City B' WHERE id='staff-training-a'")
        assertThat(body(send("GET","/api/admin/training/assignments")).size()).isZero()
        assertThat(send("GET","/api/admin/training/pharmacists/staff-training-a/profile").response.status).isEqualTo(403)
        assertThat(jdbc.queryForMap("SELECT * FROM training_assignments WHERE id=?",own)).isEqualTo(before)
    }

    @Test fun `a certificate linked to a visible assignment cannot disclose a different employee or its QR`() {
        grants(grant("certificates"),grant("assignments"),grant("profiles"))
        val certificate=UUID.randomUUID()
        jdbc.update("""INSERT INTO training_certificates(id,certificate_number,assignment_id,pharmacist_id,program_version_id,format,issued_at,qr_token)
            SELECT ?,'SYNTHETIC-MISMATCH',id,'staff-training-b',program_version_id,'offline',now(),? FROM training_assignments WHERE id=?""",
            certificate,UUID.randomUUID(),own)
        val before=jdbc.queryForMap("SELECT * FROM training_certificates WHERE id=?",certificate)
        for(path in listOf("/api/admin/training/certificates","/api/admin/training/assignments","/api/admin/training/pharmacists/staff-training-a/profile")) {
            val result=send("GET",path)
            assertThat(result.response.status).isEqualTo(409)
            assertThat(result.response.contentAsString).doesNotContain("Synthetic training-b",before["qr_token"].toString())
        }
        assertThat(jdbc.queryForMap("SELECT * FROM training_certificates WHERE id=?",certificate)).isEqualTo(before)
    }

    @Test fun `revocation and empty assigned grants deny the same previously valid JWT`() {
        grants(grant("assignments"));assertThat(send("GET","/api/admin/training/assignments").response.status).isEqualTo(200)
        grants();assertThat(send("GET","/api/admin/training/assignments").response.status).isEqualTo(403)
        grants(grant("assignments",ids=emptySet()));assertThat(send("GET","/api/admin/training/assignments").response.status).isEqualTo(403)
    }

    @Test fun `shared authoring requires ALL and publication and nonzero bonuses require their exact additional grants`() {
        grants(grant("programs","create"))
        val draft=mapOf("name" to "Synthetic draft","allowedFormats" to listOf("offline"))
        assertThat(send("POST","/api/admin/training/programs",draft).response.status).isEqualTo(403)
        grants(grant("programs","create",all=true))
        assertThat(send("POST","/api/admin/training/programs",draft).response.status).isEqualTo(200)
        assertThat(send("POST","/api/admin/training/programs",draft+mapOf("status" to "published")).response.status).isEqualTo(403)
        assertThat(send("POST","/api/admin/training/programs",draft+mapOf("completionBonus" to 100)).response.status).isEqualTo(403)
        grants(grant("programs","create",all=true),grant("programs","publish",all=true),grant("programs","configure_bonus",all=true))
        assertThat(send("POST","/api/admin/training/programs",draft+mapOf("status" to "published","completionBonus" to 100)).response.status).isEqualTo(200)
    }

    @Test fun `shared event editing cannot be delegated through a single participants pharmacy`() {
        grants(grant("events","edit"))
        assertThat(send("PATCH","/api/admin/training/events/$event",mapOf("title" to "Not permitted")).response.status).isEqualTo(403)
        assertThat(jdbc.queryForObject("SELECT title FROM offline_events WHERE id=?",String::class.java,event)).isEqualTo("Synthetic shared event")
    }

    @Test fun `adding a zero bonus stage needs edit but removing an existing nonzero stage bonus still needs financial permission`() {
        jdbc.update("UPDATE training_programs SET status='draft' WHERE id=?",program)
        grants(grant("programs","edit",all=true))
        val stages=jdbc.queryForList("""SELECT s.* FROM training_program_stages s JOIN training_program_versions v ON v.id=s.version_id
            WHERE v.program_id=? AND v.version_no=1 ORDER BY s.order_no""",program).map { row ->
            mapOf("key" to row["stage_key"],"type" to row["type"],"title" to row["title"],"order" to row["order_no"],
                "required" to row["required"],"passingScore" to row["passing_score"],"maxAttempts" to row["max_attempts"],
                "bonus" to 0,"applicableFormats" to listOf("offline"))
        } + mapOf("key" to "optional-zero","type" to "material","title" to "Optional zero bonus stage","order" to 99,
            "required" to false,"bonus" to 0,"applicableFormats" to listOf("offline"))
        assertThat(send("PATCH","/api/admin/training/programs/$program",mapOf("stages" to stages)).response.status).isEqualTo(200)
        assertThat(jdbc.queryForObject("SELECT current_version FROM training_programs WHERE id=?",Int::class.java,program)).isEqualTo(2)
        jdbc.update("""UPDATE training_program_stages SET bonus=50 WHERE stage_key='optional-zero'
            AND version_id=(SELECT id FROM training_program_versions WHERE program_id=? AND version_no=2)""",program)
        assertThat(send("PATCH","/api/admin/training/programs/$program",mapOf("stages" to stages)).response.status).isEqualTo(403)
        assertThat(jdbc.queryForObject("SELECT current_version FROM training_programs WHERE id=?",Int::class.java,program)).isEqualTo(2)
        assertThat(jdbc.queryForObject("""SELECT s.bonus FROM training_program_stages s JOIN training_program_versions v ON v.id=s.version_id
            WHERE v.program_id=? AND v.version_no=2 AND s.stage_key='optional-zero'""",Long::class.java,program)).isEqualTo(50L)
    }

    @Test fun `managed report uses XLSX and never accidentally enables the legacy CSV compatibility action`() {
        grants(grant("assignments"),grant("assignments","export"))
        assertThat(send("GET","/api/admin/training/assignments/export.csv").response.status).isEqualTo(409)
        val xlsx=send("GET","/api/admin/training/exports/assignments.xlsx")
        assertThat(xlsx.response.status).isEqualTo(200);assertThat(xlsx.response.contentType).contains("spreadsheetml")
        grants(grant("assignments","export"))
        assertThat(send("GET","/api/admin/training/exports/assignments.xlsx").response.status).isEqualTo(403)
    }

    @Test fun `LMS and question authoring honor ALL and cannot smuggle publication or bonus through generic edit`() {
        grants(grant("courses","create"))
        assertThat(send("POST","/api/admin/lms/courses",mapOf("title" to "Synthetic course")).response.status).isEqualTo(403)
        grants(grant("courses","create",all=true),grant("courses","edit",all=true),grant("question_bank","create",all=true),grant("question_bank","delete",all=true))
        val course=send("POST","/api/admin/lms/courses",mapOf("title" to "Synthetic course"))
        assertThat(course.response.status).isEqualTo(200);val id=body(course)["id"].asText()
        assertThat(send("PATCH","/api/admin/lms/courses/$id",mapOf("status" to "published")).response.status).isEqualTo(403)
        assertThat(send("PATCH","/api/admin/lms/courses/$id",mapOf("bonus" to 500)).response.status).isEqualTo(403)
        grants(grant("courses","edit",all=true),grant("courses","publish",all=true),grant("courses","configure_bonus",all=true))
        assertThat(send("PATCH","/api/admin/lms/courses/$id",mapOf("status" to "published","bonus" to 500)).response.status).isEqualTo(200)
        assertThat(jdbc.queryForObject("SELECT bonus FROM courses WHERE id=?",Int::class.java,id)).isEqualTo(500)
    }

    @Test fun `legacy HQ remains read only while legacy training authoring and learner access keep their original boundary`() {
        jdbc.update("UPDATE admin_users SET access_mode='LEGACY',role='HQ_HEAD' WHERE id=?",admin.id)
        assertThat(send("GET","/api/admin/training/assignments").response.status).isEqualTo(200)
        assertThat(body(send("GET","/api/admin/training/assignments")).size()).isEqualTo(2)
        assertThat(send("POST","/api/admin/training/programs",mapOf("name" to "Denied","allowedFormats" to listOf("offline"))).response.status).isEqualTo(403)
        val learner=jwt.issuePharmacistToken("staff-training-a","Synthetic learner","+77000000001")
        assertThat(mvc.perform(get("/api/mobile/training").header("Authorization","Bearer $learner")).andReturn().response.status).isEqualTo(200)
        assertThat(mvc.perform(get("/api/admin/training/assignments").header("Authorization","Bearer $learner")).andReturn().response.status).isEqualTo(403)
    }
}
