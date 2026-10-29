package kz.epharm.reporting.training

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.ByteArrayInputStream
import java.util.UUID
import kz.epharm.auth.access.StoredGrant
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.entity.AdminUserEntity
import kz.epharm.auth.repository.AdminUserRepository
import kz.epharm.auth.service.JwtService
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test") @Testcontainers
class TrainingLearningExportIntegrationTest {
    companion object {
        @Container @JvmStatic val postgres=PostgreSQLContainer("postgres:16-alpine").withDatabaseName("training_learning_exports")
            .withUsername("epharm").withPassword("synthetic-only").apply {start()}
        @JvmStatic @DynamicPropertySource fun properties(reg:DynamicPropertyRegistry) {
            reg.add("spring.datasource.url") {postgres.jdbcUrl};reg.add("spring.datasource.username") {postgres.username};reg.add("spring.datasource.password") {postgres.password}
        }
    }
    @Autowired lateinit var jdbc:JdbcTemplate
    @Autowired lateinit var mvc:MockMvc
    @Autowired lateinit var users:AdminUserRepository
    @Autowired lateinit var jwt:JwtService
    @Autowired lateinit var json:ObjectMapper
    private lateinit var admin:AdminUserEntity
    private lateinit var otherTrainer:AdminUserEntity
    private lateinit var program:UUID
    private lateinit var version:UUID
    private lateinit var assignmentA:UUID
    private lateinit var assignmentB:UUID
    private lateinit var stageA:UUID
    private lateinit var stageB:UUID
    private lateinit var resultA:UUID
    private lateinit var ownEvent:UUID
    private lateinit var certificateQr:UUID
    private val auth get()="Bearer ${jwt.issueAccessToken(admin)}"
    private val privateMarker="synthetic-learning-private-detail"
    private fun grant(section:String,action:String,ids:Set<String>?=null)=StoredGrant("training",section,action,ids==null,ids.orEmpty())
    private fun grants(ids:Set<String>?=null) {setGrants(grant("assignments","view",ids),grant("assignments","export",ids),grant("results","view",ids),grant("results","export",ids))}
    private fun setGrants(vararg values:StoredGrant) {jdbc.update("update unified_admin_profiles set grants_json=?,version=version+1 where account_id=?",json.writeValueAsString(values),admin.id)}
    private fun request(kind:String)=get("/api/admin/training/exports/$kind.xlsx").header("Authorization",auth)
    private fun download(kind:String)=mvc.perform(request(kind)).andExpect(status().isOk).andExpect(header().string("Cache-Control","no-store"))
        .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).andReturn().response.contentAsByteArray
    private fun book(kind:String,action:(XSSFWorkbook)->Unit) {XSSFWorkbook(ByteArrayInputStream(download(kind))).use(action)}

    @BeforeEach fun seed() {
        // These are synthetic learning records in this class's disposable PG16, with no candidate/source connection.
        jdbc.execute("TRUNCATE training_programs,courses,training_assignments,pharmacists,pharmacies,chains,unified_admin_profiles,unified_persons,admin_users CASCADE")
        admin=users.saveAndFlush(AdminUserEntity(email="learning-exports@example.invalid",name=privateMarker,passwordHash=privateMarker,roleRaw=AdminRole.HQ_HEAD.name))
        otherTrainer=users.saveAndFlush(AdminUserEntity(email="other-training@example.invalid",name=privateMarker,passwordHash="unused",roleRaw=AdminRole.TRAINER.name))
        val person=UUID.randomUUID();jdbc.update("insert into unified_persons values(?,?)",person,"synthetic-reviewed-proof")
        jdbc.update("insert into unified_admin_profiles(account_id,person_id) values(?,?)",admin.id,person)
        jdbc.update("update admin_users set access_mode='MANAGED' where id=?",admin.id)
        jdbc.update("""insert into chains(id,name,color,"group",points) values('learning-chain','Business chain','#000000','pilot',0)""")
        for((id,city) in listOf("learning-a" to "City A","learning-b" to "City B")) jdbc.update("""insert into pharmacies(id,name,chain_id,chain_name,city,district,addr,"group")
            values(?,?,'learning-chain','Business chain',?,'','','pilot')""",id,"Business $id",city)
        for((id,pharmacy,city) in listOf(Triple("learning-person-a","learning-a","City A"),Triple("learning-person-b","learning-b","City B"))) jdbc.update("""insert into pharmacists(id,name,iin,phone,password_hash,pharmacy_id,pharmacy_name,city,tier,status,joined_at,balance)
            values(?,?,?,?,?,?,?,?,'Silver','active',current_date,700)""",id,"Synthetic public ${id.takeLast(1)}",if(id.endsWith("a")) "000000000001" else "000000000002",
            if(id.endsWith("a")) "+77000000001" else "+77000000002",privateMarker,pharmacy,"Business $pharmacy",city)
        program=UUID.randomUUID();version=UUID.randomUUID()
        jdbc.update("insert into training_programs(id,name,status,allowed_formats,created_by) values(?,'=Literal program','published','online,offline',?)",program,admin.id)
        jdbc.update("""insert into training_program_versions(id,program_id,version_no,allowed_formats,snapshot_json,created_by)
            values(?,?,1,'online,offline',?,?)""",version,program,"{\"answer\":\"$privateMarker\"}",admin.id)
        val definition=UUID.randomUUID()
        jdbc.update("""insert into training_program_stages(id,version_id,stage_key,type,title,order_no,required,applicable_formats)
            values(?,?,'test-step','test','+Literal test stage',0,true,'online,offline')""",definition,version)
        ownEvent=event(admin.id);val foreignEvent=event(otherTrainer.id)
        assignmentA=assignment("learning-person-a",ownEvent);assignmentB=assignment("learning-person-b",foreignEvent)
        stageA=stage(assignmentA,definition);stageB=stage(assignmentB,definition)
        resultA=UUID.randomUUID()
        for((result,assignment,stage) in listOf(Triple(resultA,assignmentA,stageA),Triple(UUID.randomUUID(),assignmentB,stageB))) jdbc.update("""insert into training_assessment_results(id,assignment_id,assignment_stage_id,source_type,attempt_no,score,passed,feedback,competency_json,recorded_by,recorded_at)
            values(?,?,?,'test',1,88,false,?,?,?,TIMESTAMPTZ '2026-10-02 12:00:00+05')""",result,assignment,stage,privateMarker,"{\"$privateMarker\":42}",admin.id)
        certificateQr=UUID.randomUUID()
        jdbc.update("""insert into training_certificates(id,certificate_number,assignment_id,pharmacist_id,program_version_id,format,issued_at,score,signer_name,qr_token,status,pdf_url)
            values(?,'000001-CERT',?,'learning-person-a',?,'online',now(),null,?,?,'valid',?)""",UUID.randomUUID(),assignmentA,version,privateMarker,certificateQr,"https://private.invalid/$privateMarker.pdf")
        jdbc.update("""insert into training_rewards(id,assignment_id,pharmacist_id,amount,reason,status,issued_at)
            values(?,?,'learning-person-a',900,?,'issued',now())""",UUID.randomUUID(),assignmentA,privateMarker)
        grants()
    }
    private fun event(trainer:UUID)=UUID.randomUUID().also {id->jdbc.update("""insert into offline_events(id,program_version_id,title,starts_at,ends_at,trainer_id,capacity,qr_token,created_by,check_in_code)
        values(?,?,'Business training event',now(),now()+interval '1 day',?,10,?,?,?)""",id,version,trainer,UUID.randomUUID(),admin.id,if(trainer==admin.id) "100001" else "100002")}
    private fun assignment(pharmacist:String,event:UUID)=UUID.randomUUID().also {id->jdbc.update("""insert into training_assignments(id,program_version_id,pharmacist_id,format,status,assigned_by,event_id,progress_pct,score)
        values(?,?,?,'online','in_progress',?,?,42,null)""",id,version,pharmacist,admin.id,event)}
    private fun stage(assignment:UUID,definition:UUID)=UUID.randomUUID().also {id->jdbc.update("""insert into training_assignment_stages(id,assignment_id,program_stage_id,status,progress_pct,score,attempts_used)
        values(?,?,?,'in_progress',42,null,1)""",id,assignment,definition)}

    @Test fun `D6 learning history follows current pharmacy while foreign rows and financial fields without explicit access stay excluded`() {
        jdbc.update("update pharmacists set pharmacy_id='learning-b',pharmacy_name='Business learning-b',city='City B' where id='learning-person-a'")
        grants(setOf("learning-a"))
        book("assignments") {book->assertEquals(0,book.getSheet("Назначения").lastRowNum);assertEquals(0,book.getSheet("Сертификаты").lastRowNum)}
        grants(setOf("learning-b"))
        book("assignments") {book->
            val data=book.getSheet("Назначения");assertEquals(2,data.lastRowNum)
            val moved=data.first {it.rowNum>0 && it.getCell(5).stringCellValue=="learning-person-a"}
            assertEquals("learning-b",moved.getCell(6).stringCellValue);assertEquals(CellType.BLANK,moved.getCell(14).cellType)
            assertEquals(42.0,moved.getCell(13).numericCellValue)
            assertEquals("000001-CERT",book.getSheet("Сертификаты").getRow(1).getCell(2).stringCellValue)
            assertSafe(book)
        }
        assertEquals(900L,jdbc.queryForObject("select amount from training_rewards",Long::class.java))
    }

    @Test fun `regional current profile city and trainer own event intersect explicit pharmacy scope with no empty fallback ALL`() {
        jdbc.update("update admin_users set role='REGIONAL_MANAGER' where id=?",admin.id)
        jdbc.update("insert into admin_training_regions(admin_user_id,region) values(?,'City A')",admin.id)
        grants(setOf("learning-a","learning-b"))
        book("assignments") {assertEquals(1,it.getSheet("Назначения").lastRowNum);assertEquals("learning-person-a",it.getSheet("Назначения").getRow(1).getCell(5).stringCellValue)}
        grants(setOf("learning-b"));book("assignments") {assertEquals(0,it.getSheet("Назначения").lastRowNum)}
        grants();jdbc.update("delete from admin_training_regions where admin_user_id=?",admin.id)
        book("results") {assertEquals(0,it.getSheet("Результаты").lastRowNum)}
        jdbc.update("update admin_users set role='TRAINER' where id=?",admin.id)
        book("results") {assertEquals(1,it.getSheet("Результаты").lastRowNum);assertEquals("learning-person-a",it.getSheet("Результаты").getRow(1).getCell(7).stringCellValue)}
        jdbc.update("update offline_events set trainer_id=? where id=?",otherTrainer.id,ownEvent)
        book("assignments") {assertEquals(0,it.getSheet("Назначения").lastRowNum)}
    }

    @Test fun `stored raw score and passed are not recalculated and NULL assignment scores stay blank with no grade writes`() {
        grants(setOf("learning-a"))
        val before=jdbc.queryForObject("select md5(score::text||passed::text||feedback||competency_json) from training_assessment_results where id=?",String::class.java,resultA)
        book("results") {book->
            val row=book.getSheet("Результаты").getRow(1);assertEquals(88.0,row.getCell(15).numericCellValue);assertFalse(row.getCell(16).booleanCellValue)
            assertEquals(1,book.getSheet("Результаты").lastRowNum);assertSafe(book)
        }
        book("assignments") {assertEquals(CellType.BLANK,it.getSheet("Назначения").getRow(1).getCell(14).cellType);assertEquals(CellType.BLANK,it.getSheet("Этапы назначений").getRow(1).getCell(8).cellType)}
        assertEquals(before,jdbc.queryForObject("select md5(score::text||passed::text||feedback||competency_json) from training_assessment_results where id=?",String::class.java,resultA))
        assertEquals(42,jdbc.queryForObject("select progress_pct from training_assignments where id=?",Int::class.java,assignmentA))
    }

    @Test fun `separate learning files retain scoped employee names with formula-safe text and blank money without financial authority`() {
        jdbc.update("UPDATE pharmacists SET name='=Synthetic public learner' WHERE id='learning-person-a'")
        grants(setOf("learning-a"))
        book("assignments") { workbook ->
            val sheet=workbook.getSheet("Назначения");val row=sheet.getRow(1)
            assertEquals(1,sheet.lastRowNum);assertEquals(27,sheet.getRow(0).lastCellNum.toInt())
            assertEquals("=Synthetic public learner",row.getCell(24).stringCellValue)
            assertEquals(CellType.STRING,row.getCell(24).cellType)
            assertEquals(CellType.BLANK,row.getCell(25).cellType);assertEquals(CellType.BLANK,row.getCell(26).cellType)
            assertFalse(sheet.any { it.any { cell -> cell.toString().contains("Synthetic public b") } })
            assertSafe(workbook)
        }
        book("results") { workbook ->
            val row=workbook.getSheet("Результаты").getRow(1)
            assertEquals("=Synthetic public learner",row.getCell(21).stringCellValue)
            assertEquals(CellType.STRING,row.getCell(21).cellType);assertSafe(workbook)
        }
    }

    @Test fun `historical training reward needs global financial access remains its saved amount and disappears immediately on revocation`() {
        val view=grant("assignments","view");val export=grant("assignments","export")
        val localMoney=StoredGrant("pharmacists","registry","financial_data",pharmacyIds=setOf("learning-a"))
        val globalMoney=StoredGrant("pharmacists","registry","financial_data",allPharmacies=true)
        setGrants(view,export,localMoney)
        book("assignments") { assertEquals(CellType.BLANK,it.getSheet("Назначения").getRow(1).getCell(25).cellType) }
        setGrants(view,export,globalMoney)
        book("assignments") { workbook ->
            val sheet=workbook.getSheet("Назначения")
            val own=sheet.first { it.rowNum>0 && it.getCell(5).stringCellValue=="learning-person-a" }
            val other=sheet.first { it.rowNum>0 && it.getCell(5).stringCellValue=="learning-person-b" }
            assertEquals(900.0,own.getCell(25).numericCellValue);assertEquals("issued",own.getCell(26).stringCellValue)
            assertEquals(CellType.BLANK,other.getCell(25).cellType);assertEquals(CellType.BLANK,other.getCell(26).cellType)
            assertSafe(workbook,financialAllowed=true)
        }
        setGrants(view,export)
        book("assignments") { assertEquals(CellType.BLANK,it.getSheet("Назначения").getRow(1).getCell(25).cellType) }
        assertEquals(900L,jdbc.queryForObject("SELECT amount FROM training_rewards",Long::class.java))
        assertEquals(700L,jdbc.queryForObject("SELECT balance FROM pharmacists WHERE id='learning-person-a'",Long::class.java))
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM training_rewards",Int::class.java))
    }

    @Test fun `financial export refuses a reward assigned to another person without rewriting either source row`() {
        setGrants(grant("assignments","view"),grant("assignments","export"),
            StoredGrant("pharmacists","registry","financial_data",allPharmacies=true))
        jdbc.update("UPDATE training_rewards SET pharmacist_id='learning-person-b' WHERE assignment_id=?",assignmentA)
        val before=jdbc.queryForMap("SELECT * FROM training_rewards WHERE assignment_id=?",assignmentA)
        mvc.perform(request("assignments")).andExpect(status().isConflict)
        assertEquals(before,jdbc.queryForMap("SELECT * FROM training_rewards WHERE assignment_id=?",assignmentA))
    }

    @Test fun `assignment and result exports require separate explicit view export grants and revoke before next request`() {
        setGrants(grant("assignments","view"));mvc.perform(request("assignments")).andExpect(status().isForbidden)
        setGrants(grant("assignments","export"));mvc.perform(request("assignments")).andExpect(status().isForbidden)
        setGrants(grant("assignments","view"),grant("assignments","export"));download("assignments")
        mvc.perform(request("results")).andExpect(status().isForbidden)
        grants(setOf("learning-a"));download("results")
        setGrants();mvc.perform(request("results")).andExpect(status().isForbidden)
        jdbc.update("update admin_users set access_mode='LEGACY' where id=?",admin.id)
        mvc.perform(request("assignments")).andExpect(status().isForbidden)
        jdbc.update("delete from unified_admin_profiles where account_id=?",admin.id)
        mvc.perform(request("results")).andExpect(status().isForbidden)
    }

    @Test fun `results date and source filters are explicit and empty results are not fabricated completions`() {
        val file=mvc.perform(request("results").param("from","2026-10-03").param("to","2026-10-03").param("sourceType","test"))
            .andExpect(status().isOk).andReturn().response.contentAsByteArray
        XSSFWorkbook(ByteArrayInputStream(file)).use {assertEquals(0,it.getSheet("Результаты").lastRowNum);assertEquals(0.0,it.getSheet("Итоги").getRow(1).getCell(1).numericCellValue)}
        mvc.perform(request("results").param("from","2026-10-02")).andExpect(status().isBadRequest)
        mvc.perform(request("results").param("sourceType","material")).andExpect(status().isBadRequest)
        mvc.perform(request("assignments").param("status","completed")).andExpect(status().isOk)
        mvc.perform(request("assignments").param("format","not-a-format")).andExpect(status().isBadRequest)
    }

    @Test fun `inconsistent result stage or certificate identity fails closed without exporting another learners record`() {
        grants(setOf("learning-a"))
        jdbc.update("update training_assessment_results set assignment_stage_id=?,attempt_no=2 where id=?",stageB,resultA)
        mvc.perform(request("results")).andExpect(status().isConflict)
        jdbc.update("update training_assessment_results set assignment_stage_id=?,attempt_no=1 where id=?",stageA,resultA)
        jdbc.update("update training_certificates set pharmacist_id='learning-person-b' where assignment_id=?",assignmentA)
        mvc.perform(request("assignments")).andExpect(status().isConflict)
    }

    @Test fun `managed assignments require XLSX while results honour explicit scoped grants without accepting a pharmacist as trainer`() {
        mvc.perform(get("/api/admin/training/assignments/export.csv").header("Authorization",auth)).andExpect(status().isConflict)
        mvc.perform(get("/api/admin/training/assignments/$assignmentA/results").header("Authorization",auth)).andExpect(status().isOk)
        grants(setOf("learning-b"))
        mvc.perform(get("/api/admin/training/assignments/$assignmentA/results").header("Authorization",auth)).andExpect(status().isForbidden)
        jdbc.update("update admin_users set role='CATEGORY_LEAD' where id=?",admin.id)
        mvc.perform(request("assignments")).andExpect(status().isForbidden)
        mvc.perform(get("/api/admin/training/exports/results.xlsx").header("Authorization","Bearer ${jwt.issuePharmacistToken("learning-person-a",privateMarker,"+77000000001")}"))
            .andExpect(status().isForbidden)
    }
    private fun assertSafe(book:XSSFWorkbook,financialAllowed:Boolean=false) {
        val text=book.flatMap {sheet->sheet.flatMap {row->row.map {it.toString()}}}.joinToString("\n")
        for(secret in listOf(privateMarker,"000000000001","+77000000001",certificateQr.toString(),"https://private.invalid/")) assertFalse(text.contains(secret))
        assertTrue(book.all {sheet->sheet.all {row->row.none {it.cellType==CellType.FORMULA}}})
        if(!financialAllowed) {
            book.getSheet("Назначения")?.filter {it.rowNum>0}?.forEach {row->
                assertEquals(CellType.BLANK,row.getCell(25).cellType)
                assertEquals(CellType.BLANK,row.getCell(26).cellType)
            }
            assertTrue(book.flatMap {sheet->sheet.flatMap {row->row.filter {it.cellType==CellType.NUMERIC}}}.none {it.numericCellValue==900.0})
        }
    }
}
