package kz.epharm.merch.commercial

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.entity.AdminUserEntity
import kz.epharm.auth.repository.AdminUserRepository
import kz.epharm.auth.service.JwtService
import kz.epharm.merch.placements.dto.*
import kz.epharm.merch.producer.auth.ProducerPrincipal
import kz.epharm.merch.producer.auth.ProducerTokenCodec
import kz.epharm.merch.tasks.TaskEvidenceStorage
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.zip.CRC32
import javax.imageio.ImageIO

@SpringBootTest(properties=["app.merch.producer-auth-enabled=true"])
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class CommercialIntegrationTest {
    companion object {
        @Container @JvmStatic val postgres=PostgreSQLContainer("postgres:16-alpine").withDatabaseName("commercial_disposable")
            .withUsername("epharm").withPassword("synthetic-only").apply {start()}
        private val root=Files.createTempDirectory("epharm-commercial-synthetic-").toRealPath().also {Files.setPosixFilePermissions(it,PosixFilePermissions.fromString("rwx------"))}
        @JvmStatic @DynamicPropertySource fun props(reg:DynamicPropertyRegistry) {
            reg.add("spring.datasource.url"){postgres.jdbcUrl};reg.add("spring.datasource.username"){postgres.username};reg.add("spring.datasource.password"){postgres.password}
            reg.add("app.merch-tasks.evidence-root"){root.toString()}
        }
    }
    @Autowired lateinit var mvc:MockMvc
    @Autowired lateinit var jdbc:JdbcTemplate
    @Autowired lateinit var named:NamedParameterJdbcTemplate
    @Autowired lateinit var json:ObjectMapper
    @Autowired lateinit var users:AdminUserRepository
    @Autowired lateinit var jwt:JwtService
    @Autowired lateinit var codec:ProducerTokenCodec
    @Autowired lateinit var files:TaskEvidenceStorage
    private lateinit var admin:AdminUserEntity
    private lateinit var adminToken:String
    private lateinit var producerToken:String
    private lateinit var otherToken:String
    private lateinit var producer:UUID
    private lateinit var other:UUID
    private lateinit var branch:UUID
    private lateinit var foreign:UUID
    private lateinit var shelf:UUID
    private lateinit var secondShelf:UUID
    private lateinit var foreignShelf:UUID
    private val month get()=LocalDate.now(ZoneId.of("Asia/Almaty")).withDayOfMonth(1).plusMonths(1)
    private val actions=listOf("view","edit","submit","approve","reject","cancel","assign","financial_data","export")

    @BeforeEach fun seed() {
        jdbc.execute("""TRUNCATE merch_commercial_audit,merch_supplier_budget_plan_items,merch_supplier_budget_plans,merch_supplier_settings,
            merch_supplier_company_members,merch_supplier_pricing_tiers,merch_placement_campaigns,merch_placement_audit,
            merch_placement_item_product_details,merch_media_plan_items,merch_media_plans,merch_monthly_inventory,merch_inventory_media,
            merch_shelves,merch_inventory_containers,merch_branches,merch_organizations,merch_producers,
            unified_admin_profiles,unified_persons,admin_users CASCADE""")
        admin=users.saveAndFlush(AdminUserEntity(email="commercial@example.test",name="Synthetic operator",passwordHash="unused",roleRaw=AdminRole.HQ_HEAD.name))
        adminToken="Bearer ${jwt.issueAccessToken(admin)}"
        jdbc.update("UPDATE admin_users SET access_mode='MANAGED' WHERE id=?",admin.id)
        val person=UUID.randomUUID();jdbc.update("INSERT INTO unified_persons(id,verification_reference) VALUES(?,?)",person,"synthetic-explicit-person")
        jdbc.update("INSERT INTO unified_admin_profiles(account_id,person_id) VALUES(?,?)",admin.id,person)
        val org=source("merch_organizations",mapOf("name" to "Synthetic network","legal_name" to "Synthetic network","active" to true))
        fun branch(code:String)=source("merch_branches",mapOf("organization_id" to org,"code" to code,"name" to code,"address" to "Synthetic address","city" to "Synthetic city","active" to true))
        branch=branch("SYNTH-A");foreign=branch("SYNTH-B")
        fun shelf(branch:UUID,code:String)=source("merch_shelves",mapOf("branch_id" to branch,"code" to code,"category" to "AA","width_m" to java.math.BigDecimal.ONE,
            "base_monthly_price" to 15000,"half_monthly_price" to 8000,"active" to true,"place_type" to "shelf","position_index" to 0,"specifications" to "{}","sale_mode" to "whole_or_halves"))
        shelf=shelf(branch,"S1");secondShelf=shelf(branch,"S2");foreignShelf=shelf(foreign,"S1")
        producer=source("merch_producers",mapOf("name" to "Synthetic company","source_account_role" to "advertiser","active" to true))
        other=source("merch_producers",mapOf("name" to "Other synthetic company","source_account_role" to "advertiser","active" to true))
        producerToken=producerBearer(producer,"+70000000001");otherToken=producerBearer(other,"+70000000002")
        grants()
        jdbc.update("INSERT INTO merch_supplier_pricing_tiers VALUES('standard','Synthetic tier',1.000,3000,10000,20000,1000,true,now(),'{\"synthetic\":true}')")
        jdbc.update("INSERT INTO merch_supplier_settings(producer_id,supplier_category,source_payload,updated_at) VALUES(?,'standard','{}',now())",producer)
    }
    private fun source(table:String,fields:Map<String,Any?>):UUID {
        val id=UUID.randomUUID();val row=linkedMapOf<String,Any?>("id" to id,"source_system" to "MERCH_CRM","source_origin" to "https://crm.inkar.kz","source_id" to id,"source_payload" to "{\"synthetic\":true}","created_at" to Timestamp.from(Instant.now()),"updated_at" to Timestamp.from(Instant.now()))
        row.putAll(fields)
        named.update("INSERT INTO $table(${row.keys.joinToString(",")}) VALUES(${row.keys.joinToString(","){if(it in setOf("source_payload","specifications")) "CAST(:$it AS jsonb)" else ":$it"}})",row)
        return id
    }
    private fun grants(all:Boolean=true,allowed:List<String> = actions) {
        val rows=allowed.map {mapOf("module" to "merchandising","subsection" to "placements","action" to it,"allPharmacies" to false,
            "allMerchBranches" to all,"merchBranchIds" to if(all) emptyList() else listOf(branch.toString()),"allProducers" to all,"producerIds" to if(all) emptyList() else listOf(producer.toString()))}
        jdbc.update("UPDATE unified_admin_profiles SET grants_json=?,version=version+1 WHERE account_id=?",json.writeValueAsString(rows),admin.id)
    }
    private fun producerBearer(id:UUID,phone:String):String {
        jdbc.update("UPDATE merch_producers SET phone=?,phone_normalized=?,phone_verified_at=now() WHERE id=?",phone,phone,id)
        val now=Instant.now();val expires=now.plusSeconds(3600);val principal=ProducerPrincipal(id,UUID.randomUUID());val token=codec.issue(principal,now,expires)
        jdbc.update("INSERT INTO merch_producer_sessions(id,customer_id,access_token_hash,phone_fingerprint,created_at,expires_at) VALUES(?,?,?,?,?,?)",principal.sessionId,id,ProducerTokenCodec.hash(token),codec.fingerprint(id,phone),Timestamp.from(now),Timestamp.from(expires))
        return "Bearer $token"
    }
    private fun write(path:String,body:Any,token:String=adminToken,put:Boolean=false):ResultActions=mvc.perform((if(put) put(path) else post(path)).header("Authorization",token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
    private fun tree(result:ResultActions)=json.readTree(result.andExpect(status().isOk).andReturn().response.contentAsByteArray)
    private fun data(twoMonths:Boolean=true,selected:UUID=shelf):CommercialPlanData {
        val months=if(twoMonths) listOf(month,month.plusMonths(1)) else listOf(month)
        val product=CommercialProduct("opaque-source-product", "Synthetic medicine", "SYNTH-SKU", "medicines","otc",2,months,listOf("AA"))
        return CommercialPlanData("Synthetic period",month,months.last(),100000,planningMode="products",productBriefs=listOf(product),
            shelfSelection=listOf(CommercialShelf("opaque-selection",selected,if(selected==foreignShelf) foreign else branch,placementMonths=months,products=listOf(product),price=15000)))
    }
    private fun save(id:UUID=UUID.randomUUID(),value:CommercialPlanData=data(),revision:Long=0,token:String=producerToken):JsonNode =tree(write("/api/producer/commercial/plans/$id",SaveCommercialPlan(revision,value),token,true))
    private fun submit(plan:JsonNode):JsonNode=tree(write("/api/producer/commercial/plans/${plan["id"].asText()}/submit",SubmitCommercialPlan(plan["revision"].asLong()),producerToken))
    private fun monthly(twoShelves:Boolean=false):JsonNode {
        val draft=tree(write("/api/admin/merch/placements",CreatePlacementDraft(producer,month,if(twoShelves) listOf(PlacementSelection(shelf),PlacementSelection(secondShelf)) else listOf(PlacementSelection(shelf)))))
        return tree(write("/api/admin/merch/placements/${draft["id"].asText()}/submit",PlacementTransition(draft["revision"].asLong())))
    }
    private fun count(table:String)=jdbc.queryForObject("SELECT count(*) FROM $table",Long::class.java)!!

    @Test fun `two month product plan reserves once with server facing tariff and one separate placement campaign`() {
        val id=UUID.randomUUID();val original=data();val saved=save(id,original)
        assertThat(saved["estimatedTotal"].asText()).isEqualTo("42000")
        val repeated=save(id,original)
        assertThat(repeated["revision"].asLong()).isEqualTo(saved["revision"].asLong())
        val submitted=submit(saved);val retry=submit(saved)
        assertThat(submitted["requestIds"].size()).isEqualTo(2);assertThat(retry["requestIds"]).isEqualTo(submitted["requestIds"])
        assertThat(submitted["monthlyRequests"].map {it["totalAmount"].asLong()}).containsExactly(21000L,21000L)
        assertThat(count("merch_media_plans")).isEqualTo(2);assertThat(count("merch_placement_campaigns")).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT campaign_id) FROM merch_media_plans",Long::class.java)).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT bool_and(source_campaign_id IS NULL AND campaign_id IS NOT NULL) FROM merch_media_plans",Boolean::class.java)).isTrue()
        assertThat(count("merch_placement_item_product_details")).isEqualTo(2)
        jdbc.update("UPDATE merch_supplier_pricing_tiers SET facing_month_price=4000")
        val frozen=tree(mvc.perform(get("/api/producer/commercial/plans/$id").header("Authorization",producerToken)))
        assertThat(frozen["monthlyRequests"].map {it["totalAmount"].asLong()}).containsExactly(21000L,21000L)
        assertThat(count("merch_task_rewards")).isZero();assertThat(count("merch_task_dispatches")).isZero()
        assertThat(frozen["paymentStatus"].asText()).isEqualTo("not_connected")
    }
    @Test fun `a conflict in the second month rolls back the entire campaign and first month reservation`() {
        tree(write("/api/admin/merch/admin-assignments",CreatePlacementDraft(other,month.plusMonths(1),listOf(PlacementSelection(shelf)))))
        val plan=save();val id=UUID.fromString(plan["id"].asText())
        write("/api/producer/commercial/plans/$id/submit",SubmitCommercialPlan(plan["revision"].asLong()),producerToken).andExpect(status().isConflict)
        assertThat(count("merch_media_plans")).isEqualTo(1);assertThat(count("merch_placement_campaigns")).isZero()
        assertThat(jdbc.queryForObject("SELECT cardinality(request_ids) FROM merch_supplier_budget_plans WHERE id=?",Int::class.java,id)).isZero()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merch_media_plans WHERE commercial_plan_id=?",Long::class.java,id)).isZero()
    }
    @Test fun `partial decisions preserve siblings original amount submitted time holds and immutable reasons`() {
        val pending=monthly(true);val id=UUID.fromString(pending["id"].asText());val ids=pending["items"].map {UUID.fromString(it["id"].asText())}
        val before=jdbc.queryForMap("SELECT submitted_at,held_until,total_amount FROM merch_media_plans WHERE id=?",id)
        val first=tree(write("/api/admin/merch/placements/$id/items/approve",PlacementItemReview(pending["revision"].asLong(),listOf(ids[0]))))
        assertThat(first["status"].asText()).isEqualTo("pending");assertThat(first["approvedAmount"].asLong()).isEqualTo(15000);assertThat(first["pendingAmount"].asLong()).isEqualTo(15000)
        assertThat(jdbc.queryForMap("SELECT submitted_at,held_until,total_amount FROM merch_media_plans WHERE id=?",id)).isEqualTo(before)
        val final=tree(write("/api/admin/merch/placements/$id/items/reject",PlacementItemReview(first["revision"].asLong(),listOf(ids[1]),"Synthetic selected item reason")))
        assertThat(final["status"].asText()).isEqualTo("approved");assertThat(final["totalAmount"].asLong()).isEqualTo(30000)
        assertThat(final["items"].map {it["status"].asText()}).containsExactlyInAnyOrder("approved","rejected")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merch_placement_audit WHERE plan_id=? AND action IN ('items_approve','items_reject')",Long::class.java,id)).isEqualTo(2)
        assertThat(count("merch_task_rewards")).isZero()
    }
    @Test fun `stale foreign and duplicate partial decisions cannot change any position`() {
        val plan=monthly(true);val id=plan["id"].asText();val item=UUID.fromString(plan["items"][0]["id"].asText());val revision=plan["revision"].asLong()
        write("/api/admin/merch/placements/$id/items/approve",PlacementItemReview(revision-1,listOf(item))).andExpect(status().isConflict)
        write("/api/admin/merch/placements/$id/items/reject",PlacementItemReview(revision,listOf(item))).andExpect(status().isBadRequest)
        write("/api/admin/merch/placements/$id/items/approve",PlacementItemReview(revision,listOf(item,item))).andExpect(status().isBadRequest)
        write("/api/admin/merch/placements/$id/items/approve",PlacementItemReview(revision,listOf(UUID.randomUUID()))).andExpect(status().isConflict)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merch_media_plan_items WHERE status='pending'",Long::class.java)).isEqualTo(2)
    }
    @Test fun `both distinct halves are counted and booked while whole overlap and duplicate halves are rejected`() {
        for(selections in listOf(listOf(PlacementSelection(shelf),PlacementSelection(shelf,PlacementPortion.left)),listOf(PlacementSelection(shelf,PlacementPortion.left),PlacementSelection(shelf,PlacementPortion.left))))
            write("/api/admin/merch/placements",CreatePlacementDraft(producer,month,selections)).andExpect(status().isBadRequest)
        val draft=tree(write("/api/admin/merch/placements",CreatePlacementDraft(producer,month,listOf(PlacementSelection(shelf,PlacementPortion.left),PlacementSelection(shelf,PlacementPortion.right)))))
        assertThat(draft["items"].size()).isEqualTo(2);assertThat(draft["totalAmount"].asLong()).isEqualTo(16000)
        val pending=tree(write("/api/admin/merch/placements/${draft["id"].asText()}/submit",PlacementTransition(draft["revision"].asLong())))
        tree(write("/api/admin/merch/placements/${pending["id"].asText()}/approve",PlacementTransition(pending["revision"].asLong())))
        val overlapping=tree(write("/api/admin/merch/placements",CreatePlacementDraft(other,month,listOf(PlacementSelection(shelf)))))
        write("/api/admin/merch/placements/${overlapping["id"].asText()}/submit",PlacementTransition(overlapping["revision"].asLong())).andExpect(status().isConflict)
    }
    @Test fun `budget and estimate bigint values stay exact and source item IDs snapshots and history survive editing`() {
        val itemId=UUID.randomUUID();val id=UUID.randomUUID()
        val activity=CommercialActivity(itemId,"Synthetic budget item",activityType="placement",activeMonths=listOf(month),pharmacyCount=10000,facingCount=100,unitPrice=Long.MAX_VALUE)
        val value=CommercialPlanData("Source-preserved budget",month,month,9_007_199_254_740_993L,items=listOf(activity))
        val original=save(id,value)
        assertThat(original["plan"]["budgetLimit"].asText()).isEqualTo("9007199254740993")
        assertThat(original["estimatedTotal"].asText()).isEqualTo("9223372036854775807000000")
        jdbc.update("UPDATE merch_supplier_budget_plan_items SET source_system='MERCH_CRM',source_origin='https://crm.inkar.kz',source_payload='{\"original\":true}' WHERE id=?",itemId)
        val edited=save(id,value.copy(title="Explicit new draft title"),original["revision"].asLong())
        save(id,value.copy(title="Explicit retired line",items=emptyList()),edited["revision"].asLong())
        assertThat(jdbc.queryForObject("SELECT source_payload::text FROM merch_supplier_budget_plan_items WHERE id=?",String::class.java,itemId)).isEqualTo("{\"original\": true}")
        assertThat(jdbc.queryForObject("SELECT retired_at IS NOT NULL FROM merch_supplier_budget_plan_items WHERE id=?",Boolean::class.java,itemId)).isTrue()
        assertThat(count("merch_supplier_budget_plan_items")).isEqualTo(1)
        assertThat(count("merch_commercial_audit")).isEqualTo(3)
    }
    @Test fun `explicit scopes and revocation hide global empty budgets and financial fields without disabling scoped monthly plans`() {
        val empty=save(value=CommercialPlanData("Global empty budget",month,month,123456))
        val selected=save(value=data(false))
        val monthly=monthly()
        grants(false)
        mvc.perform(get("/api/admin/merch/commercial/plans/${empty["id"].asText()}").header("Authorization",adminToken)).andExpect(status().isForbidden)
        val list=tree(mvc.perform(get("/api/admin/merch/commercial/plans").header("Authorization",adminToken)))
        assertThat(list["total"].asLong()).isEqualTo(1)
        mvc.perform(get("/api/admin/merch/placements/${monthly["id"].asText()}").header("Authorization",adminToken)).andExpect(status().isOk)
        grants(false,actions-"financial_data")
        val hidden=tree(mvc.perform(get("/api/admin/merch/commercial/plans/${selected["id"].asText()}").header("Authorization",adminToken)))
        assertThat(hidden["plan"]["budgetLimit"].isNull).isTrue();assertThat(hidden["estimatedTotal"].isNull).isTrue()
        mvc.perform(get("/api/admin/merch/commercial/plans/${selected["id"].asText()}/media-kit.xlsx").header("Authorization",adminToken)).andExpect(status().isForbidden)
        write("/api/admin/merch/commercial/producers/$producer/category",SetCommercialCategory(0,"standard"),put=true).andExpect(status().isForbidden)
        jdbc.update("UPDATE unified_admin_profiles SET grants_json='[]',version=version+1 WHERE account_id=?",admin.id)
        write("/api/admin/merch/commercial/plans/${selected["id"].asText()}?producerId=$producer",SaveCommercialPlan(selected["revision"].asLong(),data(false)),put=true).andExpect(status().isForbidden)
    }
    @Test fun `team metadata does not create a person account or login and never crosses the producer realm`() {
        val people=count("unified_persons");val accounts=count("admin_users");val id=UUID.randomUUID()
        val body=SaveCommercialMember(0,"Synthetic member","member@example.test","Synthetic role","planner",true)
        val member=tree(write("/api/producer/commercial/members/$id",body,producerToken,true))
        tree(write("/api/producer/commercial/members/$id",body,producerToken,true))
        write("/api/producer/commercial/members/$id",body,otherToken,true).andExpect(status().isForbidden)
        tree(write("/api/producer/commercial/members/$id",body.copy(expectedRevision=member["revision"].asLong(),active=false),producerToken,true))
        assertThat(count("unified_persons")).isEqualTo(people);assertThat(count("admin_users")).isEqualTo(accounts)
        assertThat(count("merch_supplier_company_members")).isEqualTo(1)
        val foreign=tree(mvc.perform(get("/api/producer/commercial/workspace").header("Authorization",otherToken)))
        assertThat(foreign["members"].size()).isZero()
        mvc.perform(get("/api/producer/commercial/workspace").header("Authorization",adminToken)).andExpect(status().is4xxClientError)
    }
    @Test fun `copy is a new draft with exact retry provenance and never overwrites a different intent or source`() {
        val original=save();val id=UUID.fromString(original["id"].asText());val newId=UUID.randomUUID()
        val body=CopyCommercialPlan(original["revision"].asLong(),newId,month.plusMonths(2))
        val copy=tree(write("/api/producer/commercial/plans/$id/copy",body,producerToken))
        tree(write("/api/producer/commercial/plans/$id/copy",body,producerToken))
        assertThat(copy["copiedFrom"].asText()).isEqualTo(id.toString());assertThat(copy["requestIds"].size()).isZero()
        write("/api/producer/commercial/plans/$id/copy",body.copy(startMonth=month.plusMonths(3)),producerToken).andExpect(status().isConflict)
        assertThat(count("merch_supplier_budget_plans")).isEqualTo(2);assertThat(count("merch_media_plans")).isZero()
        assertThat(tree(mvc.perform(get("/api/producer/commercial/plans/$id").header("Authorization",producerToken)))["revision"]).isEqualTo(original["revision"])
    }
    @Test fun `CSV preview is read only rejects ambiguity foreign branches duplicates bad quotes and cross taxonomy`() {
        val csv="branch_code;shelf_code;month\nSYNTH-A;S1;${month.toString().take(7)}\nSYNTH-A;S1;${month.toString().take(7)}"
        val preview=tree(write("/api/producer/commercial/plans/import-preview",CommercialCsvText(csv),producerToken))
        assertThat(preview["valid"].asBoolean()).isFalse();assertThat(preview["rows"].size()).isEqualTo(2);assertThat(preview["placements"].size()).isZero()
        grants(false)
        val denied=tree(write("/api/admin/merch/commercial/plans/import-preview?producerId=$producer",CommercialCsvText("код филиала;код места;месяц\nSYNTH-B;S1;${month.toString().take(7)}")))
        assertThat(denied["rows"][0]["shelfId"].isNull).isTrue();assertThat(denied["valid"].asBoolean()).isFalse()
        write("/api/producer/commercial/plans/import-preview",CommercialCsvText("branch_code;shelf_code;month\n\"unclosed;S1;2027-01"),producerToken).andExpect(status().isBadRequest)
        val invalid=data(false).let {it.copy(productBriefs=it.productBriefs.map {p->p.copy(productSubcategory="orthopedics")})}
        write("/api/producer/commercial/plans/${UUID.randomUUID()}",SaveCommercialPlan(0,invalid),producerToken,true).andExpect(status().isBadRequest)
        assertThat(count("merch_supplier_budget_plans")).isZero();assertThat(count("merch_media_plans")).isZero()
    }
    @Test fun `legacy missing and foreign requests remain visible as unresolved without inferring money or losing implicit product months`() {
        val values=data().let {d->val months=listOf(month,month.plusMonths(1),month.plusMonths(2));d.copy(endMonth=months.last(),
            productBriefs=d.productBriefs.map {it.copy(activeMonths=months)},shelfSelection=d.shelfSelection!!.map {row->row.copy(placementMonths=months,products=row.products.map {it.copy(activeMonths=months)})})}
        val sent=submit(save(value=values));val id=UUID.fromString(sent["id"].asText())
        val known=sent["requestIds"].map {UUID.fromString(it.asText())};assertThat(known).hasSize(3)
        val foreignPlan=tree(write("/api/admin/merch/placements",CreatePlacementDraft(other,month,listOf(PlacementSelection(foreignShelf)))))
        val untyped=tree(write("/api/admin/merch/placements",CreatePlacementDraft(producer,month,listOf(PlacementSelection(secondShelf)))))
        val unbound=tree(write("/api/admin/merch/placements",CreatePlacementDraft(producer,month.plusMonths(3),listOf(PlacementSelection(secondShelf)))))
        jdbc.update("UPDATE merch_media_plans SET producer_id=NULL,commercial_plan_id=?,campaign_id=(SELECT campaign_id FROM merch_media_plans WHERE id=?) WHERE id=?",id,known.first(),UUID.fromString(unbound["id"].asText()))
        val wrong=listOf(UUID.fromString(foreignPlan["id"].asText()),UUID.fromString(untyped["id"].asText()),UUID.fromString(unbound["id"].asText()))
        val absent=List(15){UUID.randomUUID()};val references=known+absent+wrong
        val selection=json.valueToTree<JsonNode>(values.shelfSelection)
        selection.forEach {row->row["products"].forEach {p->(p as com.fasterxml.jackson.databind.node.ObjectNode).remove("activeMonths")}}
        jdbc.update("UPDATE merch_supplier_budget_plans SET source_system='MERCH_CRM',source_origin='https://crm.inkar.kz',source_payload='{\"private\":\"private-raw-marker\"}',shelf_selection=?::jsonb,request_ids=ARRAY(SELECT value::uuid FROM jsonb_array_elements_text(?::jsonb)),submitted_revision=NULL WHERE id=?",selection.toString(),json.writeValueAsString(references),id)
        val original=jdbc.queryForMap("SELECT source_payload::text,shelf_selection::text,request_ids::text FROM merch_supplier_budget_plans WHERE id=?",id)
        val read=tree(mvc.perform(get("/api/producer/commercial/plans/$id").header("Authorization",producerToken)))
        assertThat(read["requestIds"].map {it.asText()}).containsExactlyElementsOf(references.map {it.toString()})
        assertThat(read["monthlyRequests"].map {it["id"].asText()}).containsExactlyElementsOf(known.map {it.toString()})
        assertThat(read["unresolvedRequestIds"].map {it.asText()}).containsExactlyElementsOf((absent+wrong).map {it.toString()})
        assertThat(read["plan"]["shelfSelection"][0]["products"][0]["activeMonths"].map {it.asText()}).containsExactly(month.toString(),month.plusMonths(1).toString(),month.plusMonths(2).toString())
        assertThat(read.toString()).doesNotContain("Other synthetic company","private-raw-marker")
        tree(mvc.perform(get("/api/producer/commercial/workspace").header("Authorization",producerToken)))
        write("/api/producer/commercial/plans/$id/submit",SubmitCommercialPlan(read["revision"].asLong()),producerToken).andExpect(status().isConflict)
        val workbook=mvc.perform(get("/api/producer/commercial/plans/$id/media-kit.xlsx").header("Authorization",producerToken)).andExpect(status().isOk).andReturn().response
        XSSFWorkbook(ByteArrayInputStream(workbook.contentAsByteArray)).use {book->
            assertThat(book.getSheet("Неразрешенные заявки").lastRowNum).isEqualTo(18)
            assertThat(book.getSheet("Заявки и решения").lastRowNum).isEqualTo(3)
            assertThat(book.getSheet("Бюджет по месяцам").getRow(1).getCell(2).cellType).isEqualTo(CellType.BLANK)
            assertThat(book.getSheet("Бюджет по месяцам").getRow(1).getCell(1).numericCellValue).isEqualTo(21000.0)
        }
        val copy=tree(write("/api/producer/commercial/plans/$id/copy",CopyCommercialPlan(read["revision"].asLong(),UUID.randomUUID(),month.plusMonths(4)),producerToken))
        assertThat(copy["plan"]["shelfSelection"][0]["products"][0]["activeMonths"][0].asText()).isEqualTo(month.plusMonths(4).toString())
        assertThat(jdbc.queryForMap("SELECT source_payload::text,shelf_selection::text,request_ids::text FROM merch_supplier_budget_plans WHERE id=?",id)).isEqualTo(original)
        assertThat(jdbc.queryForObject("SELECT producer_id FROM merch_media_plans WHERE id=?",UUID::class.java,UUID.fromString(unbound["id"].asText()))).isNull()
        grants(false)
        val scoped=tree(mvc.perform(get("/api/admin/merch/commercial/plans").header("Authorization",adminToken)))
        assertThat(scoped["items"].none {it["id"].asText()==id.toString()}).isTrue()
        mvc.perform(get("/api/admin/merch/commercial/plans/$id").header("Authorization",adminToken)).andExpect(status().isForbidden)
        mvc.perform(get("/api/admin/merch/commercial/plans/$id/media-kit.xlsx").header("Authorization",adminToken)).andExpect(status().isForbidden)
        grants()
        tree(mvc.perform(get("/api/admin/merch/commercial/plans/$id").header("Authorization",adminToken)))
    }
    @Test fun `real media kit contains current authorized photo all source fields calculations and formula text without private paths`() {
        val value=data(false).copy(notes="=HYPERLINK(\"https://example.test\",\"synthetic\")")
        val plan=save(value=value);val id=plan["id"].asText()
        val bytes=ByteArrayOutputStream().use {output->ImageIO.write(BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",output);output.toByteArray()}
        fun photo(bytes:ByteArray):UUID {
            val photo=UUID.randomUUID();val key="inventory/$branch/$photo";val sha=files.write(key,bytes)
            jdbc.update("INSERT INTO merch_inventory_media(id,branch_id,shelf_id,angle,caption,storage_backend,object_key,mime_type,byte_size,sha256,created_by) VALUES(?,?,?,'shelf','Synthetic image','private_host',?,'image/png',?,?,?)",photo,branch,shelf,key,bytes.size,sha,admin.id)
            return photo
        }
        val first=photo(bytes)
        val response=mvc.perform(get("/api/producer/commercial/plans/$id/media-kit.xlsx").header("Authorization",producerToken)).andExpect(status().isOk).andExpect(header().string("Cache-Control","no-store")).andReturn().response
        XSSFWorkbook(ByteArrayInputStream(response.contentAsByteArray)).use {book->
            assertThat(book.getSheet("Места").lastRowNum).isEqualTo(1);assertThat(book.allPictures).hasSize(1)
            assertThat(book.getSheet("Бюджет по месяцам").getRow(1).getCell(1).numericCellValue).isEqualTo(21000.0)
            val cells=book.flatMap {sheet->sheet.flatMap {row->row.toList()}}
            assertThat(cells.filter {it.cellType==CellType.STRING}.map {it.stringCellValue}).contains(value.notes)
            assertThat(cells.filter {it.cellType==CellType.STRING}.map {it.stringCellValue}.joinToString("\n")).doesNotContain(root.toString(),"inventory/$branch/","source_payload","member@example.test")
            assertThat(cells.none {it.cellType==CellType.FORMULA && it.cellFormula.contains("HYPERLINK")}).isTrue()
            assertThat(book.isSheetHidden(book.getSheetIndex("Расчет"))).isTrue()
        }
        jdbc.update("UPDATE merch_inventory_media SET archived_at=now() WHERE id=?",first)
        val oversized=bytes.clone();ByteBuffer.wrap(oversized).putInt(16,100000).putInt(20,100000)
        val crc=CRC32().apply {update(oversized,12,17)};ByteBuffer.wrap(oversized).putInt(29,crc.value.toInt())
        photo(oversized)
        mvc.perform(get("/api/producer/commercial/plans/$id/media-kit.xlsx").header("Authorization",producerToken)).andExpect(status().isPayloadTooLarge)
        mvc.perform(get("/api/producer/commercial/plans/$id/media-kit.xlsx").header("Authorization",otherToken)).andExpect(status().isForbidden)
        assertThat(count("merch_inventory_media")).isEqualTo(2)
    }
}
