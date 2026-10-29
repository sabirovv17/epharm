package kz.epharm.reporting.merch

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.entity.AdminUserEntity
import kz.epharm.auth.repository.AdminUserRepository
import kz.epharm.auth.service.JwtService
import kz.epharm.reporting.xlsx.ReportWorkbook
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class MerchExportIntegrationTest {
    companion object {
        @Container @JvmStatic val postgres=PostgreSQLContainer("postgres:16-alpine").withDatabaseName("merch_exports_disposable")
            .withUsername("epharm").withPassword("synthetic-only").apply {start()}
        @JvmStatic @DynamicPropertySource fun props(reg:DynamicPropertyRegistry) {
            reg.add("spring.datasource.url"){postgres.jdbcUrl};reg.add("spring.datasource.username"){postgres.username};reg.add("spring.datasource.password"){postgres.password}
        }
    }
    @Autowired lateinit var jdbc:JdbcTemplate
    @Autowired lateinit var named:NamedParameterJdbcTemplate
    @Autowired lateinit var users:AdminUserRepository
    @Autowired lateinit var jwt:JwtService
    @Autowired lateinit var json:ObjectMapper
    @Autowired lateinit var mvc:MockMvc
    private lateinit var admin:AdminUserEntity
    private lateinit var branch:UUID
    private lateinit var foreignBranch:UUID
    private lateinit var shelf:UUID
    private lateinit var foreignShelf:UUID
    private lateinit var producer:UUID
    private lateinit var foreignProducer:UUID
    private lateinit var plan:UUID
    private lateinit var task:UUID
    private val month=LocalDate.of(2026,10,1)
    private val created=Instant.parse("2026-10-28T20:30:00Z") // 29 October in Kazakhstan, not 28 October UTC.
    private val auth get()="Bearer ${jwt.issueAccessToken(admin)}"
    @BeforeEach fun seed() {
        jdbc.execute("TRUNCATE merch_task_dispatches,merch_media_plans,merch_monthly_inventory,merch_shelves,merch_branches,merch_organizations,merch_producers,unified_admin_profiles,unified_persons,admin_users CASCADE")
        admin=users.saveAndFlush(AdminUserEntity(email="merch-export@example.invalid",name="Synthetic export operator",passwordHash="unused",roleRaw=AdminRole.HQ_HEAD.name))
        jdbc.update("UPDATE admin_users SET access_mode='MANAGED' WHERE id=?",admin.id)
        val person=UUID.randomUUID();jdbc.update("INSERT INTO unified_persons(id,verification_reference) VALUES(?,'synthetic-proof')",person)
        jdbc.update("INSERT INTO unified_admin_profiles(account_id,person_id) VALUES(?,?)",admin.id,person)
        val org=source("merch_organizations",mapOf("name" to "Synthetic network","legal_name" to "Synthetic","active" to true))
        fun branch(code:String,name:String)=source("merch_branches",mapOf("organization_id" to org,"code" to code,"name" to name,"city" to "Synthetic city","address" to "Synthetic address","active" to true))
        branch=branch("A","=1+1");foreignBranch=branch("B","Foreign branch hidden")
        fun shelf(branch:UUID,code:String)=source("merch_shelves",mapOf("branch_id" to branch,"code" to code,"category" to "AA","width_m" to BigDecimal.ONE,
            "base_monthly_price" to 15000,"active" to true,"place_type" to "shelf","position_index" to 0,"specifications" to "{}","sale_mode" to "whole_only"))
        shelf=shelf(branch,"A-01");foreignShelf=shelf(foreignBranch,"B-01")
        producer=source("merch_producers",mapOf("name" to "Own synthetic producer","phone" to "+77000000991","active" to true))
        foreignProducer=source("merch_producers",mapOf("name" to "Foreign producer hidden","phone" to "+77000000992","active" to true))
        plan=plan(producer,shelf);plan(foreignProducer,foreignShelf);plan(foreignProducer,shelf)
        task=task(branch,300);task(foreignBranch,999)
        source("merch_monthly_inventory",mapOf("shelf_id" to shelf,"placement_month" to month,"status" to "available","monthly_price" to 12000,"block_note" to "PRIVATE-BLOCK-NOTE"))
        grants()
    }
    private fun source(table:String,fields:Map<String,Any?>):UUID {
        val id=UUID.randomUUID();val row=linkedMapOf<String,Any?>("id" to id,"source_system" to "MERCH_CRM","source_origin" to "https://crm.inkar.kz",
            "source_id" to id,"source_payload" to "{\"private\":\"SOURCE-PAYLOAD-SECRET\",\"token\":\"RAW-TOKEN-SECRET\"}","created_at" to Timestamp.from(created))
        if(table!="merch_media_plan_items") row["updated_at"]=Timestamp.from(created)
        row.putAll(fields)
        named.update("INSERT INTO $table(${row.keys.joinToString(",")}) VALUES(${row.keys.joinToString(","){if(it in setOf("source_payload","specifications")) "CAST(:$it AS jsonb)" else ":$it"}})",row)
        return id
    }
    private fun plan(owner:UUID,place:UUID):UUID {
        val id=source("merch_media_plans",mapOf("producer_id" to owner,"advertiser_name" to "Original source company","placement_month" to month,"status" to "approved",
            "origin" to "request","total_amount" to 15000,"submitted_at" to Timestamp.from(created),"review_note" to "PRIVATE-REVIEW-NOTE","assignment_note" to "PRIVATE-VERIFICATION-REASON"))
        source("merch_media_plan_items",mapOf("media_plan_id" to id,"shelf_id" to place,"placement_month" to month,"monthly_price" to 15000,"status" to "approved","sale_portion" to "whole"))
        return id
    }
    private fun task(branch:UUID,reward:Long):UUID=source("merch_task_dispatches",mapOf("branch_id" to branch,"title" to "Synthetic packet","status" to "ready","reward_amount_kzt" to reward))
    private fun grants(money:Boolean=false,all:Boolean=false,view:Boolean=true,export:Boolean=true) {
        val rows=mutableListOf<Map<String,Any?>>()
        for(section in listOf("inventory","placements","tasks")) for(action in listOfNotNull(if(view) "view" else null,if(export) "export" else null,if(money) "financial_data" else null)) {
            val global=all && action!="financial_data"
            rows+=mapOf("module" to "merchandising","subsection" to section,"action" to action,"allPharmacies" to false,"pharmacyIds" to emptyList<String>(),
                "allMerchBranches" to global,"merchBranchIds" to if(global) emptyList() else listOf(branch.toString()),
                "allProducers" to (global && section=="placements"),"producerIds" to if(section=="placements" && !global) listOf(producer.toString()) else emptyList<String>())
        }
        jdbc.update("UPDATE unified_admin_profiles SET grants_json=?,version=version+1 WHERE account_id=?",json.writeValueAsString(rows),admin.id)
    }
    private fun request(path:String)=mvc.perform(get(path).header("Authorization",auth))
    private fun book(path:String):XSSFWorkbook=XSSFWorkbook(ByteArrayInputStream(request(path).andExpect(status().isOk)
        .andExpect(content().contentType(ReportWorkbook.MIME)).andExpect(header().string("Cache-Control","no-store"))
        .andReturn().response.contentAsByteArray))
    private fun text(book:XSSFWorkbook)=book.flatMap {sheet->sheet.flatMap {row->row.map {it.toString()}}}.joinToString("|")

    @Test fun `inventory workbook matches the calendar preserves stored price absence and neutralizes spreadsheet formulas`() {
        grants(money=true)
        val before=jdbc.queryForList("SELECT status,held_until FROM merch_media_plans ORDER BY id")
        book("/api/admin/merch/exports/inventory.xlsx?branchId=$branch&startMonth=$month&monthCount=2").use {b->
            assertThat(b.map {it.sheetName}).containsExactly("Параметры","Сводка","Данные")
            val data=b.getSheet("Данные");assertThat(data.lastRowNum).isEqualTo(2)
            assertThat(data.getRow(1).getCell(1).cellType).isEqualTo(CellType.STRING)
            assertThat(data.getRow(1).getCell(1).stringCellValue).isEqualTo("=1+1")
            assertThat(data.getRow(1).getCell(1).cellStyle.quotePrefixed).isTrue()
            assertThat(data.getRow(1).getCell(8).stringCellValue).isEqualTo("booked")
            assertThat(data.getRow(1).getCell(11).numericCellValue).isEqualTo(15000.0)
            assertThat(data.getRow(1).getCell(12).numericCellValue).isEqualTo(12000.0)
            assertThat(data.getRow(2).getCell(12).cellType).isEqualTo(CellType.BLANK)
            assertThat(b.getSheet("Сводка").getRow(1).getCell(5).numericCellValue).isEqualTo(1.0)
            assertThat(text(b)).contains(shelf.toString(),"MERCH_CRM").doesNotContain("PRIVATE-BLOCK-NOTE","SOURCE-PAYLOAD-SECRET","RAW-TOKEN-SECRET",foreignBranch.toString())
        }
        assertThat(jdbc.queryForList("SELECT status,held_until FROM merch_media_plans ORDER BY id")).isEqualTo(before)
    }
    @Test fun `placement workbook scopes complete plans and customer totals before producing data or the daily summary`() {
        val mixed=plan(producer,shelf)
        source("merch_media_plan_items",mapOf("media_plan_id" to mixed,"shelf_id" to foreignShelf,"placement_month" to month,"monthly_price" to 1,"status" to "approved","sale_portion" to "whole"))
        grants(money=true)
        book("/api/admin/merch/exports/placements.xlsx?producerId=$producer&branchId=$branch&status=approved&from=2026-10-29&to=2026-10-29").use {b->
            val data=b.getSheet("Данные");assertThat(data.lastRowNum).isEqualTo(1)
            assertThat(data.getRow(1).getCell(0).stringCellValue).isEqualTo(plan.toString())
            assertThat(data.getRow(1).getCell(13).numericCellValue).isEqualTo(15000.0)
            val summary=b.getSheet("Сводка").getRow(1)
            assertThat(summary.getCell(0).stringCellValue).isEqualTo("2026-10-29")
            assertThat(summary.getCell(1).numericCellValue).isEqualTo(1.0);assertThat(summary.getCell(8).numericCellValue).isEqualTo(15000.0)
            assertThat(text(b)).doesNotContain(foreignBranch.toString(),foreignProducer.toString(),mixed.toString(),"PRIVATE-REVIEW-NOTE","PRIVATE-VERIFICATION-REASON","+77000000991","SOURCE-PAYLOAD-SECRET")
        }
    }
    @Test fun `task workbook excludes foreign rows people and capabilities and masks all financial values without a money grant`() {
        book("/api/admin/merch/exports/tasks.xlsx?branchId=$branch&status=ready").use {b->
            val data=b.getSheet("Данные");assertThat(data.lastRowNum).isEqualTo(1)
            assertThat(data.getRow(1).getCell(0).stringCellValue).isEqualTo(task.toString())
            assertThat(data.getRow(1).getCell(10).cellType).isEqualTo(CellType.BLANK)
            assertThat(data.getRow(1).getCell(11).cellType).isEqualTo(CellType.BLANK)
            assertThat(b.getSheet("Сводка").getRow(1).getCell(10).cellType).isEqualTo(CellType.BLANK)
            assertThat(text(b)).doesNotContain(foreignBranch.toString(),"RAW-TOKEN-SECRET","SOURCE-PAYLOAD-SECRET","executor_person","capability_link","phone","ИИН")
        }
        book("/api/admin/merch/exports/placements.xlsx").use {b->
            assertThat(b.getSheet("Данные").getRow(1).getCell(13).cellType).isEqualTo(CellType.BLANK)
            assertThat(b.getSheet("Данные").getRow(1).getCell(14).cellType).isEqualTo(CellType.BLANK)
            assertThat(b.getSheet("Сводка").getRow(1).getCell(8).cellType).isEqualTo(CellType.BLANK)
        }
        book("/api/admin/merch/exports/inventory.xlsx?branchId=$branch&startMonth=$month&monthCount=1").use {b->
            assertThat(b.getSheet("Данные").getRow(1).getCell(11).cellType).isEqualTo(CellType.BLANK)
            assertThat(b.getSheet("Данные").getRow(1).getCell(12).cellType).isEqualTo(CellType.BLANK)
        }
    }
    @Test fun `partial money permission never becomes a complete network financial total`() {
        grants(money=true,all=true)
        book("/api/admin/merch/exports/tasks.xlsx").use {b->
            val data=b.getSheet("Данные");assertThat(data.lastRowNum).isEqualTo(2)
            val prices=(1..2).associate {data.getRow(it).getCell(1).stringCellValue to data.getRow(it).getCell(10)}
            assertThat(prices.getValue(branch.toString()).numericCellValue).isEqualTo(300.0)
            assertThat(prices.getValue(foreignBranch.toString()).cellType).isEqualTo(CellType.BLANK)
            assertThat(b.getSheet("Сводка").getRow(1).getCell(10).cellType).isEqualTo(CellType.BLANK)
        }
    }
    @Test fun `export requires its own view and export grants and a revoked right takes effect with the same JWT`() {
        grants(view=false);request("/api/admin/merch/exports/tasks.xlsx").andExpect(status().isForbidden)
        grants(export=false);request("/api/admin/merch/exports/tasks.xlsx").andExpect(status().isForbidden)
        grants();request("/api/admin/merch/exports/tasks.xlsx").andExpect(status().isOk)
        grants(export=false);request("/api/admin/merch/exports/tasks.xlsx").andExpect(status().isForbidden)
        jdbc.update("DELETE FROM unified_admin_profiles WHERE account_id=?",admin.id)
        jdbc.update("UPDATE admin_users SET access_mode='LEGACY' WHERE id=?",admin.id)
        request("/api/admin/merch/exports/tasks.xlsx").andExpect(status().isForbidden)
    }
    @Test fun `foreign filters invalid periods and SQL injection status do not widen the export scope`() {
        request("/api/admin/merch/exports/inventory.xlsx?branchId=$foreignBranch&startMonth=$month&monthCount=1").andExpect(status().isForbidden)
        request("/api/admin/merch/exports/tasks.xlsx?branchId=$foreignBranch").andExpect(status().isForbidden)
        request("/api/admin/merch/exports/placements.xlsx?producerId=$foreignProducer").andExpect(status().isForbidden)
        request("/api/admin/merch/exports/tasks.xlsx?from=2026-01-01").andExpect(status().isBadRequest)
        request("/api/admin/merch/exports/tasks.xlsx?from=2025-01-01&to=2026-10-29").andExpect(status().isBadRequest)
        mvc.perform(get("/api/admin/merch/exports/tasks.xlsx").param("status","ready' OR true --").header("Authorization",auth)).andExpect(status().isBadRequest)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merch_task_dispatches",Long::class.java)).isEqualTo(2)
    }
    @Test fun `oversized real task selection returns 413 instead of a silently truncated workbook`() {
        jdbc.update("""INSERT INTO merch_task_dispatches(id,source_system,source_origin,source_id,source_payload,branch_id,title,status,created_at,updated_at)
            SELECT id,'MERCH_CRM','https://crm.inkar.kz',id,'{}',?,'Synthetic bounded selection','ready',?,?
            FROM(SELECT gen_random_uuid() AS id FROM generate_series(1,15000)) synthetic""",branch,Timestamp.from(created),Timestamp.from(created))
        request("/api/admin/merch/exports/tasks.xlsx").andExpect(status().isPayloadTooLarge)
    }
}
