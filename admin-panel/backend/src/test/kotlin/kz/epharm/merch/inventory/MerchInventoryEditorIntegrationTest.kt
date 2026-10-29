package kz.epharm.merch.inventory

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
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
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.UUID
import javax.imageio.ImageIO

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class MerchInventoryEditorIntegrationTest {
    companion object {
        @Container @JvmStatic val postgres=PostgreSQLContainer("postgres:16-alpine").withDatabaseName("inventory_editor").withUsername("test").withPassword("test").apply{start()}
        private val fileRoot=Files.createTempDirectory("epharm-inventory-editor-test-").toRealPath().also{Files.setPosixFilePermissions(it,PosixFilePermissions.fromString("rwx------"))}
        @JvmStatic @DynamicPropertySource fun properties(reg: DynamicPropertyRegistry) {
            reg.add("spring.datasource.url"){postgres.jdbcUrl};reg.add("spring.datasource.username"){postgres.username};reg.add("spring.datasource.password"){postgres.password}
            reg.add("app.merch-tasks.evidence-root"){fileRoot.toString()}
        }
        val B1=UUID.fromString("10000000-0000-4000-8000-000000000001")
        val B2=UUID.fromString("20000000-0000-4000-8000-000000000002")
        val MONTH=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Almaty")).withDayOfMonth(1).plusMonths(1)
    }
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var named: NamedParameterJdbcTemplate
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var users: AdminUserRepository
    @Autowired lateinit var jwt: JwtService
    lateinit var account: AdminUserEntity
    lateinit var bearer: String
    lateinit var org: UUID
    lateinit var cabinet: UUID
    lateinit var shelf: UUID

    @BeforeEach fun seed() {
        jdbc.execute("TRUNCATE merch_inventory_editor_audit,merch_floor_plan_versions,merch_inventory_media,merch_organizations,admin_users,unified_persons CASCADE")
        account=users.saveAndFlush(AdminUserEntity(email="editor@example.test",name="Synthetic editor",passwordHash="unused",roleRaw=AdminRole.HQ_HEAD.name))
        bearer="Bearer ${jwt.issueAccessToken(account)}"
        jdbc.update("UPDATE admin_users SET access_mode='MANAGED' WHERE id=?",account.id)
        val person=UUID.randomUUID();jdbc.update("INSERT INTO unified_persons(id,verification_reference) VALUES(?,'synthetic-only')",person)
        jdbc.update("INSERT INTO unified_admin_profiles(account_id,person_id) VALUES(?,?)",account.id,person)
        org=source("merch_organizations",mapOf("name" to "Synthetic network","legal_name" to "Synthetic network","active" to true))
        for(id in listOf(B1,B2)) source("merch_branches",mapOf("id" to id,"organization_id" to org,"code" to id.toString(),"name" to "Synthetic branch","address" to "Synthetic street","city" to "Test","active" to true))
        cabinet=source("merch_inventory_containers",mapOf("branch_id" to B1,"code" to "A-01","name" to "Synthetic cabinet","container_type" to "cabinet","category" to "A","position_index" to 0,"active" to true,"metadata" to "{}"))
        shelf=shelf(B1,cabinet,"AA-01")
        grants("view","edit_structure","edit_passport","configure_prices","financial_data")
    }
    private fun grants(vararg actions: String,all: Boolean=false,branches: List<UUID> = listOf(B1)) {
        jdbc.update("UPDATE unified_admin_profiles SET grants_json=?,version=version+1 WHERE account_id=?",mapper.writeValueAsString(actions.map{
            mapOf("module" to "merchandising","subsection" to "inventory","action" to it,"allMerchBranches" to all,"merchBranchIds" to if(all)emptyList()else branches.map(UUID::toString))}),account.id)
    }
    private fun source(table: String,fields: Map<String,Any?>): UUID {
        val id=fields["id"] as? UUID?:UUID.randomUUID()
        val row=linkedMapOf<String,Any?>("id" to id,"source_system" to "MERCH_CRM","source_origin" to "https://crm.inkar.kz","source_id" to id,"source_payload" to "{\"synthetic\":true}","created_at" to java.sql.Timestamp.from(Instant.now()))
        if(table!="merch_media_plan_items")row["updated_at"]=java.sql.Timestamp.from(Instant.now())
        row.putAll(fields)
        named.update("INSERT INTO $table(${row.keys.joinToString(",")}) VALUES(${row.keys.joinToString(","){if(it in setOf("source_payload","metadata","specifications"))"CAST(:$it AS jsonb)"else":$it"}})",row)
        return id
    }
    private fun shelf(branch: UUID,container: UUID?,code: String)=source("merch_shelves",mapOf("branch_id" to branch,"container_id" to container,"code" to code,"category" to "AA","width_m" to java.math.BigDecimal("0.80"),"base_monthly_price" to 15000,"active" to true,"place_type" to "shelf","position_index" to 0,"specifications" to "{\"maxProducts\":12,\"sourceExtra\":true}","sale_mode" to "whole_only"))
    private fun invoke(method: String,path: String,body: Any?=null): MvcResult {
        val request=when(method){"GET"->get(path);"PUT"->put(path);else->post(path)}.header("Authorization",bearer)
        if(body!=null)request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body))
        return mvc.perform(request).andReturn()
    }
    private fun json(result: MvcResult)=mapper.readTree(result.response.contentAsByteArray)
    private fun editor(id: UUID=B1): JsonNode {
        val result=invoke("GET","/api/admin/merch/branches/$id/editor?month=$MONTH")
        assertThat(result.response.status).isEqualTo(200)
        return json(result)
    }
    private fun command(suffix: String,body: Any,method: String="POST",branch: UUID=B1)=invoke(method,"/api/admin/merch/branches/$branch/editor$suffix",body)
    private fun shelfBody(revision: Int=1,active: Boolean=true)=mapOf("expectedRevision" to revision,"containerId" to cabinet,"code" to "AA-01","category" to "AA","width" to 0.9,"position" to 1,"maxProducts" to 18,"active" to active)
    private fun plan()=mapOf("version" to 3,"room" to mapOf("width" to 18,"depth" to 11),"fixtures" to listOf(
        mapOf("id" to "cashier","type" to "cashier","label" to "Касса","x" to 9,"y" to 1,"width" to 4,"depth" to 1),
        mapOf("id" to "door","type" to "entrance","label" to "Вход","x" to 9,"y" to 10,"width" to 2,"depth" to 0.5)),
        "cabinetPositions" to listOf(mapOf("cabinetCode" to "A-01","x" to 2,"y" to 4,"width" to 1.4,"depth" to 0.55)))
    private fun placement(): UUID {
        val id=source("merch_media_plans",mapOf("advertiser_name" to "Synthetic producer","placement_month" to MONTH,"status" to "approved","origin" to "request","total_amount" to 19000,"submitted_at" to java.sql.Timestamp.from(Instant.now())))
        return source("merch_media_plan_items",mapOf("media_plan_id" to id,"shelf_id" to shelf,"placement_month" to MONTH,"monthly_price" to 19000,"status" to "approved","sale_portion" to "whole"))
    }

    @Test fun `editor is real scoped projection and withholds prices without financial grant`() {
        grants("view","edit_structure")
        val data=editor();assertThat(data["canEditStructure"].asBoolean()).isTrue();assertThat(data["canConfigurePrices"].asBoolean()).isFalse()
        assertThat(data["shelves"][0]["shelf"]["baseMonthlyPrice"].isNull).isTrue()
        assertThat(invoke("GET","/api/admin/merch/branches/$B2/editor?month=$MONTH").response.status).isEqualTo(403)
        grants("view",branches=emptyList())
        assertThat(invoke("GET","/api/admin/merch/branches/$B1/editor?month=$MONTH").response.status).isEqualTo(403)
    }
    @Test fun `structure cannot edit money or create implicitly priced active places`() {
        grants("view","edit_structure")
        val result=command("/shelves/$shelf",shelfBody(),"PUT");assertThat(result.response.status).isEqualTo(200)
        val create=shelfBody(2)+mapOf("code" to "NEW","initialPrice" to 30000)
        assertThat(command("/shelves",create).response.status).isEqualTo(403)
        assertThat(jdbc.queryForObject("SELECT base_monthly_price FROM merch_shelves WHERE id=?",Int::class.java,shelf)).isEqualTo(15000)
        assertThat(jdbc.queryForObject("SELECT specifications->>'sourceExtra' FROM merch_shelves WHERE id=?",String::class.java,shelf)).isEqualTo("true")
    }
    @Test fun `foreign child IDs stale revision and duplicate codes never mutate another branch`() {
        val foreign=shelf(B2,null,"FOREIGN")
        assertThat(command("/shelves/$foreign",shelfBody(),"PUT").response.status).isEqualTo(404)
        assertThat(command("/shelves/$shelf",shelfBody(99),"PUT").response.status).isEqualTo(409)
        assertThat(command("/shelves",shelfBody()+mapOf("initialPrice" to 30000)).response.status).isEqualTo(409)
        assertThat(editor()["revision"].asInt()).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merch_inventory_editor_audit",Int::class.java)).isZero()
    }
    @Test fun `global organizations and branch creation need explicit ALL scope and never invent pharmacy binding`() {
        assertThat(invoke("GET","/api/admin/merch/inventory/organizations").response.status).isEqualTo(403)
        grants("view","edit_structure",all=true)
        val created=invoke("POST","/api/admin/merch/inventory/organizations",mapOf("name" to "New test org","legalName" to "New legal test"))
        assertThat(created.response.status).isEqualTo(200)
        val result=invoke("POST","/api/admin/merch/inventory/branches",mapOf("organizationId" to json(created)["id"].asText(),"code" to "NEW-1","name" to "New branch","address" to "Synthetic address","city" to "Test"))
        assertThat(result.response.status).isEqualTo(200)
        val id=UUID.fromString(json(result)["branch"]["id"].asText())
        val row=jdbc.queryForMap("SELECT source_system,epharm_pharmacy_id,pharmacy_binding_verified FROM merch_branches WHERE id=?",id)
        assertThat(row["source_system"]).isEqualTo("EPHARM");assertThat(row["epharm_pharmacy_id"]).isNull();assertThat(row["pharmacy_binding_verified"]).isEqualTo(false)
    }
    @Test fun `archive preserves IDs source history and refuses a contracted container or branch`() {
        val item=placement()
        assertThat(command("/shelves/$shelf",shelfBody(active=false),"PUT").response.status).isEqualTo(409)
        assertThat(command("/containers/$cabinet",mapOf("expectedRevision" to 1,"code" to "A-01","name" to "Cabinet","category" to "A","active" to false),"PUT").response.status).isEqualTo(409)
        assertThat(jdbc.queryForObject("SELECT monthly_price FROM merch_media_plan_items WHERE id=?",Int::class.java,item)).isEqualTo(19000)
        jdbc.update("UPDATE merch_media_plan_items SET status='cancelled' WHERE id=?",item)
        assertThat(command("/shelves/$shelf",shelfBody(active=false),"PUT").response.status).isEqualTo(200)
        val row=jdbc.queryForMap("SELECT active,source_id,source_payload FROM merch_shelves WHERE id=?",shelf)
        assertThat(row["active"]).isEqualTo(false);assertThat(row["source_id"]).isEqualTo(shelf);assertThat(row["source_payload"].toString()).contains("synthetic")
    }
    @Test fun `month price edit protects approved quote while applyFuture updates only free future inventory`() {
        val contracted=placement()
        val result=command("/prices",mapOf("expectedRevision" to 1,"shelfIds" to listOf(shelf),"fromMonth" to MONTH,"toMonth" to MONTH.plusMonths(1),"price" to 33000,"applyFuture" to true))
        assertThat(result.response.status).isEqualTo(200)
        assertThat(json(result)["protectedCount"].asInt()).isEqualTo(1);assertThat(json(result)["changedCount"].asInt()).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT monthly_price FROM merch_media_plan_items WHERE id=?",Int::class.java,contracted)).isEqualTo(19000)
        assertThat(jdbc.queryForObject("SELECT monthly_price FROM merch_monthly_inventory WHERE shelf_id=? AND placement_month=?",Int::class.java,shelf,MONTH.plusMonths(1))).isEqualTo(33000)
    }
    @Test fun `cabinet configuration retains protected places and rollback is atomic when target impossible`() {
        placement()
        val counts=mapOf("AA" to 0,"AB" to 0,"BA" to 0,"BB" to 0,"CA" to 0,"CB" to 0)
        assertThat(command("/containers/$cabinet/configure",mapOf("expectedRevision" to 1,"counts" to counts)).response.status).isEqualTo(400)
        assertThat(editor()["revision"].asInt()).isEqualTo(1)
        assertThat(command("/containers/$cabinet/configure",mapOf("expectedRevision" to 1,"counts" to counts+mapOf("AA" to 2),"initialPrice" to 25000)).response.status).isEqualTo(200)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merch_shelves WHERE container_id=? AND active",Int::class.java,cabinet)).isEqualTo(2)
    }
    @Test fun `floor draft does not publish and CAS publish preserves an immutable version`() {
        assertThat(command("/floor-plan",mapOf("expectedRevision" to 1,"plan" to plan()),"PUT").response.status).isEqualTo(200)
        assertThat(editor()["floorPlan"].isNull).isTrue();assertThat(editor()["floorPlanDraft"].isObject).isTrue()
        assertThat(command("/floor-plan/publish",mapOf("expectedRevision" to 1)).response.status).isEqualTo(409)
        assertThat(command("/floor-plan/publish",mapOf("expectedRevision" to 2)).response.status).isEqualTo(200)
        assertThat(editor()["floorPlanDraft"].isNull).isTrue()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merch_floor_plan_versions",Int::class.java)).isEqualTo(1)
    }
    @Test fun `floor plan rejects overlap outside room and foreign or repeated cabinets`() {
        val overlap=plan()+mapOf("cabinetPositions" to listOf(mapOf("cabinetCode" to "A-01","x" to 9,"y" to 1,"width" to 1.4,"depth" to 0.55)))
        assertThat(command("/floor-plan",mapOf("expectedRevision" to 1,"plan" to overlap),"PUT").response.status).isEqualTo(400)
        val foreign=plan()+mapOf("cabinetPositions" to listOf(mapOf("cabinetCode" to "FOREIGN","x" to 2,"y" to 4,"width" to 1.4,"depth" to 0.55)))
        assertThat(command("/floor-plan",mapOf("expectedRevision" to 1,"plan" to foreign),"PUT").response.status).isEqualTo(400)
        assertThat(editor()["revision"].asInt()).isEqualTo(1)
    }
    @Test fun `legacy floor v2 is projected without rewriting source or saved published bytes`() {
        val raw="""{"version":2,"room":{"width":18,"depth":11},"cashier":{"x":9,"y":1},"entrance":{"x":9,"y":10},"cabinetPositions":[{"cabinetCode":"A-01","x":2,"y":4}]}"""
        jdbc.update("UPDATE merch_branches SET floor_plan=?::jsonb WHERE id=?",raw,B1)
        val before=jdbc.queryForObject("SELECT floor_plan::text FROM merch_branches WHERE id=?",String::class.java,B1)
        assertThat(editor()["floorPlan"]["version"].asInt()).isEqualTo(3)
        assertThat(jdbc.queryForObject("SELECT floor_plan::text FROM merch_branches WHERE id=?",String::class.java,B1)).isEqualTo(before)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merch_inventory_editor_audit",Int::class.java)).isZero()
    }
    @Test fun `photo upload uses private bytes scoped authenticated routes and archive retains evidence`() {
        val bytes=ByteArrayOutputStream().also{ImageIO.write(BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",it)}.toByteArray()
        val uploaded=mvc.perform(multipart("/api/admin/merch/branches/$B1/editor/photos").file(MockMultipartFile("file","test.png","image/png",bytes))
            .param("expectedRevision","1").param("angle","facade").header("Authorization",bearer)).andReturn()
        assertThat(uploaded.response.status).isEqualTo(200)
        val photo=editor()["photos"][0]["id"].asText()
        val downloaded=invoke("GET","/api/admin/merch/branches/$B1/editor/media/$photo")
        assertThat(downloaded.response.contentAsByteArray).isEqualTo(bytes);assertThat(downloaded.response.getHeader("Cache-Control")).contains("no-store")
        assertThat(invoke("GET","/api/admin/merch/branches/$B2/editor/media/$photo").response.status).isEqualTo(403)
        assertThat(command("/photos/$photo/archive",mapOf("expectedRevision" to 2)).response.status).isEqualTo(200)
        assertThat(editor()["photos"][0]["archived"].asBoolean()).isTrue()
        assertThat(invoke("GET","/api/admin/merch/branches/$B1/editor/media/$photo").response.contentAsByteArray).isEqualTo(bytes)
        assertThat(editor().toString()).doesNotContain("object_key","private_host",fileRoot.toString())
    }
    @Test fun `arbitrary media URL or executable bytes cannot become a passport photo`() {
        val result=mvc.perform(multipart("/api/admin/merch/branches/$B1/editor/photos").file(MockMultipartFile("file","bad.png","image/png","<svg onload='evil'>".toByteArray()))
            .param("expectedRevision","1").param("angle","facade").header("Authorization",bearer)).andReturn()
        assertThat(result.response.status).isEqualTo(400)
        assertThat(editor()["photos"].size()).isZero();assertThat(editor()["revision"].asInt()).isEqualTo(1)
    }
    @Test fun `native container and shelf creation persist exact explicit price and independent source IDs`() {
        assertThat(command("/containers",mapOf("expectedRevision" to 1,"code" to "A-02","name" to "New cabinet","category" to "A")).response.status).isEqualTo(200)
        val next=editor()["containers"].first{it["code"].asText()=="A-02"}["id"].asText()
        assertThat(command("/shelves",mapOf("expectedRevision" to 2,"containerId" to next,"code" to "AA-02","category" to "AA","width" to 0.8,"initialPrice" to 25000)).response.status).isEqualTo(200)
        val row=jdbc.queryForMap("SELECT source_system,id,source_id,base_monthly_price FROM merch_shelves WHERE branch_id=? AND code='AA-02'",B1)
        assertThat(row["source_system"]).isEqualTo("EPHARM");assertThat(row["id"]).isEqualTo(row["source_id"]);assertThat(row["base_monthly_price"]).isEqualTo(25000)
        assertThat(editor()["revision"].asInt()).isEqualTo(3)
    }
}
