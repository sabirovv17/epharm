package kz.epharm.reporting.exports

import com.fasterxml.jackson.databind.ObjectMapper
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import java.io.ByteArrayInputStream
import java.time.LocalDate
import java.time.ZoneId
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
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Transactional
class UnifiedReportsIntegrationTest {
    companion object {
        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("unified_reports").withUsername("epharm").withPassword("synthetic-only").apply { start() }
        @JvmStatic @DynamicPropertySource fun props(reg: DynamicPropertyRegistry) {
            reg.add("spring.datasource.url") { postgres.jdbcUrl }
            reg.add("spring.datasource.username") { postgres.username }
            reg.add("spring.datasource.password") { postgres.password }
        }
    }
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var users: AdminUserRepository
    @Autowired lateinit var jwt: JwtService
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var mvc: MockMvc
    private lateinit var admin: AdminUserEntity
    private lateinit var bearer: String
    private val day get() = LocalDate.now(ZoneId.of("Asia/Almaty")).toString()
    private fun grant(module: String, section: String, action: String, ids: Set<String>? = null) =
        StoredGrant(module, section, action, ids == null, ids.orEmpty())
    private fun setGrants(vararg grants: StoredGrant) {
        jdbc.update("update unified_admin_profiles set grants_json=?,version=version+1 where account_id=?",
            mapper.writeValueAsString(grants.toList()), admin.id)
    }
    private fun finance(vararg actions: String, ids: Set<String>? = null) =
        actions.map { grant("motivation", "accruals", it, ids) }.toTypedArray()
    private fun ledger(path: String = "ledger") = get("/api/admin/reports/$path").header("Authorization", bearer).param("from", day).param("to", day)

    @BeforeEach fun seed() {
        // All writes belong to this test's disposable PostgreSQL, never to the rehearsal/source databases.
        jdbc.execute("TRUNCATE pharmacist_wallet_entries,pharmacist_wallet_checkpoints,merch_reward_events,merch_executor_bindings,pending_bonuses,payout_items,payout_batches,pharmacists,unified_admin_profiles,unified_persons,admin_users,pharmacies,chains CASCADE")
        admin = users.saveAndFlush(AdminUserEntity(email = "reports@example.invalid", name = "Synthetic HQ",
            passwordHash = "synthetic-hash-not-for-export", roleRaw = AdminRole.HQ_HEAD.name))
        bearer = "Bearer ${jwt.issueAccessToken(admin)}"
        val person = UUID.randomUUID()
        jdbc.update("insert into unified_persons values(?,?)", person, "synthetic-proof")
        jdbc.update("insert into unified_admin_profiles(account_id,person_id) values(?,?)", admin.id, person)
        jdbc.update("update admin_users set access_mode='MANAGED' where id=?", admin.id)
        jdbc.update("""insert into chains(id,name,color,"group",points) values('report-chain','Synthetic','#000000','pilot',0)""")
        for (id in listOf("report-a", "report-b")) jdbc.update("""insert into pharmacies(id,name,chain_id,chain_name,city,district,addr,"group")
            values(?,?,'report-chain','Synthetic','Synthetic city','Synthetic district','Synthetic address','pilot')""", id, "Pharmacy $id")
        jdbc.update("""insert into pharmacists(id,name,iin,phone,password_hash,pharmacy_id,pharmacy_name,city,tier,status,joined_at,balance)
            values('report-staff','=LITERAL()','000000000001','+77000000001','synthetic-private-hash','report-b','Current B','Test','Silver','active',current_date,600),
                  ('report-other','Synthetic A','000000000002','+77000000002',null,'report-a','Current A','Test','Silver','active',current_date,0)""")
        jdbc.update("""insert into pending_bonuses(id,pharmacist_id,pharmacy_id,sku,expected_amount,bonus,status)
            values('report-pending','report-staff','report-a','synthetic-sku',1000,100,'matched')""")
        posting("receipt_bonus", "report-pending", 100)
        posting("training_bonus", UUID.randomUUID().toString(), 75)
        val dispatch = UUID.randomUUID(); val binding = UUID.randomUUID()
        val walletId = posting("merch_task", dispatch.toString(), 250)
        jdbc.update("""insert into merch_executor_bindings(id,dispatch_id,pharmacist_id,pharmacy_id,reward_kzt,task_due_at,verified_at)
            values(?,?,'report-staff','report-b',250,now()+interval '1 day',now())""", binding, dispatch)
        jdbc.update("""insert into merch_reward_events(event_id,dispatch_id,binding_id,pharmacist_id,pharmacy_id,amount_kzt,
            submitted_at,approved_at,approved_by_crm_user_id,payload_hash,wallet_entry_id)
            values(?,?,?,'report-staff','report-b',250,now(),now(),?,repeat('a',64),?)""", UUID.randomUUID(), dispatch, binding, UUID.randomUUID(), walletId)
        posting("admin_adjustment", UUID.randomUUID().toString(), -50)
    }
    private fun posting(source: String, id: String, amount: Long): UUID = UUID.randomUUID().also { entry ->
        jdbc.update("""insert into pharmacist_wallet_entries(id,pharmacist_id,source,source_id,amount_kzt,earned_delta_kzt,context_key,balance_after_kzt)
            values(?,'report-staff',?,?,?,?,?,600)""", entry, source, id, amount, if (source == "admin_adjustment") 0L else amount, "synthetic:$id")
    }

    @Test fun `new financial report denies signed HQ with no explicit profile or grant including legacy role`() {
        mvc.perform(ledger()).andExpect(status().isForbidden)
        jdbc.update("update admin_users set access_mode='LEGACY' where id=?", admin.id)
        mvc.perform(ledger()).andExpect(status().isForbidden)
        jdbc.update("delete from unified_admin_profiles where account_id=?", admin.id)
        mvc.perform(ledger()).andExpect(status().isForbidden)
    }

    @Test fun `ledger scopes by source pharmacy despite current transfer and withholds unknown historical count and global balance`() {
        setGrants(*finance("view", ids = setOf("report-a")))
        mvc.perform(ledger()).andExpect(status().isOk)
            .andExpect(jsonPath("$.entries.length()").value(1))
            .andExpect(jsonPath("$.entries[0].source").value("receipt_bonus"))
            .andExpect(jsonPath("$.entries[0].historicalPharmacyId").value("report-a"))
            .andExpect(jsonPath("$.entries[0].balanceAfterKzt").isEmpty)
            .andExpect(jsonPath("$.unresolvedHistoricalRows").isEmpty)
        mvc.perform(ledger().param("source", "training_bonus")).andExpect(status().isOk)
            .andExpect(jsonPath("$.entries").isEmpty).andExpect(jsonPath("$.unresolvedHistoricalRows").isEmpty)
    }

    @Test fun `ALL ledger includes each of three credit sources once with technical adjustments distinct and preserves missing lineage`() {
        setGrants(*finance("view"))
        val result = mvc.perform(ledger()).andExpect(status().isOk)
            .andExpect(jsonPath("$.entries.length()").value(4)).andExpect(jsonPath("$.unresolvedHistoricalRows").value(2))
            .andReturn().response.contentAsByteArray
        val report = mapper.readValue(result, LedgerReport::class.java)
        assertEquals(375L, report.totals.sumOf { it.amountKzt })
        assertEquals(setOf("receipt_bonus", "training_bonus", "merch_task", "admin_adjustment"), report.totals.map { it.source }.toSet())
        assertEquals(4, jdbc.queryForObject("select count(*) from pharmacist_wallet_entries", Int::class.java))
    }

    @Test fun `XLSX requires export and view separately and actual download contains only granted rows with no formulas`() {
        setGrants(*finance("view"))
        mvc.perform(ledger("ledger.xlsx")).andExpect(status().isForbidden)
        setGrants(*finance("export"))
        mvc.perform(ledger("ledger.xlsx")).andExpect(status().isForbidden)
        setGrants(*finance("view", "export", ids = setOf("report-a")))
        val logger = LoggerFactory.getLogger(ReportExportController::class.java) as Logger
        val audit = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(audit)
        val file = try {
            mvc.perform(ledger("ledger.xlsx")).andExpect(status().isOk)
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().contentType(ReportWorkbookMime)).andReturn().response.contentAsByteArray
        } finally { logger.detachAppender(audit); audit.stop() }
        val message = audit.list.single().formattedMessage
        assertTrue(message.contains("actor=${admin.id}")); assertTrue(message.contains("scopePharmacies=[report-a]"))
        assertTrue(message.contains("\"from\":\"$day\"")); assertTrue(message.contains("\"to\":\"$day\""))
        assertFalse(message.contains("=LITERAL()")); assertFalse(message.contains("synthetic-private-hash"))
        XSSFWorkbook(ByteArrayInputStream(file)).use { book ->
            val data = book.getSheet("Данные")
            assertEquals(1, data.lastRowNum)
            assertEquals("report-a", data.getRow(1).getCell(11).stringCellValue)
            assertEquals("=LITERAL()", data.getRow(1).getCell(2).stringCellValue)
            assertEquals(CellType.STRING, data.getRow(1).getCell(2).cellType)
            assertEquals(CellType.BLANK, data.getRow(1).getCell(9).cellType)
        }
        setGrants()
        mvc.perform(ledger("ledger.xlsx")).andExpect(status().isForbidden)
    }

    @Test fun `pharmacist workbook masks personal fields per pharmacy and global money remains null without ALL finance`() {
        setGrants(grant("pharmacists", "registry", "view"), grant("pharmacists", "registry", "export"),
            grant("pharmacists", "registry", "personal_data", setOf("report-a")))
        val file = mvc.perform(get("/api/admin/reports/pharmacists.xlsx").header("Authorization", bearer))
            .andExpect(status().isOk).andReturn().response.contentAsByteArray
        XSSFWorkbook(ByteArrayInputStream(file)).use { book ->
            val data = book.getSheet("Данные")
            val a = data.first { it.rowNum > 0 && it.getCell(0).stringCellValue == "report-other" }
            val b = data.first { it.rowNum > 0 && it.getCell(0).stringCellValue == "report-staff" }
            assertEquals("000000000002", a.getCell(2).stringCellValue)
            assertEquals(CellType.STRING, a.getCell(2).cellType)
            assertEquals(CellType.BLANK, b.getCell(2).cellType)
            assertEquals(CellType.BLANK, b.getCell(3).cellType)
            assertEquals(CellType.BLANK, b.getCell(8).cellType)
            assertEquals(CellType.BLANK, a.getCell(21).cellType)
            assertEquals(CellType.BLANK, b.getCell(21).cellType)
            val allCells = book.flatMap { it.flatMap { row -> row.map { cell -> cell.toString() } } }.joinToString("\n")
            assertFalse(allCells.contains("synthetic-private-hash"))
            assertFalse(allCells.contains("000000000001"))
            assertFalse(allCells.contains("+77000000001"))
        }
    }

    @Test fun `global pharmacist financial grant preserves actual zero and balance without summing event sources again`() {
        setGrants(grant("pharmacists", "registry", "view"), grant("pharmacists", "registry", "export"),
            grant("pharmacists", "registry", "financial_data"))
        val file = mvc.perform(get("/api/admin/reports/pharmacists.xlsx").header("Authorization", bearer))
            .andExpect(status().isOk).andReturn().response.contentAsByteArray
        XSSFWorkbook(ByteArrayInputStream(file)).use { book ->
            val rows = book.getSheet("Данные").filter { it.rowNum > 0 }
            assertEquals(600.0, rows.single { it.getCell(0).stringCellValue == "report-staff" }.getCell(21).numericCellValue)
            assertEquals(0.0, rows.single { it.getCell(0).stringCellValue == "report-other" }.getCell(21).numericCellValue)
        }
    }

    @Test fun `payout export keeps source snapshot fields and bank caveat and rejects assigned pharmacy scope`() {
        jdbc.update("insert into payout_batches(id,period,status,pharmacists,amount,items) values('report-batch','Source period','pending',1,600,1)")
        jdbc.update("""insert into payout_items(id,batch_id,pharmacist_id,pharmacist_name,pharmacy,city,receipts,rules,amount,flag)
            values('report-item','report-batch','report-staff','Historic person','Historic pharmacy','Historic city',8,4,600,'Source flag')""")
        setGrants(grant("motivation", "payouts", "view", setOf("report-a")), grant("motivation", "payouts", "export", setOf("report-a")))
        mvc.perform(ledger("payouts.xlsx")).andExpect(status().isForbidden)
        setGrants(grant("motivation", "payouts", "view"), grant("motivation", "payouts", "export"))
        val file = mvc.perform(ledger("payouts.xlsx")).andExpect(status().isOk).andReturn().response.contentAsByteArray
        XSSFWorkbook(ByteArrayInputStream(file)).use { book ->
            assertEquals("Source period", book.getSheet("Данные").getRow(1).getCell(1).stringCellValue)
            val row = book.getSheet("Состав пакетов").getRow(1)
            assertEquals("Historic pharmacy", row.getCell(4).stringCellValue)
            assertEquals("Source flag", row.getCell(9).stringCellValue)
            assertEquals(CellType.BLANK, row.getCell(11).cellType)
            assertTrue(book.getSheet("Параметры").any { it.getCell(1).toString().contains("не доказывают банковский перевод") })
        }
    }

    @Test fun `invalid source and period fail before producing a misleading financial workbook`() {
        setGrants(*finance("view", "export"))
        mvc.perform(ledger().param("source", "not-a-money-source")).andExpect(status().isBadRequest)
        mvc.perform(get("/api/admin/reports/ledger.xlsx").header("Authorization", bearer)
            .param("from", "2026-10-29").param("to", "2026-01-01")).andExpect(status().isBadRequest)
    }

    @Test fun `campaign XLSX preserves real metric cohort and historical pharmacy coverage on one cutoff`() {
        jdbc.update("insert into products(id,name,brand,vendor,mnn,price) values('report-product','Synthetic offer','','','',0)")
        jdbc.update("insert into promos(id,title,brand,status) values('report-promo','Synthetic campaign','Brand label','active')")
        jdbc.update("""insert into rules(id,type,status,trigger,recommend,promo_id) values('report-rule','substitution','active',
            '{"kind":"product","value":"report-product"}'::jsonb,'report-product','report-promo')""")
        jdbc.update("""insert into recommendation_events(id,session_id,pharmacist_id,pharmacy_id,rule_id,kind,recommend_sku,shown_at,displayed_at)
            values('report-event','report-session','report-staff','report-a','report-rule','substitution','report-product',now(),now())""")
        setGrants(grant("analytics", "campaign_reports", "view"), grant("analytics", "campaign_reports", "export"))
        val file = mvc.perform(get("/api/admin/reports/campaigns/report-promo/xlsx").header("Authorization", bearer)
            .param("from", day).param("to", day)).andExpect(status().isOk).andReturn().response.contentAsByteArray
        XSSFWorkbook(ByteArrayInputStream(file)).use { book ->
            assertEquals(1.0, book.getSheet("Сводка").getRow(1).getCell(3).numericCellValue)
            assertEquals("report-a", book.getSheet("Охват аптек").getRow(1).getCell(0).stringCellValue)
            assertEquals(1, book.getSheet("Данные").drawingPatriarch.charts.size)
        }
    }

    private val ReportWorkbookMime = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
}
