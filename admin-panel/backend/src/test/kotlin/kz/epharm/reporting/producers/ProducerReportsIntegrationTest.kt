package kz.epharm.reporting.producers

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.ByteArrayInputStream
import java.time.LocalDate
import java.time.OffsetDateTime
import java.sql.Timestamp
import java.util.UUID
import kz.epharm.auth.access.StoredGrant
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.entity.AdminUserEntity
import kz.epharm.auth.repository.AdminUserRepository
import kz.epharm.auth.service.JwtService
import kz.epharm.reporting.CampaignReportRepository
import kz.epharm.reporting.ReportCounts
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.Assertions.*
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test") @Testcontainers @Transactional
class ProducerReportsIntegrationTest {
    companion object {
        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("producer_reports").withUsername("epharm").withPassword("synthetic-only").apply { start() }
        @JvmStatic @DynamicPropertySource fun properties(reg: DynamicPropertyRegistry) {
            reg.add("spring.datasource.url") { postgres.jdbcUrl }; reg.add("spring.datasource.username") { postgres.username }
            reg.add("spring.datasource.password") { postgres.password }
        }
    }
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var users: AdminUserRepository
    @Autowired lateinit var jwt: JwtService
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var canonical: CampaignReportRepository
    private lateinit var admin: AdminUserEntity
    private lateinit var producer: UUID
    private lateinit var other: UUID
    private val auth get() = "Bearer ${jwt.issueAccessToken(admin)}"
    private val reference = "synthetic-private-proof-reference-never-return"
    private val from = LocalDate.of(2026,10,1)
    private val to = LocalDate.of(2026,10,3)

    @BeforeEach fun seed() {
        // Disposable synthetic PG only. Import/customer credentials and source DBs are never read.
        jdbc.execute("TRUNCATE promos,products,merch_producers,unified_admin_profiles,unified_persons,admin_users CASCADE")
        admin = users.saveAndFlush(AdminUserEntity(email="producer-reports@example.invalid",name="Synthetic HQ",passwordHash="unused-synthetic",roleRaw=AdminRole.HQ_HEAD.name))
        val person=UUID.randomUUID()
        jdbc.update("insert into unified_persons values(?,?)",person,"synthetic-proof")
        jdbc.update("insert into unified_admin_profiles(account_id,person_id) values(?,?)",admin.id,person)
        jdbc.update("update admin_users set access_mode='MANAGED' where id=?",admin.id)
        jdbc.update("insert into products(id,name,brand,vendor,mnn,price) values('producer-product','Synthetic','','','',0)")
        for (id in listOf("producer-a","producer-b","producer-empty")) jdbc.update(
            "insert into promos(id,title,brand,status) values(?,?,?,'active')",id,"=Literal $id","Synthetic company")
        producer=UUID.randomUUID();other=UUID.randomUUID()
        for (id in listOf(producer,other)) jdbc.update("""insert into merch_producers(id,source_system,source_origin,source_id,source_payload,name,phone,created_at,updated_at)
            values(?,'EPHARM','https://epharm.inkar.kz',?,?::jsonb,'Synthetic company','+77000000001',now(),now())""",id,id,
            """{"company_name":"Synthetic company","password_hash":"synthetic-secret-hash","iban":"synthetic-bank-private"}""")
        permissions()
    }
    private fun grant(module:String,section:String,action:String,all:Boolean=true)=StoredGrant(module,section,action,all,
        if(all) emptySet() else setOf("synthetic-pharmacy"))
    private fun permissions(manage:Boolean=true,report:Boolean=true,export:Boolean=true,all:Boolean=true) {
        val grants=mutableListOf(grant("motivation","campaigns","view",all))
        if(manage) grants+=grant("motivation","campaigns","manage_producer",all)
        if(report) grants+=grant("analytics","campaign_reports","view",all)
        if(export) grants+=grant("analytics","campaign_reports","export",all)
        jdbc.update("update unified_admin_profiles set grants_json=?::jsonb,version=version+1 where account_id=?",json.writeValueAsString(grants),admin.id)
    }
    private fun bind(id:String="producer-a",revision:Long=0,target:UUID=producer,whole:Boolean=true,start:LocalDate?=null,end:LocalDate?=null)=
        mvc.perform(put("/api/admin/promo/$id/producer").header("Authorization",auth).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsBytes(BindPromoProducer(revision,target,whole,start,end,reference))))
    private fun view(id:String="producer-a")=mvc.perform(get("/api/admin/promo/$id/producer").header("Authorization",auth))
    private fun workbook(target:UUID=producer)=mvc.perform(get("/api/admin/reports/producers/$target/xlsx").header("Authorization",auth)
        .param("from",from.toString()).param("to",to.toString()))
    private fun retract(id:String,binding:UUID,revision:Long)=mvc.perform(post("/api/admin/promo/$id/producer/retractions")
        .header("Authorization",auth).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(RetractPromoProducer(revision,binding,reference))))

    @Test fun `brand names and creator never create a producer binding and verified reference stays private`() {
        view().andExpect(status().isOk).andExpect(jsonPath("$.revision").value(0)).andExpect(jsonPath("$.bindings").isEmpty)
        bind().andExpect(status().isOk).andExpect(jsonPath("$.revision").value(1)).andExpect(jsonPath("$.bindings[0].producerId").value(producer.toString()))
        val body=view().andExpect(status().isOk).andReturn().response.contentAsString
        assertFalse(body.contains(reference));assertFalse(body.contains("verificationReference"));assertFalse(body.contains("+77000000001"))
        assertEquals(reference,jdbc.queryForObject("select verification_reference from promo_producer_bindings",String::class.java))
        assertEquals(1,jdbc.queryForObject("select count(*) from promo_producer_bindings",Int::class.java))
    }

    @Test fun `version CAS and inclusive overlap protect ownership across any producer while adjacent intervals are allowed`() {
        bind(whole=false,start=from,end=from).andExpect(status().isOk)
        bind(revision=0,target=other,whole=false,start=to,end=to).andExpect(status().isConflict)
        bind(revision=1,target=other,whole=false,start=from,end=to).andExpect(status().isConflict)
        bind(revision=1,target=other,whole=false,start=from.plusDays(1),end=to).andExpect(status().isOk)
        bind(revision=2).andExpect(status().isConflict)
        assertEquals(2,jdbc.queryForObject("select count(*) from promo_producer_bindings",Int::class.java))
        assertEquals(2L,jdbc.queryForObject("select revision from promo_producer_binding_versions",Long::class.java))
    }

    @Test fun `retraction is append only and removes attribution without deleting original binding`() {
        bind().andExpect(status().isOk)
        val id=jdbc.queryForObject("select id from promo_producer_bindings",UUID::class.java)!!
        retract("producer-a",id,0).andExpect(status().isConflict)
        retract("producer-b",id,0).andExpect(status().isNotFound)
        retract("producer-a",id,1).andExpect(status().isOk).andExpect(jsonPath("$.revision").value(2))
        retract("producer-a",id,2).andExpect(status().isConflict)
        val body=view().andExpect(status().isOk).andExpect(jsonPath("$.bindings[0].retractionRevision").value(2)).andReturn().response.contentAsString
        assertFalse(body.contains(reference));assertEquals(1,jdbc.queryForObject("select count(*) from promo_producer_bindings",Int::class.java))
        workbook().andExpect(status().isOk).andReturn().response.contentAsByteArray.let { file ->
            XSSFWorkbook(ByteArrayInputStream(file)).use { assertEquals(0,it.getSheet("Кампании").lastRowNum) }
        }
    }

    @Test fun `new writes require complete company explicit ALL HQ grant and a fresh nonrevoked profile`() {
        jdbc.update("update merch_producers set name=null where id=?",producer)
        bind().andExpect(status().isConflict).andExpect(jsonPath("$.code").value("COMPLETE_PROFILE"))
        jdbc.update("update merch_producers set name='Synthetic company' where id=?",producer)
        permissions(manage=false);bind().andExpect(status().isForbidden)
        permissions(all=false);bind().andExpect(status().isForbidden)
        permissions();jdbc.update("update admin_users set role='FINANCE_REVIEWER' where id=?",admin.id)
        bind().andExpect(status().isForbidden)
        jdbc.update("update admin_users set role='HQ_HEAD' where id=?",admin.id)
        jdbc.update("delete from unified_admin_profiles where account_id=?",admin.id)
        bind().andExpect(status().isForbidden)
        assertEquals(0,jdbc.queryForObject("select count(*) from promo_producer_bindings",Int::class.java))
    }

    @Test fun `empty report has truthful zero counts blank conversion and HQ unbound diagnostics without inferred companies`() {
        jdbc.update("update merch_producers set name=null where id=?",other)
        val index=mvc.perform(get("/api/admin/reports/producers").header("Authorization",auth)).andExpect(status().isOk)
            .andExpect(jsonPath("$.unboundCampaigns.length()").value(3)).andReturn().response.contentAsString
        assertFalse(index.contains(reference));assertFalse(index.contains("synthetic-secret-hash"));assertFalse(index.contains("synthetic-bank-private"))
        val file=workbook().andExpect(status().isOk).andExpect(header().string("Cache-Control","no-store")).andReturn().response.contentAsByteArray
        XSSFWorkbook(ByteArrayInputStream(file)).use { book ->
            assertEquals(0.0,book.getSheet("Сводка").getRow(1).getCell(3).numericCellValue)
            assertEquals(CellType.BLANK,book.getSheet("Сводка").getRow(1).getCell(8).cellType)
            assertEquals(0,book.getSheet("Кампании").lastRowNum)
            assertEquals(1,book.getSheet("Дни").drawingPatriarch.charts.size)
        }
    }

    @Test fun `report export requires view plus export ALL and legacy role alone cannot open producer data`() {
        permissions(export=false);workbook().andExpect(status().isForbidden)
        permissions(report=false);workbook().andExpect(status().isForbidden)
        permissions(all=false);workbook().andExpect(status().isForbidden)
        jdbc.update("update admin_users set access_mode='LEGACY' where id=?",admin.id)
        jdbc.update("delete from unified_admin_profiles where account_id=?",admin.id)
        workbook().andExpect(status().isForbidden);view().andExpect(status().isForbidden)
    }

    @Test fun `invalid interval and unknown request fields never create a guessed binding`() {
        bind(whole=false,start=to,end=from).andExpect(status().isBadRequest)
        bind(start=from,end=to).andExpect(status().isBadRequest)
        bind(whole=false).andExpect(status().isBadRequest)
        mvc.perform(put("/api/admin/promo/producer-a/producer").header("Authorization",auth).contentType(MediaType.APPLICATION_JSON)
            .content("""{"expectedRevision":0,"producerId":"$producer","wholeHistory":true,"reference":"proof","brand":"guess"}"""))
            .andExpect(status().isBadRequest)
        assertEquals(0,jdbc.queryForObject("select count(*) from promo_producer_bindings",Int::class.java))
    }

    @Test fun `producer workbook clips cohorts preserves boundary sale computes weighted conversion and excludes foreign campaigns`() {
        bind(whole=false,start=from,end=from).andExpect(status().isOk)
        bind(id="producer-b").andExpect(status().isOk)
        bind(id="producer-empty",target=other).andExpect(status().isOk)
        event("boundary","producer-a","2026-10-01T23:59:30+05:00","boundary-sale","2026-10-02T00:00:30+05:00")
        event("outside-binding","producer-a","2026-10-02T12:00:00+05:00","outside-sale","2026-10-02T12:00:30+05:00")
        event("b-sale","producer-b","2026-10-03T12:00:00+05:00","b-sale-id","2026-10-03T12:01:00+05:00")
        event("b-no-sale-1","producer-b","2026-10-03T12:10:00+05:00")
        event("b-no-sale-2","producer-b","2026-10-03T12:20:00+05:00")
        event("foreign","producer-empty","2026-10-03T12:30:00+05:00","foreign-sale","2026-10-03T12:30:30+05:00")
        val file=workbook().andExpect(status().isOk).andReturn().response.contentAsByteArray
        XSSFWorkbook(ByteArrayInputStream(file)).use { book ->
            val totals=book.getSheet("Сводка").getRow(1)
            assertEquals(4.0,totals.getCell(3).numericCellValue)
            assertEquals(2.0,totals.getCell(5).numericCellValue)
            assertEquals(2.0,totals.getCell(7).numericCellValue)
            assertEquals(50.0,totals.getCell(8).numericCellValue)
            assertEquals(2,book.getSheet("Кампании").lastRowNum)
            val a=book.getSheet("Кампании").first { it.rowNum>0 && it.getCell(0).stringCellValue=="producer-a" }
            assertEquals(CellType.STRING,a.getCell(1).cellType);assertTrue(a.getCell(1).cellStyle.quotePrefixed)
            val content=book.flatMap { sheet -> sheet.flatMap { row -> row.map { it.toString() } } }.joinToString("\n")
            for(secret in listOf("producer-empty",reference,"+77000000001","synthetic-secret-hash","synthetic-bank-private")) assertFalse(content.contains(secret))
            assertTrue(book.all { sheet -> sheet.all { row -> row.none { it.cellType==CellType.FORMULA } } })
        }
    }

    @Test fun `disjoint approved intervals share one canonical sale rank and unapproved gap does not enter cohort`() {
        bind(whole=false,start=from,end=from).andExpect(status().isOk)
        bind(revision=1,whole=false,start=to,end=to).andExpect(status().isOk)
        event("first-ack","producer-a","2026-10-01T12:00:00+05:00","one-receipt","2026-10-03T12:01:00+05:00")
        event("last-ack","producer-a","2026-10-03T12:00:30+05:00","one-receipt","2026-10-03T12:01:00+05:00")
        event("gap","producer-a","2026-10-02T12:00:00+05:00")
        val file=workbook().andExpect(status().isOk).andReturn().response.contentAsByteArray
        XSSFWorkbook(ByteArrayInputStream(file)).use { book ->
            val totals=book.getSheet("Сводка").getRow(1)
            assertEquals(2.0,totals.getCell(3).numericCellValue)
            assertEquals(1.0,totals.getCell(5).numericCellValue)
            assertEquals(1.0,totals.getCell(7).numericCellValue)
            assertEquals(50.0,totals.getCell(8).numericCellValue)
            assertEquals(2,book.getSheet("Интервалы связей").lastRowNum)
        }
        val canonicalCounts=canonical.aggregate("producer-a",OffsetDateTime.parse("2026-10-01T00:00:00+05:00").toInstant(),
            OffsetDateTime.parse("2026-10-04T00:00:00+05:00").toInstant()).fold(ReportCounts()) { sum,row -> sum+row.counts }
        assertEquals(3L,canonicalCounts.confirmed);assertEquals(1L,canonicalCounts.attributedSales)
    }

    @Test fun `HQ manufacturer creation is idempotent exact resource provenance with no phone login person or organization`() {
        val before=identityCounts()
        val request=CreateManufacturer(UUID.randomUUID(),"Explicit business manufacturer",reference)
        val response=createManufacturer(request).andExpect(status().isOk).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.id").value(request.requestId.toString())).andExpect(jsonPath("$.name").value(request.name))
            .andExpect(jsonPath("$.profileComplete").value(true)).andReturn().response.contentAsString
        assertEquals(3,json.readTree(response).size());assertFalse(response.contains(reference))
        createManufacturer(request).andExpect(status().isOk)
        val row=jdbc.queryForMap("select * from merch_producers where id=?",request.requestId)
        assertEquals("EPHARM",row["source_system"]);assertEquals("https://epharm.inkar.kz",row["source_origin"]);assertEquals(request.requestId,row["source_id"])
        assertEquals("internal_manufacturer",row["source_account_role"])
        for(field in listOf("phone","phone_normalized","phone_verified_at")) assertNull(row[field])
        assertEquals("HQ_DEFINED_MANUFACTURER",json.readTree(row["source_payload"].toString())["kind"].asText())
        assertEquals(admin.id.toString(),json.readTree(row["source_payload"].toString())["createdByAdminId"].asText())
        assertFalse(row["source_payload"].toString().contains(reference))
        assertEquals(1,jdbc.queryForObject("select count(*) from unified_source_links where source_entity_type='MANUFACTURER' AND source_id=? AND canonical_entity_type='CUSTOMER' AND canonical_id=? AND verification_reference=?",
            Int::class.java,request.requestId.toString(),request.requestId.toString(),reference))
        assertEquals(1,jdbc.queryForObject("select count(*) from unified_identity_audit where operation='CREATE_MANUFACTURER' AND entity_id=?",Int::class.java,request.requestId.toString()))
        assertEquals(before,identityCounts())
    }

    @Test fun `manufacturer request cannot overwrite a different name proof or creator with the same resource UUID`() {
        val request=CreateManufacturer(UUID.randomUUID(),"Stable manufacturer",reference)
        createManufacturer(request).andExpect(status().isOk)
        createManufacturer(request.copy(name="Different manufacturer")).andExpect(status().isConflict)
        createManufacturer(request.copy(reference="Different private proof")).andExpect(status().isConflict)
        val otherAdmin=users.saveAndFlush(AdminUserEntity(email="other-manufacturer-hq@example.invalid",name="Synthetic HQ",passwordHash="unused",roleRaw=AdminRole.HQ_HEAD.name))
        val person=UUID.randomUUID();jdbc.update("insert into unified_persons values(?,?)",person,"synthetic-proof")
        jdbc.update("insert into unified_admin_profiles(account_id,person_id,grants_json) values(?,?,?)",otherAdmin.id,person,json.writeValueAsString(listOf(
            grant("motivation","campaigns","view"),grant("motivation","campaigns","manage_producer"))))
        jdbc.update("update admin_users set access_mode='MANAGED' where id=?",otherAdmin.id)
        createManufacturer(request,"Bearer ${jwt.issueAccessToken(otherAdmin)}").andExpect(status().isConflict)
        assertEquals(request.name,jdbc.queryForObject("select name from merch_producers where id=?",String::class.java,request.requestId))
        assertEquals(reference,jdbc.queryForObject("select verification_reference from unified_source_links where source_entity_type='MANUFACTURER' AND source_id=?",String::class.java,request.requestId.toString()))
        assertEquals(1,jdbc.queryForObject("select count(*) from unified_identity_audit where entity_id=? AND operation='CREATE_MANUFACTURER'",Int::class.java,request.requestId.toString()))
    }

    @Test fun `imported customer UUID is never overwritten and a duplicate company name is a separate explicit resource`() {
        val id=UUID.randomUUID();val source="{\"legacy\":\"preserved source evidence\"}"
        jdbc.update("""insert into merch_producers(id,source_system,source_origin,source_id,source_payload,name,created_at,updated_at)
            values(?,'MERCH_CRM','https://crm.inkar.kz',?,?::jsonb,'Imported company',now(),now())""",id,id,source)
        val before=jdbc.queryForObject("select source_payload::text from merch_producers where id=?",String::class.java,id)
        createManufacturer(CreateManufacturer(id,"Imported company",reference)).andExpect(status().isConflict)
        assertEquals(before,jdbc.queryForObject("select source_payload::text from merch_producers where id=?",String::class.java,id))
        val fresh=UUID.randomUUID()
        createManufacturer(CreateManufacturer(fresh,"Imported company",reference)).andExpect(status().isOk)
        assertEquals(2,jdbc.queryForObject("select count(*) from merch_producers where name='Imported company'",Int::class.java))
        assertEquals(0,jdbc.queryForObject("select count(*) from unified_source_links where source_entity_type='MANUFACTURER' AND source_id=?",Int::class.java,id.toString()))
    }

    @Test fun `manufacturer create requires HQ explicit sensitive ALL and separate campaign view without legacy role fallback`() {
        val request=CreateManufacturer(UUID.randomUUID(),"Denied manufacturer",reference)
        permissions(manage=false);createManufacturer(request).andExpect(status().isForbidden)
        permissions(all=false);createManufacturer(request).andExpect(status().isForbidden)
        permissions();jdbc.update("update admin_users set role='FINANCE_REVIEWER' where id=?",admin.id)
        createManufacturer(request).andExpect(status().isForbidden)
        jdbc.update("update admin_users set role='HQ_HEAD' where id=?",admin.id)
        jdbc.update("update unified_admin_profiles set grants_json=? where account_id=?",json.writeValueAsString(listOf(grant("motivation","campaigns","manage_producer"))),admin.id)
        createManufacturer(request).andExpect(status().isForbidden)
        jdbc.update("update admin_users set access_mode='LEGACY' where id=?",admin.id)
        jdbc.update("delete from unified_admin_profiles where account_id=?",admin.id)
        createManufacturer(request).andExpect(status().isForbidden)
        assertEquals(0,jdbc.queryForObject("select count(*) from merch_producers where id=?",Int::class.java,request.requestId))
    }

    @Test fun `manufacturer input cannot smuggle portal phone identity blank names or missing proof`() {
        val request=CreateManufacturer(UUID.randomUUID(),"Valid manufacturer",reference)
        for(invalid in listOf(request.copy(name="  "),request.copy(reference="  "),request.copy(name="x".repeat(256)),request.copy(name="Bad\u0000name")))
            createManufacturer(invalid).andExpect(status().isBadRequest)
        mvc.perform(post("/api/admin/promo/producers").header("Authorization",auth).contentType(MediaType.APPLICATION_JSON)
            .content("""{"requestId":"${request.requestId}","name":"Valid","reference":"proof","phone":"+77000000001"}"""))
            .andExpect(status().isBadRequest)
        assertEquals(0,jdbc.queryForObject("select count(*) from merch_producers where id=?",Int::class.java,request.requestId))
    }

    @Test fun `HQ manufacturer can bind a campaign and enter report picker without exposing provenance or creating an advertiser login`() {
        val request=CreateManufacturer(UUID.randomUUID(),"=Literal business manufacturer",reference)
        createManufacturer(request).andExpect(status().isOk)
        bind(target=request.requestId).andExpect(status().isOk)
        val index=mvc.perform(get("/api/admin/reports/producers").header("Authorization",auth)).andExpect(status().isOk).andReturn().response.contentAsString
        val entry=json.readTree(index)["producers"].first {it["id"].asText()==request.requestId.toString()}
        assertTrue(entry["profileComplete"].asBoolean());assertEquals(1,entry["activeBindingCount"].asInt())
        assertFalse(index.contains(reference));assertFalse(index.contains("createdByAdminId"));assertFalse(index.contains("HQ_DEFINED_MANUFACTURER"))
        val file=workbook(request.requestId).andExpect(status().isOk).andReturn().response.contentAsByteArray
        XSSFWorkbook(ByteArrayInputStream(file)).use {book->
            val text=book.flatMap {sheet->sheet.flatMap {row->row.map {it.toString()}}}.joinToString("\n")
            for(privateValue in listOf(reference,"createdByAdminId","HQ_DEFINED_MANUFACTURER")) assertFalse(text.contains(privateValue))
            assertEquals(1,book.getSheet("Кампании").lastRowNum)
            assertTrue(book.all {sheet->sheet.all {row->row.none {it.cellType==CellType.FORMULA}}})
        }
        assertEquals(0,jdbc.queryForObject("select count(*) from merch_producer_sessions where customer_id=?",Int::class.java,request.requestId))
        assertEquals(0,jdbc.queryForObject("select count(*) from merch_producer_challenges where customer_id=?",Int::class.java,request.requestId))
    }

    private fun createManufacturer(request:CreateManufacturer,bearer:String=auth)=mvc.perform(post("/api/admin/promo/producers")
        .header("Authorization",bearer).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request)))
    private fun identityCounts()=listOf("admin_users","unified_persons","pharmacists","unified_external_accounts","merch_organizations","merch_producer_sessions","merch_producer_challenges")
        .associateWith {jdbc.queryForObject("select count(*) from $it",Long::class.java)}

    private fun event(id:String,promo:String,display:String,sale:String?=null,sold:String?=null) {
        val rule="rule-$promo"
        jdbc.update("""insert into rules(id,type,status,trigger,recommend,promo_id) values(?,'substitution','active',
            '{"kind":"product","value":"producer-product"}'::jsonb,'producer-product',?) ON CONFLICT DO NOTHING""",rule,promo)
        val session="session-${sale?:id}"
        fun time(value:String)=Timestamp.from(OffsetDateTime.parse(value).toInstant())
        if(sale!=null) jdbc.update("""insert into pos_sales(id,session_id,pharmacist_id,pharmacy_id,printed_at)
            values(?,?,'synthetic-person','synthetic-pharmacy',?) ON CONFLICT DO NOTHING""",sale,session,time(sold!!))
        jdbc.update("""insert into recommendation_events(id,session_id,pharmacist_id,pharmacy_id,rule_id,kind,recommend_sku,shown_at,displayed_at,
            outcome,decided_at,sale_id,sold_at) values(?,?,'synthetic-person','synthetic-pharmacy',?,'substitution','producer-product',?,?,'accepted',?,?,?)""",
            id,session,rule,time(display),time(display),time(sold?:display),sale,sold?.let(::time))
    }
}
