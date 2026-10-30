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
class TrainingAuthoringExportIntegrationTest {
    companion object {
        @Container @JvmStatic val postgres=PostgreSQLContainer("postgres:16-alpine").withDatabaseName("training_authoring_exports")
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
    private lateinit var program:UUID
    private lateinit var incomplete:UUID
    private val auth get()="Bearer ${jwt.issueAccessToken(admin)}"
    private val privateMarker="synthetic-private-answer-and-path"
    private fun grant(section:String,action:String,all:Boolean=true)=StoredGrant("training",section,action,all,if(all) emptySet() else setOf("synthetic-pharmacy"))
    private fun grants(vararg values:StoredGrant) {jdbc.update("update unified_admin_profiles set grants_json=?,version=version+1 where account_id=?",json.writeValueAsString(values),admin.id)}
    private fun request(kind:String,filter:String?=null)=get("/api/admin/training/exports/$kind.xlsx").header("Authorization",auth).also {if(filter!=null) it.param("status",filter)}
    private fun download(kind:String,filter:String?=null)=mvc.perform(request(kind,filter)).andExpect(status().isOk)
        .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .andExpect(header().string("Cache-Control","no-store")).andExpect(header().string("X-Content-Type-Options","nosniff")).andReturn().response.contentAsByteArray

    @BeforeEach fun seed() {
        // All fixtures and HTTP reads use only this class's disposable PG16, never source/candidate/production data.
        jdbc.execute("TRUNCATE courses,training_programs,unified_admin_profiles,unified_persons,admin_users CASCADE")
        admin=users.saveAndFlush(AdminUserEntity(email="training-export@example.invalid",name="Synthetic HQ",passwordHash=privateMarker,roleRaw=AdminRole.HQ_HEAD.name))
        val person=UUID.randomUUID();jdbc.update("insert into unified_persons values(?,?)",person,"synthetic-verified-person")
        jdbc.update("insert into unified_admin_profiles(account_id,person_id) values(?,?)",admin.id,person)
        jdbc.update("update admin_users set access_mode='MANAGED' where id=?",admin.id)
        for((id,state) in listOf("001-course" to "published","draft-course" to "draft","archived-course" to "archived")) jdbc.update(
            """insert into courses(id,title,description,status,category,lessons,duration_min,enrolled,completed,bonus,created_by)
                values(?,?,?,?,'Synthetic category',9,45,13,4,900,?)""",id,"=Literal $id",privateMarker,state,admin.id.toString())
        val quiz=json.writeValueAsString(listOf(mapOf("id" to "q1","prompt" to privateMarker,"options" to listOf("one","two"),"correctOption" to 1,"explanation" to privateMarker)))
        jdbc.update("""insert into course_lessons(id,course_id,title,content,kind,video_url,duration_min,order_no,required_lesson,minimum_watch_pct)
            values('video-lesson','001-course','Video',?,'video',?,20,0,true,75)""",privateMarker,"https://private.invalid/$privateMarker.mp4")
        jdbc.update("""insert into course_lessons(id,course_id,title,content,kind,quiz_questions,quiz_passing_score,duration_min,order_no,required_lesson)
            values('quiz-lesson','001-course','+Literal quiz',?,'quiz',?::jsonb,80,5,1,true)""",privateMarker,quiz)
        jdbc.update("""insert into course_lesson_attachments(id,lesson_id,title,file_name,content_type,media_url,size_bytes)
            values('private-attachment','video-lesson',?,?,'application/pdf',?,12)""",privateMarker,privateMarker,"https://private.invalid/$privateMarker.pdf")
        program=UUID.randomUUID();incomplete=UUID.randomUUID()
        for((id,state) in listOf(program to "published",incomplete to "draft")) jdbc.update("""insert into training_programs(id,name,status,allowed_formats,created_by,
            manufacturer,brand,product,language,current_version,cover_url) values(?,?,?,'online,offline',?,'Business manufacturer','Business brand','Business product','ru',1,?)""",
            id,"=Literal program $state",state,admin.id,"https://private.invalid/$privateMarker.png")
        val current=UUID.randomUUID();val other=UUID.randomUUID()
        for((id,version) in listOf(current to 1,other to 2)) jdbc.update("""insert into training_program_versions(id,program_id,version_no,allowed_formats,
            online_course_id,passing_score,max_attempts,completion_bonus,snapshot_json,created_by) values(?,?,?,'online','001-course',80,3,700,?,?)""",id,program,version,"{\"private\":\"$privateMarker\"}",admin.id)
        jdbc.update("""insert into training_program_stages(id,version_id,stage_key,type,title,order_no,required,passing_score,max_attempts,
            applicable_formats,content_url) values(?,?,'current-stage','online_course','+Literal stage',0,true,null,null,'online',?)""",UUID.randomUUID(),current,"https://private.invalid/$privateMarker.mp4")
        jdbc.update("""insert into training_program_stages(id,version_id,stage_key,type,title,order_no,required,applicable_formats)
            values(?,?,'future-stage','test','Not current version stage',0,true,'online')""",UUID.randomUUID(),other)
        grants(grant("courses","view"),grant("courses","export"),grant("programs","view"),grant("programs","export"))
    }

    @Test fun `course XLSX preserves source counters actual formats quiz counts and literal text while excluding keys PII and media`() {
        val before=jdbc.queryForObject("select md5(quiz_questions::text||content) from course_lessons where id='quiz-lesson'",String::class.java)
        XSSFWorkbook(ByteArrayInputStream(download("courses","published"))).use {book->
            val data=book.getSheet("Курсы");assertEquals(1,data.lastRowNum)
            val row=data.getRow(1);assertEquals("001-course",row.getCell(0).stringCellValue);assertEquals(CellType.STRING,row.getCell(0).cellType)
            assertTrue(row.getCell(1).cellStyle.quotePrefixed);assertEquals("published",row.getCell(2).stringCellValue)
            assertEquals(9.0,row.getCell(5).numericCellValue);assertEquals(2.0,row.getCell(6).numericCellValue)
            assertEquals("quiz, video",row.getCell(7).stringCellValue);assertEquals(1.0,row.getCell(8).numericCellValue)
            assertEquals(1.0,row.getCell(9).numericCellValue);assertEquals(1.0,row.getCell(10).numericCellValue)
            assertEquals(13.0,row.getCell(11).numericCellValue);assertEquals(4.0,row.getCell(12).numericCellValue)
            assertEquals(2,book.getSheet("Уроки").lastRowNum)
            assertEquals(1.0,book.getSheet("Итоги").getRow(1).getCell(1).numericCellValue)
            assertSafe(book)
        }
        assertEquals(before,jdbc.queryForObject("select md5(quiz_questions::text||content) from course_lessons where id='quiz-lesson'",String::class.java))
        assertEquals("https://private.invalid/$privateMarker.mp4",jdbc.queryForObject("select video_url from course_lessons where id='video-lesson'",String::class.java))
    }

    @Test fun `program workbook exports current authoring version only and preserves unknown missing version as blank rather than zero`() {
        XSSFWorkbook(ByteArrayInputStream(download("programs"))).use {book->
            val data=book.getSheet("Программы");assertEquals(2,data.lastRowNum)
            val complete=data.first {it.rowNum>0 && it.getCell(0).stringCellValue==program.toString()}
            assertEquals(2.0,complete.getCell(15).numericCellValue);assertEquals(1.0,complete.getCell(16).numericCellValue)
            val missing=data.first {it.rowNum>0 && it.getCell(0).stringCellValue==incomplete.toString()}
            for(column in listOf(10,11,12,13,14,16,17)) assertEquals(CellType.BLANK,missing.getCell(column).cellType)
            assertEquals(0.0,missing.getCell(15).numericCellValue)
            assertEquals(1,book.getSheet("Этапы текущих версий").lastRowNum)
            assertEquals("current-stage",book.getSheet("Этапы текущих версий").getRow(1).getCell(3).stringCellValue)
            assertSafe(book);assertFalse(text(book).contains("Not current version stage"))
        }
        assertEquals(2,jdbc.queryForObject("select count(*) from training_program_versions",Int::class.java))
        assertEquals(2,jdbc.queryForObject("select count(*) from training_program_stages",Int::class.java))
        assertTrue(jdbc.queryForObject("select snapshot_json from training_program_versions where program_id=? limit 1",String::class.java,program)!!.contains(privateMarker))
    }

    @Test fun `authoring status filters match existing enums archived and empty exports contain actual counts with no placeholder rows`() {
        XSSFWorkbook(ByteArrayInputStream(download("courses","archived"))).use {book->
            assertEquals(1,book.getSheet("Курсы").lastRowNum);assertEquals("archived-course",book.getSheet("Курсы").getRow(1).getCell(0).stringCellValue)
            assertEquals(0,book.getSheet("Уроки").lastRowNum)
        }
        XSSFWorkbook(ByteArrayInputStream(download("programs","paused"))).use {book->
            assertEquals(0,book.getSheet("Программы").lastRowNum);assertEquals(0,book.getSheet("Этапы текущих версий").lastRowNum)
            assertEquals(0.0,book.getSheet("Итоги").getRow(1).getCell(1).numericCellValue)
        }
        mvc.perform(request("courses","paused")).andExpect(status().isBadRequest)
        mvc.perform(request("programs","not-a-status")).andExpect(status().isBadRequest)
    }

    @Test fun `unrecognized legacy quiz container remains untouched and question counts stay unknown rather than zero`() {
        val legacy="{\"legacy\":\"$privateMarker\"}"
        jdbc.update("update course_lessons set quiz_questions=?::jsonb where id='quiz-lesson'",legacy)
        XSSFWorkbook(ByteArrayInputStream(download("courses","published"))).use {book->
            assertEquals(CellType.BLANK,book.getSheet("Курсы").getRow(1).getCell(9).cellType)
            val row=book.getSheet("Уроки").first {it.rowNum>0 && it.getCell(0).stringCellValue=="quiz-lesson"}
            assertEquals(CellType.BLANK,row.getCell(9).cellType);assertSafe(book)
        }
        assertTrue(jdbc.queryForObject("select quiz_questions::text from course_lessons where id='quiz-lesson'",String::class.java)!!.contains(privateMarker))
    }

    @Test fun `exports require explicit own view and export ALL grants and a legacy HQ role does not open authoring metadata`() {
        grants(grant("courses","view"));mvc.perform(request("courses")).andExpect(status().isForbidden)
        grants(grant("courses","export"));mvc.perform(request("courses")).andExpect(status().isForbidden)
        grants(grant("courses","view",false),grant("courses","export",false));mvc.perform(request("courses")).andExpect(status().isForbidden)
        grants(grant("courses","view"),grant("courses","export"));mvc.perform(request("programs")).andExpect(status().isForbidden)
        jdbc.update("update admin_users set access_mode='LEGACY' where id=?",admin.id)
        grants();mvc.perform(request("courses")).andExpect(status().isForbidden)
        jdbc.update("delete from unified_admin_profiles where account_id=?",admin.id)
        mvc.perform(request("programs")).andExpect(status().isForbidden)
    }

    @Test fun `training role ceiling allows explicitly granted trainer but denies merchandising roles and pharmacist tokens`() {
        jdbc.update("update admin_users set role='TRAINER' where id=?",admin.id)
        download("courses");download("programs")
        jdbc.update("update admin_users set role='CATEGORY_LEAD' where id=?",admin.id)
        mvc.perform(request("courses")).andExpect(status().isForbidden)
        mvc.perform(request("programs")).andExpect(status().isForbidden)
        mvc.perform(get("/api/admin/training/exports/courses.xlsx").header("Authorization","Bearer ${jwt.issuePharmacistToken("synthetic-person","Synthetic","+77000000001")}"))
            .andExpect(status().isForbidden)
    }

    @Test fun `over budget lesson selection fails explicitly without a truncated workbook or altering the content`() {
        jdbc.execute("""insert into course_lessons(id,course_id,title,kind,order_no) SELECT 'bounded-'||n,'001-course','Bounded metadata','text',n+10
            FROM generate_series(1,14000) AS n""")
        mvc.perform(request("courses","published")).andExpect(status().isPayloadTooLarge).andExpect(content().contentTypeCompatibleWith("application/json"))
        assertEquals(14002,jdbc.queryForObject("select count(*) from course_lessons where course_id='001-course'",Int::class.java))
    }

    private fun text(book:XSSFWorkbook)=book.flatMap {sheet->sheet.flatMap {row->row.map {it.toString()}}}.joinToString("\n")
    private fun assertSafe(book:XSSFWorkbook) {
        val values=text(book)
        for(privateValue in listOf(privateMarker,"https://private.invalid/","correctOption","+77000000001","password_hash")) assertFalse(values.contains(privateValue))
        assertTrue(book.all {sheet->sheet.all {row->row.none {it.cellType==CellType.FORMULA}}})
    }
}
