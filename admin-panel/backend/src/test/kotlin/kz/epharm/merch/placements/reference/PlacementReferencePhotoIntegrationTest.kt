package kz.epharm.merch.placements.reference

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
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
class PlacementReferencePhotoIntegrationTest {
    companion object {
        @Container @JvmStatic val postgres=PostgreSQLContainer("postgres:16-alpine").withDatabaseName("reference_disposable")
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
    private fun count(table:String)=jdbc.queryForObject("SELECT count(*) FROM $table",Long::class.java)!!
    private fun pending():JsonNode {
        val created=tree(write("/api/admin/merch/placements",CreatePlacementDraft(producer,month,listOf(PlacementSelection(shelf)))))
        return tree(write("/api/admin/merch/placements/${created["id"].asText()}/submit",PlacementTransition(created["revision"].asLong())))
    }
    private fun png()=ByteArrayOutputStream().use {out->ImageIO.write(BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",out);out.toByteArray()}
    private fun path(plan:JsonNode,admin:Boolean=false,item:String=plan["items"][0]["id"].asText())="${if(admin) "/api/admin/merch" else "/api/producer"}/placements/${plan["id"].asText()}/items/$item/reference-photo"
    private fun upload(plan:JsonNode,bytes:ByteArray=png(),revision:Long=plan["revision"].asLong(),id:UUID=UUID.randomUUID(),token:String=producerToken,admin:Boolean=false,name:String="synthetic.png",declaredMime:String="image/png"):ResultActions =
        mvc.perform(multipart(path(plan,admin)).file(MockMultipartFile("file",name,declaredMime,bytes)).param("expectedRevision",revision.toString()).param("requestId",id.toString()).header("Authorization",token))
    private fun webp(name:String)=javaClass.getResourceAsStream("/placement-reference/$name.webp")!!.use {it.readBytes()}

    @Test fun `pending owner uploads a private typed reference exact retry is once and creator is not an invented administrator`() {
        assertThat(count("merch_placement_reference_photos")).isZero()
        val plan=pending();val id=UUID.randomUUID();val bytes=png()
        val uploaded=tree(upload(plan,bytes,id=id))
        assertThat(uploaded["photo"]["id"].asText()).isEqualTo(id.toString());assertThat(uploaded["revision"].asLong()).isEqualTo(plan["revision"].asLong()+1)
        assertThat(tree(upload(plan,bytes,id=id))["revision"]).isEqualTo(uploaded["revision"])
        assertThat(count("merch_placement_reference_photos")).isEqualTo(1)
        val row=jdbc.queryForMap("SELECT created_by_admin,created_by_producer FROM merch_placement_reference_photos WHERE id=?",id)
        assertThat(row["created_by_admin"]).isNull();assertThat(row["created_by_producer"]).isEqualTo(producer)
        assertThat(uploaded.toString()).doesNotContain("object_key","placement-reference/","created_by","source_payload",root.toString())
        mvc.perform(get("${path(plan)}/$id/file").header("Authorization",producerToken)).andExpect(status().isOk).andExpect(header().string("Cache-Control","no-store")).andExpect(header().string("X-Content-Type-Options","nosniff")).andExpect(content().bytes(bytes))
        mvc.perform(get(path(plan,true)).header("Authorization",adminToken)).andExpect(status().isOk)
        upload(plan,png()+byteArrayOf(0),id=id).andExpect(status().isConflict)
    }
    @Test fun `replace and archive preserve every binary and approved references cannot be edited`() {
        val plan=pending();val firstId=UUID.randomUUID();val first=tree(upload(plan,id=firstId));val secondId=UUID.randomUUID()
        val replaced=tree(upload(plan,revision=first["revision"].asLong(),id=secondId))
        assertThat(replaced["history"].size()).isEqualTo(2)
        assertThat(replaced["history"].count {it["archivedAt"].isTextual}).isEqualTo(1)
        val item=plan["items"][0]["id"].asText()
        assertThat(Files.exists(root.resolve("placement-reference/$item/$firstId"))).isTrue()
        mvc.perform(get("${path(plan)}/$firstId/file").header("Authorization",producerToken)).andExpect(status().isOk)
        assertThatThrownBy {jdbc.update("DELETE FROM merch_placement_reference_photos WHERE id=?",firstId)}.isInstanceOf(org.springframework.dao.DataAccessException::class.java)
        assertThatThrownBy {jdbc.update("UPDATE merch_placement_reference_photos SET sha256=repeat('a',64) WHERE id=?",firstId)}.isInstanceOf(org.springframework.dao.DataAccessException::class.java)
        val archived=tree(write("${path(plan)}/archive",ArchiveReferencePhoto(replaced["revision"].asLong(),secondId),producerToken))
        assertThat(archived["photo"].isNull).isTrue()
        tree(write("${path(plan)}/archive",ArchiveReferencePhoto(replaced["revision"].asLong(),secondId),producerToken))
        assertThat(Files.exists(root.resolve("placement-reference/$item/$secondId"))).isTrue()
        upload(plan,revision=replaced["revision"].asLong()).andExpect(status().isConflict)
        val latest=tree(upload(plan,revision=archived["revision"].asLong()))
        tree(write("/api/admin/merch/placements/${plan["id"].asText()}/approve",PlacementTransition(latest["revision"].asLong())))
        val locked=tree(mvc.perform(get(path(plan)).header("Authorization",producerToken)))
        assertThat(locked["canManage"].asBoolean()).isFalse()
        upload(plan,revision=locked["revision"].asLong()).andExpect(status().isConflict)
        write("${path(plan)}/archive",ArchiveReferencePhoto(locked["revision"].asLong(),UUID.fromString(latest["photo"]["id"].asText())),producerToken).andExpect(status().isConflict)
        assertThat(count("merch_placement_reference_photos")).isEqualTo(3)
    }
    @Test fun `foreign principals neighbor item IDs grant revocation and revoked producer sessions do not expose or create references`() {
        val plan=pending();val photo=tree(upload(plan));val id=photo["photo"]["id"].asText()
        mvc.perform(get(path(plan)).header("Authorization",otherToken)).andExpect(status().isForbidden)
        mvc.perform(get("${path(plan)}/$id/file").header("Authorization",otherToken)).andExpect(status().isForbidden)
        upload(plan,token=otherToken).andExpect(status().isForbidden)
        mvc.perform(get("${path(plan,item=UUID.randomUUID().toString())}/$id/file").header("Authorization",producerToken)).andExpect(status().isNotFound)
        grants(allowed=listOf("view"))
        upload(plan,revision=photo["revision"].asLong(),token=adminToken,admin=true).andExpect(status().isForbidden)
        mvc.perform(get(path(plan,true)).header("Authorization",adminToken)).andExpect(status().isOk)
        grants(allowed=emptyList())
        mvc.perform(get("${path(plan,true)}/$id/file").header("Authorization",adminToken)).andExpect(status().isForbidden)
        jdbc.update("UPDATE merch_producer_sessions SET revoked_at=now() WHERE customer_id=?",producer)
        upload(plan,revision=photo["revision"].asLong()).andExpect(status().is4xxClientError)
        assertThat(count("merch_placement_reference_photos")).isEqualTo(1)
    }
    @Test fun `invalid images oversized files and tiny compressed header bombs never create rows or decode unbounded pixels`() {
        val plan=pending()
        upload(plan,"not an image".toByteArray()).andExpect(status().isBadRequest)
        upload(plan,png().copyOf(24)).andExpect(status().isBadRequest)
        upload(plan,ByteArray(8*1024*1024+1)).andExpect(status().isPayloadTooLarge)
        val bomb=png();ByteBuffer.wrap(bomb).putInt(16,100000).putInt(20,100000)
        val crc=CRC32().apply {update(bomb,12,17)};ByteBuffer.wrap(bomb).putInt(29,crc.value.toInt())
        upload(plan,bomb).andExpect(status().isPayloadTooLarge)
        assertThat(count("merch_placement_reference_photos")).isZero()
    }
    @Test fun `explicit administrator edit preserves the actual uploader and requires the entire plan branch scope`() {
        val plan=pending();grants(false)
        val uploaded=tree(upload(plan,token=adminToken,admin=true))
        val row=jdbc.queryForMap("SELECT created_by_admin,created_by_producer FROM merch_placement_reference_photos WHERE id=?",UUID.fromString(uploaded["photo"]["id"].asText()))
        assertThat(row["created_by_admin"]).isEqualTo(admin.id);assertThat(row["created_by_producer"]).isNull()
        grants()
        val mixed=tree(write("/api/admin/merch/placements",CreatePlacementDraft(producer,month.plusMonths(1),listOf(PlacementSelection(secondShelf),PlacementSelection(foreignShelf)))))
        val submitted=tree(write("/api/admin/merch/placements/${mixed["id"].asText()}/submit",PlacementTransition(mixed["revision"].asLong())))
        grants(false)
        mvc.perform(get(path(submitted,true)).header("Authorization",adminToken)).andExpect(status().isForbidden)
        upload(submitted,token=adminToken,admin=true).andExpect(status().isForbidden)
    }
    @Test fun `one admission slot protects all uploads and stale parallel intent cannot replace the winning photo`() {
        val plan=pending();val entered=CountDownLatch(1);val release=CountDownLatch(1);val pool=Executors.newSingleThreadExecutor()
        val blocking=object:MockMultipartFile("file","synthetic.png","image/png",png()) {
            override fun getBytes():ByteArray {entered.countDown();check(release.await(10,TimeUnit.SECONDS));return super.getBytes()}
        }
        try {
            val winner=pool.submit<Int> {mvc.perform(multipart(path(plan)).file(blocking).param("expectedRevision",plan["revision"].asText()).param("requestId",UUID.randomUUID().toString()).header("Authorization",producerToken)).andReturn().response.status}
            assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue()
            upload(plan).andExpect(status().isTooManyRequests)
            release.countDown();assertThat(winner.get(10,TimeUnit.SECONDS)).isEqualTo(200)
            upload(plan).andExpect(status().isConflict)
            assertThat(count("merch_placement_reference_photos")).isEqualTo(1)
        } finally {release.countDown();pool.shutdownNow()}
    }
    @Test fun `protected old-version download verifies the stored hash and never changes payment or rewards`() {
        val plan=pending();val uploaded=tree(upload(plan));val id=uploaded["photo"]["id"].asText();val item=plan["items"][0]["id"].asText()
        Files.write(root.resolve("placement-reference/$item/$id"),byteArrayOf(1,2,3))
        mvc.perform(get("${path(plan)}/$id/file").header("Authorization",producerToken)).andExpect(status().isConflict)
        assertThat(count("merch_task_rewards")).isZero()
        assertThat(jdbc.queryForObject("SELECT total_amount FROM merch_media_plans WHERE id=?",Long::class.java,UUID.fromString(plan["id"].asText()))).isEqualTo(plan["totalAmount"].asLong())
    }
    @Test fun `real lossy and lossless WebP decode through the plugin and retain exact private bytes hash MIME and extension`() {
        val plugin=ImageIO.getImageReadersByFormatName("WebP").asSequence().first()
        try {assertThat(plugin.javaClass.name).contains("twelvemonkeys")} finally {plugin.dispose()}
        val plan=pending();var revision=plan["revision"].asLong()
        for(name in listOf("lossy","lossless")) {
            val bytes=webp(name);val id=UUID.randomUUID()
            // Client MIME is deliberately wrong: the installed reader identifies the actual bytes.
            val state=tree(upload(plan,bytes,revision,id,name="synthetic-$name.webp",declaredMime="image/jpeg"))
            revision=state["revision"].asLong()
            assertThat(state["photo"]["mimeType"].asText()).isEqualTo("image/webp")
            assertThat(state["photo"]["byteSize"].asLong()).isEqualTo(bytes.size.toLong())
            val expected=TaskEvidenceStorage.hash(bytes)
            assertThat(jdbc.queryForObject("SELECT sha256 FROM merch_placement_reference_photos WHERE id=?",String::class.java,id)).isEqualTo(expected)
            val item=plan["items"][0]["id"].asText()
            assertThat(Files.readAllBytes(root.resolve("placement-reference/$item/$id"))).isEqualTo(bytes)
            mvc.perform(get("${path(plan)}/$id/file").header("Authorization",producerToken)).andExpect(status().isOk)
                .andExpect(content().contentType("image/webp")).andExpect(header().string("Content-Disposition","inline; filename=\"reference-photo.webp\""))
                .andExpect(content().bytes(bytes))
            val decoded=ImageIO.read(ByteArrayInputStream(bytes))
            try {assertThat(decoded.width).isEqualTo(16);assertThat(decoded.height).isEqualTo(12)
                if(name=="lossless") assertThat(decoded.getRGB(5,4) and 0xffffff).isEqualTo((85 shl 16) or (92 shl 8) or 83)
            } finally {decoded.flush()}
        }
        assertThat(count("merch_placement_reference_photos")).isEqualTo(2)
    }
    @Test fun `WebP alpha remains present and original encoding is never replaced by a JPEG or PNG transcode`() {
        val bytes=webp("alpha");val plan=pending();val state=tree(upload(plan,bytes,name="synthetic-alpha.webp",declaredMime="image/webp"))
        val id=UUID.fromString(state["photo"]["id"].asText())
        val decoded=ImageIO.read(ByteArrayInputStream(bytes))
        try {assertThat(decoded.colorModel.hasAlpha()).isTrue();assertThat(decoded.getRGB(0,0) ushr 24).isZero();assertThat(decoded.getRGB(15,0) ushr 24).isEqualTo(255)} finally {decoded.flush()}
        mvc.perform(get("${path(plan)}/$id/file").header("Authorization",adminToken)).andExpect(status().is4xxClientError)
        mvc.perform(get("${path(plan,true)}/$id/file").header("Authorization",adminToken)).andExpect(status().isOk).andExpect(content().contentType("image/webp")).andExpect(content().bytes(bytes))
        assertThat(jdbc.queryForObject("SELECT sha256 FROM merch_placement_reference_photos WHERE id=?",String::class.java,id)).isEqualTo(TaskEvidenceStorage.hash(bytes))
    }
    @Test fun `truncated corrupt and huge WebP headers fail before saving metadata or decoding an unbounded bitmap`() {
        val plan=pending()
        upload(plan,webp("lossless").copyOf(24),name="truncated.webp").andExpect(status().isBadRequest)
        val corrupt=webp("lossy").also {java.util.Arrays.fill(it,20,it.size,0.toByte())}
        upload(plan,corrupt,name="corrupt.webp").andExpect(status().isBadRequest)
        val huge=webp("alpha").also {bytes->for(offset in listOf(24,27)) {bytes[offset]=0xff.toByte();bytes[offset+1]=0x0f;bytes[offset+2]=0}}
        // VP8X canvas 4096×4096 exceeds 12 MP; its tiny encoded payload is intentionally unchanged.
        upload(plan,huge,name="huge.webp").andExpect(status().isPayloadTooLarge)
        assertThat(count("merch_placement_reference_photos")).isZero()
    }
}
