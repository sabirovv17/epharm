package kz.epharm.merchtasks

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import kz.epharm.catalog.entity.ProductEntity
import kz.epharm.catalog.repository.ProductRepository
import kz.epharm.merchtasks.dto.*
import kz.epharm.merchtasks.service.MerchTaskClient
import kz.epharm.merchtasks.service.NativeRecommendationContextReader
import kz.epharm.posm.dto.CartItemDto
import kz.epharm.posm.dto.RecommendRequest
import kz.epharm.posm.service.*
import kz.epharm.rules.entity.RuleEntity
import kz.epharm.rules.entity.RuleTrigger
import kz.epharm.rules.repository.RuleRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

@SpringBootTest(properties=["app.merch.legacy-portal-cutover-enabled=true","app.merch-tasks.enabled=true"])
@ActiveProfiles("test")
@Testcontainers
class NativeRecommendationContextIntegrationTest {
    companion object {
        @Container @JvmStatic val postgres=PostgreSQLContainer("postgres:16-alpine").withDatabaseName("native_context")
            .withUsername("epharm").withPassword("synthetic-only").apply { start() }
        @JvmStatic @DynamicPropertySource fun props(reg:DynamicPropertyRegistry) {
            reg.add("spring.datasource.url"){postgres.jdbcUrl};reg.add("spring.datasource.username"){postgres.username};reg.add("spring.datasource.password"){postgres.password}
        }
    }
    @Autowired lateinit var jdbc:JdbcTemplate
    @Autowired lateinit var reader:NativeRecommendationContextReader
    @Autowired lateinit var client:MerchTaskClient
    @Autowired lateinit var transactions:PlatformTransactionManager
    @Autowired lateinit var recommendations:RecommendationService
    @Autowired lateinit var products:ProductRepository
    @Autowired lateinit var rules:RuleRepository
    @MockkBean lateinit var engine:RulesEngineService
    private val org=UUID.fromString("10000000-0000-4000-8000-000000000001")
    private val otherOrg=UUID.fromString("10000000-0000-4000-8000-000000000002")
    private val branch=UUID.fromString("20000000-0000-4000-8000-000000000001")
    private val foreign=UUID.fromString("20000000-0000-4000-8000-000000000002")
    private fun request(cart:List<MerchRecommendationCartItem> = listOf(MerchRecommendationCartItem("ip-1","4871234567890","Synthetic",1.0)),
                        refs:List<MerchRecommendationProductRef> = emptyList()) = MerchRecommendationContextRequest("ctx-pharmacy",cart,refs)

    @BeforeEach fun seed() {
        jdbc.execute("TRUNCATE merch_recommendation_signals,merch_product_balances,merch_branches,merch_organizations,pharmacies,chains,products,rules,recommendation_events CASCADE")
        jdbc.update("""INSERT INTO chains(id,name,color,"group",points) VALUES('ctx-chain','Synthetic','#000','pilot',0)""")
        jdbc.update("""INSERT INTO pharmacies(id,name,chain_id,chain_name,city,district,addr,"group") VALUES('ctx-pharmacy','Synthetic','ctx-chain','Synthetic','Test','','','pilot')""")
        for(o in listOf(org,otherOrg)) jdbc.update("""INSERT INTO merch_organizations(id,source_system,source_origin,source_id,source_payload,name,legal_name,active,created_at,updated_at)
            VALUES(?,'EPHARM','https://epharm.inkar.kz',?,'{}','Synthetic','Synthetic',true,now(),now())""",o,o)
        addBranch(branch,org,"ctx-pharmacy");addBranch(foreign,otherOrg,null)
        every { engine.match(any(),any()) } returns RuleMatchResult(emptyList(),emptyList())
    }
    private fun addBranch(id:UUID,organization:UUID,pharmacy:String?) {
        jdbc.update("""INSERT INTO merch_branches(id,source_system,source_origin,source_id,source_payload,organization_id,epharm_pharmacy_id,pharmacy_binding_verified,code,name,address,city,active,created_at,updated_at)
            VALUES(?,'EPHARM','https://epharm.inkar.kz',?,'{}',?,?,?,?,'Synthetic','','Test',true,now(),now())""",id,id,organization,pharmacy,pharmacy!=null,id.toString())
    }
    private fun signal(n:Int,scope:UUID?=branch,organization:UUID=org,priority:Int=0,offer:String="offer",kind:String="substitution"):UUID {
        val id=UUID.fromString("30000000-0000-4000-8000-"+n.toString().padStart(12,'0'))
        jdbc.update("""INSERT INTO merch_recommendation_signals(id,source_system,source_origin,source_id,source_payload,organization_id,branch_id,kind,trigger_barcode,recommend_product_id,script,advantages,bonus,priority)
            VALUES(?,'EPHARM','https://epharm.inkar.kz',?,'{}',?,?,?,'4871234567890',?,'Exact source script','["first"]',123,?)""",id,id,organization,scope,kind,offer,priority)
        return id
    }
    @Test fun `verified empty feed is healthy and unknown pharmacy stays unmapped without fabricated stock`() {
        val value=client.recommendationContext(request())
        assertThat(value.available).isTrue();assertThat(value.pharmacyMapped).isTrue();assertThat(value.generatedAt).isNotNull()
        assertThat(value.balances).isEmpty();assertThat(value.recommendations).isEmpty()
        assertThat(reader.context(request().copy(pharmacyId="unknown")).pharmacyMapped).isFalse()
    }
    @Test fun `branch overrides global duplicate before priority while foreign or unbound scopes never leak`() {
        signal(1,scope=null,priority=999);val own=signal(2,priority=-5)
        signal(3,scope=foreign,organization=otherOrg,priority=1000,offer="foreign")
        val value=client.recommendationContext(request())
        assertThat(value.recommendations.map{it.id}).containsExactly(own.toString())
        jdbc.update("UPDATE merch_branches SET active=false WHERE id=?",branch)
        assertThat(reader.context(request()).pharmacyMapped).isFalse()
        jdbc.update("UPDATE merch_branches SET active=true WHERE id=?",branch)
        addBranch(UUID.randomUUID(),org,"ctx-pharmacy")
        assertThat(reader.context(request()).pharmacyMapped).isFalse()
    }
    @Test fun `only exact barcode or ipart triggers and safe trimmed identifiers are used`() {
        val id=signal(1)
        assertThat(client.recommendationContext(request(listOf(MerchRecommendationCartItem(name="Synthetic")))).recommendations).isEmpty()
        assertThat(client.recommendationContext(request(listOf(MerchRecommendationCartItem(barcode=" 4871234567890 ")))).recommendations.map{it.id}).containsExactly(id.toString())
        jdbc.update("UPDATE merch_recommendation_signals SET trigger_barcode=NULL,trigger_ipart_id='ip-1' WHERE id=?",id)
        assertThat(client.recommendationContext(request()).recommendations).hasSize(1)
        assertThat(client.recommendationContext(request(listOf(MerchRecommendationCartItem(sku="ip-2",name="Synthetic")))).recommendations).isEmpty()
    }
    @Test fun `window boundaries are inclusive start exclusive end and inactive rows remain historical`() {
        val a=signal(1,offer="start");val b=signal(2,offer="end");val c=signal(3,offer="inactive")
        TransactionTemplate(transactions).execute {
            jdbc.update("UPDATE merch_recommendation_signals SET valid_from=now() WHERE id=?",a)
            jdbc.update("UPDATE merch_recommendation_signals SET valid_until=now() WHERE id=?",b)
            jdbc.update("UPDATE merch_recommendation_signals SET active=false WHERE id=?",c)
            assertThat(client.recommendationContext(request()).recommendations.map{it.recommendProductId}).containsExactly("start")
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merch_recommendation_signals",Int::class.java)).isEqualTo(3)
    }
    @Test fun `stable priority uuid ordering caps offers without combining rule types`() {
        for(n in 1..102) signal(n,offer="offer-$n",priority=n%2,kind=if(n==1) "crosssell" else "substitution")
        val value=client.recommendationContext(request())
        assertThat(value.recommendations).hasSize(100)
        assertThat(value.recommendations.take(51).map{it.priority}).containsOnly(1)
        assertThat(value.recommendations.take(3).map{it.recommendProductId}).containsExactly("offer-1","offer-3","offer-5")
        assertThat(value.recommendations.map{it.kind}).contains("crosssell","substitution")
    }
    @Test fun `authoritative quantities retain null decimal zero status and requested exact keys without task inference`() {
        signal(1)
        for(n in 1..4) jdbc.update("""INSERT INTO merch_product_balances(id,source_system,source_origin,source_id,source_payload,organization_id,branch_id,external_key,product_id,pharmacy_available_qty,network_available_qty,quality,source,observed_at)
            VALUES(?,'EPHARM','https://epharm.inkar.kz',?,'{}',?,?,?, ?,?,NULL,?,'explicit-synthetic',now()-(? * interval '1 second'))""",
            n.toLong(),n.toLong(),if(n==4)otherOrg else org,if(n==4)foreign else branch,"key-$n",if(n==1)"offer" else "requested",if(n==2)null else java.math.BigDecimal("0.000"),if(n==3)"estimated" else "confirmed",if(n==1)1801 else 0)
        val value=client.recommendationContext(request(refs=listOf(MerchRecommendationProductRef(productId="requested"))))
        assertThat(value.balances).hasSize(3)
        assertThat(value.balances[0].status).isEqualTo("stale");assertThat(value.balances[0].networkAvailableQty).isNull()
        assertThat(value.balances[1].pharmacyAvailableQty).isNull();assertThat(value.balances[2].status).isEqualTo("estimated")
        assertThat(value.balances[2].pharmacyAvailableQty).isEqualByComparingTo("0.000")
    }
    @Test fun `bounded JDBC timeout cancels a blocked optional read without leaking timeout or aborting outer writes`() {
        java.sql.DriverManager.getConnection(postgres.jdbcUrl,postgres.username,postgres.password).use { blocker ->
            blocker.autoCommit=false
            blocker.createStatement().use { it.execute("LOCK TABLE merch_recommendation_signals IN ACCESS EXCLUSIVE MODE") }
            try {
                TransactionTemplate(transactions).execute {
                    val timeout=jdbc.queryForObject("SHOW statement_timeout",String::class.java)
                    val started=System.nanoTime()
                    assertThat(client.recommendationContext(request()).available).isFalse()
                    assertThat(java.time.Duration.ofNanos(System.nanoTime()-started).toMillis()).isBetween(500L,3500L)
                    assertThat(jdbc.queryForObject("SHOW statement_timeout",String::class.java)).isEqualTo(timeout)
                    jdbc.update("UPDATE merch_branches SET city='after-query-timeout' WHERE id=?",branch)
                }
            } finally { blocker.rollback() }
        }
        assertThat(jdbc.queryForObject("SELECT city FROM merch_branches WHERE id=?",String::class.java,branch)).isEqualTo("after-query-timeout")
    }

    @Test fun `failed optional SQL rolls back savepoint and actual Recommend outer transaction still commits its event`() {
        val offer=products.saveAndFlush(ProductEntity(id="ctx-offer",name="Synthetic offer",price=2000))
        val rule=rules.saveAndFlush(RuleEntity(id="ctx-rule",statusRaw="active",recommend=offer.id,trigger=RuleTrigger("product","ctx-trigger"),bonus=123))
        every { engine.match(any(),any()) } returns RuleMatchResult(listOf(RuleMatch(rule,"ctx-trigger","Synthetic",null,null,"4871234567890",offer)),emptyList())
        val tx=TransactionTemplate(transactions)
        tx.execute {
            val pid=jdbc.queryForObject("SELECT pg_backend_pid()",Int::class.java)
            val timeout=jdbc.queryForObject("SHOW statement_timeout",String::class.java)
            jdbc.execute("ALTER TABLE merch_recommendation_signals RENAME TO context_hidden_for_failure")
            try {
                val value=recommendations.recommend(RecommendRequest(pharmacyId="ctx-pharmacy",sessionId="ctx-error-session",cart=listOf(CartItemDto(barcode="4871234567890"))))
                assertThat(value.recommendations.map{it.ruleId}).containsExactly("ctx-rule")
                assertThat(jdbc.queryForObject("SELECT pg_backend_pid()",Int::class.java)).isEqualTo(pid)
                assertThat(jdbc.queryForObject("SHOW statement_timeout",String::class.java)).isEqualTo(timeout)
                jdbc.update("UPDATE merch_branches SET city='after-optional-error' WHERE id=?",branch)
            } finally { jdbc.execute("ALTER TABLE context_hidden_for_failure RENAME TO merch_recommendation_signals") }
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM recommendation_events WHERE session_id='ctx-error-session'",Int::class.java)).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT city FROM merch_branches WHERE id=?",String::class.java,branch)).isEqualTo("after-optional-error")
    }
}
