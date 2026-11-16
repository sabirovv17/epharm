package kz.epharm.posm

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kz.epharm.auth.access.StoredGrant
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.entity.AdminUserEntity
import kz.epharm.auth.repository.AdminUserRepository
import kz.epharm.auth.service.JwtService
import kz.epharm.catalog.entity.ProductEntity
import kz.epharm.catalog.repository.ProductRepository
import kz.epharm.pharmacies.entity.ChainEntity
import kz.epharm.pharmacies.entity.PharmacyEntity
import kz.epharm.pharmacies.repository.ChainRepository
import kz.epharm.pharmacies.repository.PharmacyRepository
import kz.epharm.pharmacists.entity.PharmacistEntity
import kz.epharm.pharmacists.entity.PharmacistStatus
import kz.epharm.pharmacists.repository.PharmacistRepository
import kz.epharm.posm.dto.*
import kz.epharm.posm.repository.PosSaleRepository
import kz.epharm.posm.repository.RecommendationEventRepository
import kz.epharm.reporting.CampaignReportRepository
import kz.epharm.posm.service.RewardReviewDecision
import kz.epharm.promo.entity.PromoEntity
import kz.epharm.promo.entity.PromoStatus
import kz.epharm.promo.entity.PromoTier
import kz.epharm.promo.repository.PromoRepository
import kz.epharm.rules.entity.RuleEntity
import kz.epharm.rules.entity.RuleStatus
import kz.epharm.rules.entity.RuleTrigger
import kz.epharm.rules.repository.RuleRepository
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.assertj.core.api.Assertions.assertThat
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/** Actual device-authenticated HTTP flow. Only catalogue, funding and identities are fixtures;
 * reward cases, pending bonuses, receipts and wallet entries are produced by application endpoints. */
@SpringBootTest(properties = [
    "app.posm.sale-bonus.enabled=true", "app.posm.sale-bonus.auto-credit-enabled=true",
    "app.posm.sale-bonus.device-allowlist=*", "app.posm.staff-auth-enabled=true",
    "app.posm.sale-bonus.eligible-after=2026-01-01T00:00:00Z", "app.posm.legacy-device-key-enabled=false",
])
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class RecommendationRewardFlowIntegrationTest {
    companion object {
        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("reward_http_flow_test").withUsername("epharm").withPassword("test-only").apply { start() }
        private val sequence = AtomicLong(860_000_000_000)
        private val documents = AtomicLong(20_000)
        @JvmStatic @DynamicPropertySource fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
        }
    }
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var admins: AdminUserRepository
    @Autowired lateinit var jwt: JwtService
    @Autowired lateinit var chains: ChainRepository
    @Autowired lateinit var pharmacies: PharmacyRepository
    @Autowired lateinit var people: PharmacistRepository
    @Autowired lateinit var products: ProductRepository
    @Autowired lateinit var promos: PromoRepository
    @Autowired lateinit var rules: RuleRepository
    @Autowired lateinit var sales: PosSaleRepository
    @Autowired lateinit var recommendationEvents: RecommendationEventRepository
    @Autowired lateinit var sourceBindings: kz.epharm.posm.staff.SourceStaffBindingService
    @Autowired lateinit var transactions: org.springframework.transaction.PlatformTransactionManager
    @Autowired lateinit var encoder: org.springframework.security.crypto.password.PasswordEncoder
    @Autowired lateinit var rewardService: kz.epharm.posm.service.RecommendationRewardService
    @Autowired lateinit var campaignReports: CampaignReportRepository
    private var bearer: String? = null

    @Test fun `actual recommend shown and paid two package sale credit the shared wallet without fake Excel`() {
        val f = flow()
        acknowledge(f)
        deliver(f, payment(f))
        assertCredit(f, 400)
        assertThat(balance(f.employee)).isEqualTo(400)
        assertThat(number("SELECT earned_30d FROM pharmacists WHERE id=?", f.employee.person)).isEqualTo(400)
        assertThat(spent(f.world.offers.single())).isEqualTo(400)
        val receipt = rows("""SELECT r.* FROM receipts r JOIN recommendation_sale_rewards w ON w.receipt_id=r.id
            WHERE w.source_sale_id=?""", f.sale).single()
        assertThat(receipt["status"]).isEqualTo("approved")
        assertThat(receipt["auto_approved"]).isEqualTo(true)
        assertThat(receipt["confirmed_by_log"]).isEqualTo(true)
        assertThat(receipt["confirmed_by_excel"]).isEqualTo(false)
        assertThat((receipt["parsed_amount"] as Number).toLong()).isEqualTo(2000)
        assertThat(number("SELECT bonus FROM pending_bonuses WHERE id=(SELECT pending_bonus_id FROM recommendation_sale_rewards WHERE source_sale_id=?)", f.sale)).isEqualTo(200)
        assertThat(number("SELECT count(*) FROM posm_pharmacist_mapping_audit WHERE pharmacist_id=?", f.employee.person)).isEqualTo(1)
        // These are the existing web /learn readers, not a separate mobile application test.
        val owner = people.findById(f.employee.person).orElseThrow()
        val learner = "Bearer ${jwt.issuePharmacistToken(owner.id, owner.name, owner.phone)}"
        val profile = mvc.perform(get("/api/mobile/auth/me").header("Authorization", learner))
            .andExpect(status().isOk).andReturn().response.contentAsByteArray
        assertThat(mapper.readTree(profile)["balance"].asLong()).isEqualTo(400)
        assertThat(mapper.readTree(profile)["earned30d"].asLong()).isEqualTo(400)
        val portalBalance = mvc.perform(get("/api/mobile/me/balance").header("Authorization", learner))
            .andExpect(status().isOk).andReturn().response.contentAsByteArray
        assertThat(mapper.readTree(portalBalance)["balanceKzt"].asLong()).isEqualTo(400)
        assertThat(mapper.readTree(portalBalance)["currency"].asText()).isEqualTo("KZT")
    }

    @Test fun `a confirmed secondary barcode credits its own offer but a foreign product cannot reuse the event`() {
        for (sameProduct in listOf(true, false)) {
            val w = world()
            val offer = w.offers.single()
            val scanned = barcode()
            if (sameProduct) {
                products.findById(offer.id).orElseThrow().also {
                    it.barcodes = listOf(scanned)
                    products.saveAndFlush(it)
                }
            } else {
                products.saveAndFlush(ProductEntity(id = "foreign_${token()}", name = "Synthetic other product",
                    barcode = scanned, price = 1000))
            }
            val f = flow(w.copy(offers = listOf(offer.copy(barcode = scanned))))
            acknowledge(f)
            deliver(f, payment(f))

            val expected = if (sameProduct) 400L else 0L
            assertThat(balance(f.employee)).isEqualTo(expected)
            assertThat(spent(offer)).isEqualTo(expected)
            if (sameProduct) assertCredit(f, expected)
            else {
                assertThat(cases(f)).isEmpty()
                assertThat(number("SELECT count(*) FROM pharmacist_wallet_entries WHERE pharmacist_id=?", f.employee.person)).isZero()
            }
        }
    }

    @Test fun `only the admitted pilot receives capability and money and removal before payment stops auto credit`() {
        val original = org.springframework.test.util.ReflectionTestUtils.getField(rewardService, "deviceScope")!!
        fun admit(device: String) = org.springframework.test.util.ReflectionTestUtils.setField(rewardService,
            "deviceScope", kz.epharm.posm.service.RecommendationRewardDeviceScope(device))
        fun capability(f: Flow) = mapper.readTree(mvc.perform(post("/api/posm/heartbeat")
            .header("X-Posm-Key", f.employee.key).param("deviceId", f.employee.device)
            .param("pharmacyId", f.employee.pharmacy)).andExpect(status().isOk)
            .andReturn().response.contentAsByteArray).path("rewardEvidenceProtocol")
        try {
            val admitted = flow(); val foreign = flow(); val removed = flow()
            admit(admitted.employee.device)
            assertThat(capability(admitted).asInt()).isEqualTo(1)
            acknowledge(admitted); deliver(admitted, payment(admitted))
            assertCredit(admitted, 400)
            assertThat(balance(admitted.employee)).isEqualTo(400)

            assertThat(capability(foreign).asInt(0)).isZero()
            acknowledge(foreign); deliver(foreign, payment(foreign))
            assertThat(balance(foreign.employee)).isZero()
            assertThat(spent(foreign.world.offers.single())).isZero()

            admit(removed.employee.device)
            acknowledge(removed)
            admit("a-different-synthetic-device")
            deliver(removed, payment(removed))
            assertThat(balance(removed.employee)).isZero()
            assertThat(spent(removed.world.offers.single())).isZero()
            assertThat(cases(removed).single()["reason_code"]).isEqualTo("device_not_enabled")
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(rewardService, "deviceScope", original)
        }
    }

    @Test fun `an unrelated fractional package does not deny a whole offered product but an offered fraction never credits`() {
        for (offeredFraction in listOf(false, true)) {
            val f = flow()
            val otherCode = barcode()
            products.saveAndFlush(ProductEntity(id = "fraction_${token()}", name = "Synthetic unrelated fractional product",
                barcode = otherCode, price = 1000))
            val baseline = listOf(
                RewardCartLine(f.doc, 1, barcode = f.world.triggerBarcode, qty = BigDecimal.ONE, unitSemantics = "full_package"),
                RewardCartLine(f.doc, 999, barcode = otherCode, qty = BigDecimal("0.3"), unitSemantics = "unknown"))
            postDevice("/api/posm/recommendations/${f.eventIds.values.single()}/shown", f.employee,
                MarkShownRequest(f.shownAt, RewardShownEvidence(checkoutSessionId = f.session, observationId = "fraction_baseline",
                    identity = f.identity, cartComplete = true, lines = baseline)))
            val paid = payment(f)
            val evidence = paid.rewardEvidence!!
            val count = if (offeredFraction) BigDecimal("0.5") else BigDecimal("2")
            val amount = count.multiply(BigDecimal("1000")).longValueExact()
            val total = amount + 300
            val otherChange = RewardLineChange("unrelated_fraction", "", "unrelated_generation", 1,
                f.shownAt.minusSeconds(10), f.identity, f.doc, 999, barcode = otherCode,
                beforeQty = BigDecimal.ZERO, afterQty = BigDecimal("0.3"), unitSemantics = "unknown")
            val changes = (evidence.changes.map { change ->
                if (change.eventId == f.eventIds.values.single()) change.copy(afterQty = count) else change
            } + otherChange).sortedBy { it.observedAt }.mapIndexed { index, change -> change.copy(sequence = index + 1L) }
            val lines = listOf(evidence.completion.lines.single().copy(qty = count, signedQuantity = count.negate(),
                signedAmount = BigDecimal.valueOf(-amount)),
                RewardPaidLine(f.doc, 999, barcode = otherCode, qty = BigDecimal("0.3"), signedQuantity = BigDecimal("-0.3"),
                    signedPrice = BigDecimal("1000"), signedAmount = BigDecimal("-300"), unitSemantics = "unknown"))
            val request = paid.copy(totalAmount = total,
                items = listOf(paid.items.single().copy(qty = count.toDouble(), total = amount),
                    PosSaleItemDto(barcode = otherCode, name = "Synthetic unrelated fractional product", qty = .3, price = 1000, total = 300)),
                rewardEvidence = evidence.copy(changes = changes, completion = evidence.completion.copy(
                    signedTotal = BigDecimal.valueOf(-total), lines = lines)))

            deliver(f, request)

            val expected = if (offeredFraction) 0L else 400L
            assertCredit(f, expected)
            assertThat(balance(f.employee)).isEqualTo(expected)
            assertThat(spent(f.world.offers.single())).isEqualTo(expected)
        }
    }

    @Test fun `password and exact native IIN bind automatically and actual shown sale credits the same learner wallet`() {
        val login = sourceLogin()
        val f = sourceFlow(login)
        assertThat(number("SELECT count(*) FROM posm_pharmacist_mappings WHERE pharmacy_id=?", f.employee.pharmacy)).isZero()
        assertThat(number("SELECT count(*) FROM posm_source_staff_bindings WHERE pharmacist_id=? AND state='active'", f.employee.person)).isEqualTo(1)
        acknowledge(f); deliver(f,payment(f)); deliver(f,payment(f))
        assertCredit(f,400)
        assertThat(sales.findById(f.sale).orElseThrow().pharmacistSource).isEqualTo("standardn_rps_binding")
        val auth=mvc.perform(post("/api/mobile/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsBytes(mapOf("iin" to login.iin,"password" to "A1!b"))))
            .andExpect(status().isOk).andReturn().response.contentAsByteArray
        val token=mapper.readTree(auth).path("tokens").path("accessToken").asText()
        val body=mvc.perform(get("/api/mobile/me/balance").header("Authorization","Bearer $token"))
            .andExpect(status().isOk).andReturn().response.contentAsByteArray
        assertThat(mapper.readTree(body).path("balanceKzt").asLong()).isEqualTo(400)
    }

    @Test fun `ordinary receipt source resolves a proven RPS seller without reward evidence or credit and survives retry`() {
        val login = sourceLogin()
        val native = login.context.standardNContext!!
        val source = SaleSellerSource(native.sourceNamespace!!, native.standardNUserId,
            native.standardNSessionId.toLong(), native.workstationId)
        val request = PosSaleRequest(saleId = "ordinary_${token()}", pharmacyId = login.employee.pharmacy,
            pharmacistId = login.employee.rawUser, printedAt = Instant.now(), sellerSource = source)
        val response = postDevice("/api/posm/sales", login.employee, request)
        assertThat(response.path("accepted").asBoolean()).isTrue()
        val stored = sales.findById(request.saleId).orElseThrow()
        assertThat(stored.pharmacistId).isEqualTo(login.employee.person)
        assertThat(stored.pharmacistSource).isEqualTo("standardn_rps_binding")
        assertThat(stored.sellerSource).isEqualTo(source)
        assertThat(stored.rewardEvidence).isNull()
        assertThat(stored.reportedPharmacistId).isEqualTo(login.employee.rawUser)
        assertThat(postDevice("/api/posm/sales", login.employee, request).path("accepted").asBoolean()).isFalse()
        assertThat(balance(login.employee)).isZero()
        assertThat(number("SELECT count(*) FROM pending_bonuses WHERE pharmacist_id=?", login.employee.person)).isZero()
        assertThat(number("SELECT count(*) FROM recommendation_sale_rewards WHERE source_sale_id=?", request.saleId)).isZero()
    }

    @Test fun `two full normalized name words bind only when IIN is absent and unique in that pharmacy`() {
        for (status in listOf("missing","unavailable")) {
            val login=sourceLogin(status=status,name="  САБИРОВ   АМИР ")
            val f=sourceFlow(login)
            acknowledge(f);deliver(f,payment(f));assertCredit(f,400)
            assertThat(rows("SELECT proof_method FROM posm_source_staff_bindings WHERE pharmacist_id=?",f.employee.person).single()["proof_method"]).isEqualTo("NAME")
        }
    }

    @Test fun `available mismatching or invalid or ambiguous IIN never falls back to the same name`() {
        for (status in listOf("available","invalid","ambiguous")) {
            val login=sourceLogin(status=status,sourceIin=if(status=="available") "000000000001" else null)
            val f=sourceFlow(login)
            acknowledge(f);deliver(f,payment(f))
            assertThat(balance(f.employee)).isZero()
            assertThat(number("SELECT count(*) FROM posm_source_staff_bindings WHERE pharmacist_id=?",f.employee.person)).isZero()
        }
    }

    @Test fun `duplicate full names and initials remain unbound without a manual HQ decision`() {
        for (mode in listOf("duplicate","initials","generic")) {
            val base=employee(mapped=false)
            if(mode=="duplicate") {
                employee(base,mapped=false).also { jdbc.update("UPDATE pharmacists SET name='Сабиров Амир' WHERE id=?",it.person) }
            }
            val login=sourceLogin(base,status="missing",name=when(mode){"initials"->"Сабиров А.";"generic"->"Кассир Пользователь";else->"Амир Сабиров"})
            val f=sourceFlow(login)
            acknowledge(f);deliver(f,payment(f))
            assertThat(balance(base)).isZero()
            assertThat(number("SELECT count(*) FROM posm_source_staff_bindings WHERE pharmacist_id=?",base.person)).isZero()
        }
    }

    @Test fun `a later live source observation binds the same logged in employee without registering again`() {
        val login=sourceLogin(attachSource=false)
        assertThat(number("SELECT count(*) FROM posm_source_staff_bindings WHERE pharmacist_id=?",login.employee.person)).isZero()
        checkSource(login)
        val f=sourceFlow(login);acknowledge(f);deliver(f,payment(f));assertCredit(f,400)
    }

    @Test fun `source bindings cannot cross device database user session or workstation or bypass actual shown ACK`() {
        for(mode in listOf("namespace","device","user","session","workstation","not-shown")) {
            val login=sourceLogin();val initial=sourceFlow(login)
            val f=initial.copy(identity=when(mode) {
                "namespace"->initial.identity.copy(sourceNamespace=sha("another-database"))
                "user"->initial.identity.copy(rawUserId="different-user")
                "session"->initial.identity.copy(nativeSessionId=777)
                "workstation"->initial.identity.copy(workstationId="other-workstation")
                else->initial.identity
            })
            if(mode!="not-shown") acknowledge(f)
            if(mode=="device") {
                val key="different-native-device-${UUID.randomUUID()}"
                jdbc.update("INSERT INTO fulfillment_devices(id,device_id,pharmacy_id,secret_sha256,secret_hint) VALUES (?,?,?,?,?)",
                    UUID.randomUUID(),"other-device-${token()}",f.employee.pharmacy,sha(key),"test-only")
                deliver(f,payment(f),employee=f.employee.copy(key=key))
            } else deliver(f,payment(f))
            assertThat(balance(f.employee)).isZero()
        }
    }

    @Test fun `source binding uses the grant alive at payment and never a newer login for an old offline sale`() {
        for(mode in listOf("revoked-before","expired-before","revoked-after","late-proof")) {
            val login=sourceLogin();val initial=sourceFlow(login)
            val f=initial.copy(shownAt=Instant.now().minusSeconds(240).truncatedTo(ChronoUnit.MILLIS))
            val paidAt=payment(f).printedAt
            val proofAt=if(mode=="late-proof") paidAt.plusMillis(1) else f.shownAt.minusSeconds(1)
            jdbc.update("UPDATE posm_device_staff_sessions SET created_at=?,source_verified_at=?,expires_at=?,revoked_at=? WHERE token_hash=?",
                java.sql.Timestamp.from(f.shownAt.minusSeconds(2)),java.sql.Timestamp.from(proofAt),
                java.sql.Timestamp.from(if(mode=="expired-before") paidAt else paidAt.plusSeconds(30)),
                if(mode.startsWith("revoked")) java.sql.Timestamp.from(if(mode=="revoked-before")paidAt else paidAt.plusSeconds(1)) else null,sha(login.token))
            jdbc.update("UPDATE posm_source_staff_bindings SET created_at=? WHERE pharmacist_id=?",java.sql.Timestamp.from(f.shownAt.minusSeconds(1)),login.employee.person)
            acknowledge(f);deliver(f,payment(f))
            assertThat(balance(f.employee)).isEqualTo(if(mode=="revoked-after")400L else 0L)
        }
    }

    @Test fun `blocked transferred or changed credentials cannot spend an old automatic source binding`() {
        for(mode in listOf("blocked","transferred","password","iin","hq-revoked")) {
            val login=sourceLogin();val f=sourceFlow(login);acknowledge(f)
            when(mode) {
                "blocked"->jdbc.update("UPDATE pharmacists SET status='blocked' WHERE id=?",f.employee.person)
                "transferred"->jdbc.update("UPDATE pharmacists SET pharmacy_id=NULL WHERE id=?",f.employee.person)
                "password"->jdbc.update("UPDATE pharmacists SET password_hash=? WHERE id=?",encoder.encode("Other123!"),f.employee.person)
                "iin"->jdbc.update("UPDATE pharmacists SET iin=? WHERE id=?",validIin(),f.employee.person)
                else->{
                    mvc.perform(post("/api/admin/posm/pharmacist-mappings").header("Authorization",hq()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(UpsertPosmPharmacistMappingRequest(f.employee.pharmacy,f.employee.rawUser,"HQ",f.employee.person))))
                        .andExpect(status().isOk)
                    jdbc.update("UPDATE posm_pharmacist_mappings SET active=false,revoked_at=now() WHERE pharmacy_id=? AND external_user_id=?",f.employee.pharmacy,f.employee.rawUser)
                }
            }
            deliver(f,payment(f));assertThat(balance(f.employee)).isZero()
        }
    }

    @Test fun `changing a proven database context revokes its grant rather than reusing a native USER across databases`() {
        val login=sourceLogin()
        val changed=login.context.copy(standardNContext=login.context.standardNContext!!.copy(sourceNamespace=sha("another-source")))
        mvc.perform(post("/api/posm/staff/device/session/check").header("X-Posm-Key",login.employee.key)
            .header("X-Staff-Session",login.token).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(changed)))
            .andExpect(status().isUnauthorized)
        assertThat(rows("SELECT revoked_at FROM posm_device_staff_sessions WHERE token_hash=?",sha(login.token)).single()["revoked_at"]).isNotNull()
    }

    @Test fun `a conflicting verified login never rewrites a binding and the existing HQ mapping tool resolves the exception`() {
        for(preexisting in listOf(false,true)) {
        val first=sourceLogin();val other=employee(first.employee,mapped=false).copy(rawUser=first.employee.rawUser)
        if(preexisting) mvc.perform(post("/api/admin/posm/pharmacist-mappings").header("Authorization",hq()).contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsBytes(UpsertPosmPharmacistMappingRequest(other.pharmacy,other.rawUser,"HQ existing choice",other.person))))
            .andExpect(status().isOk)
        val second=sourceLogin(other)
        val binding=rows("SELECT * FROM posm_source_staff_bindings WHERE pharmacy_id=?",first.employee.pharmacy).single()
        assertThat(binding["pharmacist_id"]).isEqualTo(first.employee.person)
        assertThat(binding["state"]).isEqualTo("conflict")
        val blocked=sourceFlow(second);acknowledge(blocked);deliver(blocked,payment(blocked))
        assertThat(balance(other)).isZero()
        mvc.perform(post("/api/admin/posm/pharmacist-mappings").header("Authorization",hq()).contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsBytes(UpsertPosmPharmacistMappingRequest(other.pharmacy,other.rawUser,"Verified HQ choice",other.person))))
            .andExpect(status().isOk)
        val resumed=sourceFlow(second);acknowledge(resumed);deliver(resumed,payment(resumed))
        assertCredit(resumed,400)
        assertThat(balance(first.employee)).isZero()
        assertThat(rows("SELECT pharmacist_id FROM posm_source_staff_bindings WHERE pharmacy_id=?",other.pharmacy).single()["pharmacist_id"]).isEqualTo(first.employee.person)
        if(preexisting) assertThat(number("SELECT count(*) FROM posm_pharmacist_mapping_audit WHERE pharmacy_id=? AND action='reviewed'",other.pharmacy)).isEqualTo(1)
        }
    }

    @Test fun `repeated checked conflict preserves HQ approval but a new contradiction requires a new HQ decision`() {
        val first=sourceLogin()
        val second=sourceLogin(employee(first.employee,mapped=false).copy(rawUser=first.employee.rawUser))
        val oldConflict=rows("SELECT conflict_at FROM posm_source_staff_bindings WHERE pharmacy_id=?",first.employee.pharmacy).single()["conflict_at"]
        mvc.perform(post("/api/admin/posm/pharmacist-mappings").header("Authorization",hq()).contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsBytes(UpsertPosmPharmacistMappingRequest(second.employee.pharmacy,second.employee.rawUser,"HQ",second.employee.person))))
            .andExpect(status().isOk)
        checkSource(second)
        assertThat(rows("SELECT conflict_at FROM posm_source_staff_bindings WHERE pharmacy_id=?",first.employee.pharmacy).single()["conflict_at"]).isEqualTo(oldConflict)
        val third=sourceLogin(employee(first.employee,mapped=false).copy(rawUser=first.employee.rawUser))
        val afterThird=rows("SELECT conflict_at FROM posm_source_staff_bindings WHERE pharmacy_id=?",first.employee.pharmacy).single()["conflict_at"] as java.sql.Timestamp
        val reviewed=rows("SELECT updated_at FROM posm_pharmacist_mappings WHERE pharmacy_id=?",first.employee.pharmacy).single()["updated_at"] as java.sql.Timestamp
        assertThat(afterThird.toInstant()).isAfter(reviewed.toInstant())
        val denied=sourceFlow(third);acknowledge(denied);deliver(denied,payment(denied));assertThat(balance(third.employee)).isZero()
        checkSource(second)
        assertThat(number("SELECT count(*) FROM posm_source_staff_binding_audit WHERE claimant_id=? AND action='conflict'",second.employee.person)).isEqualTo(2)
        assertThat(rows("SELECT pharmacist_id FROM posm_source_staff_bindings WHERE pharmacy_id=?",first.employee.pharmacy).single()["pharmacist_id"]).isEqualTo(first.employee.person)
    }

    @Test fun `HQ remapping cannot commit inside a source authorized monetary transaction`() {
        val login=sourceLogin();val other=employee(login.employee,mapped=false)
        val hqToken=hq();val held=CountDownLatch(1);val release=CountDownLatch(1)
        val workers=Executors.newFixedThreadPool(2)
        try {
            val financial=workers.submit<Boolean> {
                org.springframework.transaction.support.TransactionTemplate(transactions).execute {
                    val admitted=sourceBindings.authorizes(login.employee.person,login.employee.pharmacy,login.employee.device,
                        login.context.standardNContext!!.sourceNamespace,login.employee.rawUser,Instant.now().plusSeconds(1),"901",login.employee.device)
                    held.countDown()
                    assertThat(release.await(10,TimeUnit.SECONDS)).isTrue()
                    admitted
                }!!
            }
            assertThat(held.await(10,TimeUnit.SECONDS)).isTrue()
            val remap=workers.submit {
                mvc.perform(post("/api/admin/posm/pharmacist-mappings").header("Authorization",hqToken).contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsBytes(UpsertPosmPharmacistMappingRequest(other.pharmacy,login.employee.rawUser,"HQ",other.person))))
                    .andExpect(status().isOk)
            }
            org.junit.jupiter.api.assertThrows<java.util.concurrent.TimeoutException> { remap.get(250,TimeUnit.MILLISECONDS) }
            release.countDown()
            assertThat(financial.get(10,TimeUnit.SECONDS)).isTrue()
            remap.get(10,TimeUnit.SECONDS)
            assertThat(rows("SELECT pharmacist_id FROM posm_pharmacist_mappings WHERE pharmacy_id=?",other.pharmacy).single()["pharmacist_id"]).isEqualTo(other.person)
        } finally { release.countDown();workers.shutdownNow() }
    }

    @Test fun `the exact two minute addition boundary qualifies but one nanosecond later does not`() {
        for ((delay, expected) in listOf(Duration.ofSeconds(120) to 400L, Duration.ofSeconds(120).plusNanos(1) to 0L)) {
            val f = flow()
            acknowledge(f)
            deliver(f, payment(f, additionDelay = delay))
            assertCredit(f, expected)
            assertThat(balance(f.employee)).isEqualTo(expected)
            assertThat(spent(f.world.offers.single())).isEqualTo(expected)
        }
    }

    @Test fun `an offered product already present when the popup is displayed never earns a new reward`() {
        val f = flow(preexisting = true)
        acknowledge(f)
        deliver(f, payment(f))
        assertCredit(f, 0)
        assertThat(cases(f).single()["reason_code"]).isEqualTo("product_preexisting")
        assertThat(balance(f.employee)).isZero()
        assertThat(spent(f.world.offers.single())).isZero()
    }

    @Test fun `removed packages do not earn and a reappearing generation must satisfy its own addition window`() {
        for ((readdedAt, expected) in listOf(40L to 200L, 150L to 0L)) {
            val f = flow(quantities = listOf(1))
            acknowledge(f)
            val offer = f.world.offers.single()
            val steps = listOf(
                Step(offer, 0, 1, Duration.ofSeconds(20), "old"),
                Step(offer, 1, 0, Duration.ofSeconds(30), "old"),
                Step(offer, 0, 1, Duration.ofSeconds(readdedAt), "new"),
            )
            deliver(f, payment(f, steps = steps))
            assertCredit(f, expected)
            assertThat(balance(f.employee)).isEqualTo(expected)
        }
    }

    @Test fun `an offline sale delivered before its shown ACK credits once after the late genuine ACK`() {
        val f = flow()
        val request = payment(f)
        deliver(f, request)
        assertThat(balance(f.employee)).isZero()
        assertThat(cases(f).single()["state"]).isEqualTo("waiting_evidence")
        assertThat(number("SELECT count(*) FROM recommendation_reward_receipts WHERE pharmacy_id=?", f.employee.pharmacy)).isEqualTo(1)
        acknowledge(f)
        acknowledge(f)
        deliver(f, request)
        assertCredit(f, 400)
        assertThat(balance(f.employee)).isEqualTo(400)
        assertThat(spent(f.world.offers.single())).isEqualTo(400)
        assertThat(number("SELECT count(*) FROM recommendation_reward_receipts WHERE pharmacy_id=?", f.employee.pharmacy)).isEqualTo(1)
    }

    @Test fun `a late shown ACK cannot credit a stored HQ identity after its mapping was revoked or reassigned`() {
        for (reassigned in listOf(false, true)) {
            val f = flow()
            val request = payment(f)
            deliver(f, request)
            assertThat(cases(f).single()["state"]).isEqualTo("waiting_evidence")
            assertThat(sales.findById(f.sale).orElseThrow().pharmacistSource).isEqualTo("standardn_explicit_mapping")
            val replacement = employee(f.employee, mapped = false)
            if (reassigned) {
                mvc.perform(post("/api/admin/posm/pharmacist-mappings").header("Authorization", hq())
                    .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(
                        UpsertPosmPharmacistMappingRequest(f.employee.pharmacy, f.employee.rawUser, "Synthetic cashier", replacement.person))))
                    .andExpect(status().isOk)
            } else {
                val mapping = rows("SELECT id FROM posm_pharmacist_mappings WHERE pharmacy_id=? AND external_user_id=?",
                    f.employee.pharmacy, f.employee.rawUser).single()["id"]
                mvc.perform(delete("/api/admin/posm/pharmacist-mappings/$mapping").header("Authorization", hq()))
                    .andExpect(status().isNoContent)
            }

            acknowledge(f)
            deliver(f, request)

            assertThat(cases(f).single()["state"]).isEqualTo("review_required")
            assertThat(cases(f).single()["reason_code"]).isEqualTo("employee_unverified")
            assertThat(balance(f.employee)).isZero()
            assertThat(balance(replacement)).isZero()
            assertThat(spent(f.world.offers.single())).isZero()
            assertThat(number("SELECT count(*) FROM pending_bonuses WHERE source_sale_id=?", f.sale)).isZero()
        }
    }

    @Test fun `a native source conflict after a stored sale blocks its late shown ACK despite an active HQ mapping`() {
        val f = flow()
        val request = payment(f)
        deliver(f, request)
        assertThat(cases(f).single()["state"]).isEqualTo("waiting_evidence")
        assertThat(sales.findById(f.sale).orElseThrow().pharmacistSource).isEqualTo("standardn_explicit_mapping")

        val claimant = employee(f.employee, mapped = false).copy(rawUser = f.employee.rawUser)
        sourceLogin(claimant)
        val binding = rows("SELECT pharmacist_id,state,reason_code FROM posm_source_staff_bindings WHERE pharmacy_id=? AND native_user_id=?",
            f.employee.pharmacy, f.employee.rawUser).single()
        assertThat(binding["pharmacist_id"]).isEqualTo(claimant.person)
        assertThat(binding["state"]).isEqualTo("conflict")
        assertThat(binding["reason_code"]).isEqualTo("hq_mapping_conflict")
        val mapping = rows("SELECT pharmacist_id,active FROM posm_pharmacist_mappings WHERE pharmacy_id=? AND external_user_id=?",
            f.employee.pharmacy, f.employee.rawUser).single()
        assertThat(mapping["pharmacist_id"]).isEqualTo(f.employee.person)
        assertThat(mapping["active"]).isEqualTo(true)

        acknowledge(f)
        assertThat(cases(f).single()["state"]).isEqualTo("review_required")
        assertThat(cases(f).single()["reason_code"]).isEqualTo("employee_unverified")
        deliver(f, request)
        acknowledge(f)

        assertCredit(f, 0)
        assertThat(cases(f).single()["state"]).isEqualTo("review_required")
        assertThat(cases(f).single()["reason_code"]).isEqualTo("employee_unverified")
        assertThat(balance(f.employee)).isZero()
        assertThat(balance(claimant)).isZero()
        assertThat(spent(f.world.offers.single())).isZero()
        assertThat(number("SELECT count(*) FROM pending_bonuses WHERE source_sale_id=?", f.sale)).isZero()
        assertThat(number("SELECT count(*) FROM pharmacist_wallet_entries WHERE pharmacist_id IN (?,?)",
            f.employee.person, claimant.person)).isZero()
    }

    @Test fun `ordinary paid checkout retains raw v2 sale without allocating financial cases or invoking legacy bonuses`() {
        val f = flow()
        val session = "ordinary_${token()}"
        val paid = payment(f).let { it.copy(sessionId = session,
            rewardEvidence = it.rewardEvidence!!.copy(checkoutSessionId = session)) }
        deliver(f, paid)
        deliver(f, paid)
        assertThat(number("SELECT count(*) FROM pos_sales WHERE id=? AND reward_evidence IS NOT NULL", paid.saleId)).isEqualTo(1)
        assertThat(number("SELECT count(*) FROM recommendation_reward_receipts WHERE pharmacy_id=?", f.employee.pharmacy)).isZero()
        assertThat(number("SELECT count(*) FROM recommendation_sale_rewards WHERE pharmacy_id=?", f.employee.pharmacy)).isZero()
        assertThat(number("SELECT count(*) FROM pending_bonuses WHERE pharmacy_id=?", f.employee.pharmacy)).isZero()
        assertThat(balance(f.employee)).isZero()
        assertThat(spent(f.world.offers.single())).isZero()
    }

    @Test fun `a changed physical alias without recommendation events still fences the original credited receipt`() {
        val f = flow()
        acknowledge(f)
        val paid = payment(f)
        deliver(f, paid)
        assertCredit(f, 400)
        val session = "alias_without_events_${token()}"
        val alias = paid.copy(saleId = "alias_${token()}", sessionId = session,
            rewardEvidence = paid.rewardEvidence!!.copy(checkoutSessionId = session))
        deliver(f, alias)
        deliver(f, alias)
        assertThat(number("SELECT count(*) FROM pos_sales WHERE id IN (?,?) AND reward_evidence IS NOT NULL", f.sale, alias.saleId)).isEqualTo(2)
        assertThat(number("SELECT count(*) FROM recommendation_reward_receipts WHERE pharmacy_id=?", f.employee.pharmacy)).isEqualTo(1)
        assertThat(cases(f).single()["state"]).isEqualTo("refund_review")
        assertThat(cases(f).single()["reason_code"]).isEqualTo("receipt_payload_conflict")
        assertThat(number("SELECT count(*) FROM recommendation_reward_audit WHERE pharmacy_id=? AND action='receipt_conflict'", f.employee.pharmacy)).isEqualTo(1)
        assertThat(number("SELECT count(*) FROM pharmacist_wallet_entries WHERE pharmacist_id=?", f.employee.person)).isEqualTo(1)
        assertThat(balance(f.employee)).isEqualTo(400)
        assertThat(spent(f.world.offers.single())).isEqualTo(400)
    }

    @Test fun `six simultaneous identical HTTP deliveries preserve one raw sale receipt budget debit and wallet credit`() {
        val f = flow()
        acknowledge(f)
        val request = payment(f)
        val replies = parallel((1..6).map { { deliver(f, request) } })
        assertThat(replies.count { it["accepted"].asBoolean() }).isEqualTo(1)
        assertCredit(f, 400)
        assertThat(balance(f.employee)).isEqualTo(400)
        assertThat(number("SELECT count(*) FROM pos_sales WHERE id=?", f.sale)).isEqualTo(1)
        assertThat(spent(f.world.offers.single())).isEqualTo(400)
    }

    @Test fun `another transport sale ID for the same physical and fiscal receipt cannot earn again`() {
        val f = flow()
        acknowledge(f)
        val request = payment(f)
        deliver(f, request)
        val alias = request.copy(saleId = "alias_${UUID.randomUUID()}")
        deliver(f, alias)
        assertCredit(f, 400)
        assertThat(balance(f.employee)).isEqualTo(400)
        assertThat(number("SELECT count(*) FROM pos_sales WHERE id IN (?,?)", f.sale, alias.saleId)).isEqualTo(2)
        assertThat(number("SELECT count(*) FROM recommendation_reward_receipts WHERE pharmacy_id=?", f.employee.pharmacy)).isEqualTo(1)
        assertThat(spent(f.world.offers.single())).isEqualTo(400)
    }

    @Test fun `a changed retry body is rejected with conflict while original evidence and money remain intact`() {
        val f = flow()
        acknowledge(f)
        val original = payment(f)
        deliver(f, original)
        val changed = original.copy(items = original.items.map { it.copy(name = "Changed immutable sale") })
        deliver(f, changed, expectedStatus = 409)
        assertCredit(f, 400)
        assertThat(balance(f.employee)).isEqualTo(400)
        assertThat(spent(f.world.offers.single())).isEqualTo(400)
        val stored = mapper.readTree(rows("SELECT items::text FROM pos_sales WHERE id=?", f.sale).single()["items"].toString())
        assertThat(stored[0]["name"].asText()).isEqualTo(original.items.single().name)
    }

    @Test fun `unknown package units and an unmapped raw seller stay in HQ review without moving money`() {
        for (unknownActor in listOf(false, true)) {
            val f = flow(employee = employee(mapped = !unknownActor))
            acknowledge(f)
            val original = payment(f)
            val request = if (unknownActor) original else original.copy(rewardEvidence = original.rewardEvidence!!.let {
                it.copy(completion = it.completion.copy(lines = it.completion.lines.map { line -> line.copy(unitSemantics = "unknown") }))
            })
            deliver(f, request)
            assertCredit(f, 0)
            assertThat(cases(f).single()["state"]).isEqualTo("review_required")
            assertThat(balance(f.employee)).isZero()
            assertThat(spent(f.world.offers.single())).isZero()
        }
    }

    @Test fun `a refund from another cashier delivered before its original sale fences the later reward for HQ`() {
        val f = flow()
        acknowledge(f)
        val other = employee(base = f.employee)
        deliver(f, refund(f, other), employee = other)
        deliver(f, payment(f))
        assertCredit(f, 0)
        assertThat(cases(f).single()["state"]).isIn("review_required", "refund_review")
        assertThat(balance(f.employee)).isZero()
        assertThat(balance(other)).isZero()
        assertThat(spent(f.world.offers.single())).isZero()
        assertThat(number("SELECT count(*) FROM pos_sales WHERE pharmacy_id=?", f.employee.pharmacy)).isEqualTo(2)
    }

    @Test fun `a refund after credit never silently debits the pharmacist or reopens its budget`() {
        val f = flow()
        acknowledge(f)
        deliver(f, payment(f))
        deliver(f, refund(f, f.employee))
        assertThat(cases(f).single()["state"]).isEqualTo("refund_review")
        assertThat(balance(f.employee)).isEqualTo(400)
        assertThat(spent(f.world.offers.single())).isEqualTo(400)
        assertThat(number("SELECT count(*) FROM pharmacist_wallet_entries WHERE pharmacist_id=?", f.employee.person)).isEqualTo(1)
    }

    @Test fun `concurrent refund and original from different cashiers commit only retained credit or HQ hold`() {
        repeat(3) {
            val f = flow()
            acknowledge(f)
            val other = employee(base = f.employee)
            parallel(listOf(
                { deliver(f, payment(f)) },
                { deliver(f, refund(f, other), employee = other) },
            ))
            assertThat(cases(f).single()["state"]).isIn("review_required", "refund_review")
            assertThat(balance(f.employee)).isIn(0L, 400L)
            assertThat(spent(f.world.offers.single())).isEqualTo(balance(f.employee))
            assertThat(balance(other)).isZero()
            assertThat(number("SELECT count(*) FROM pharmacist_wallet_entries WHERE pharmacist_id=?", f.employee.person))
                .isEqualTo(if (balance(f.employee) == 0L) 0L else 1L)
        }
    }

    @Test fun `the last campaign budget is shared atomically between independent employee HTTP sales`() {
        val world = world(budget = 400)
        val first = flow(world = world)
        val second = flow(world = world, employee = employee(base = first.employee))
        acknowledge(first); acknowledge(second)
        parallel(listOf({ deliver(first, payment(first)) }, { deliver(second, payment(second)) }))
        assertThat(balance(first.employee) + balance(second.employee)).isEqualTo(400)
        assertThat(spent(world.offers.single())).isEqualTo(400)
        assertThat((cases(first) + cases(second)).map { it["state"] }).containsExactlyInAnyOrder("credited", "review_required")
        assertThat(number("SELECT count(*) FROM pos_sales WHERE pharmacy_id=?", first.employee.pharmacy)).isEqualTo(2)
    }

    @Test fun `opposite multi campaign orders for the same and different employees cannot deadlock or duplicate funds`() {
        for (sameEmployee in listOf(true, false)) {
            val world = world(offerCount = 2)
            val first = flow(world = world, quantities = listOf(1, 1))
            val second = flow(world = world, employee = if (sameEmployee) first.employee else employee(base = first.employee),
                quantities = listOf(1, 1), reverse = true)
            acknowledge(first); acknowledge(second)
            parallel(listOf({ deliver(first, payment(first)) }, { deliver(second, payment(second)) }))
            assertCredit(first, 400); assertCredit(second, 400)
            if (sameEmployee) assertThat(balance(first.employee)).isEqualTo(800)
            else {
                assertThat(balance(first.employee)).isEqualTo(400)
                assertThat(balance(second.employee)).isEqualTo(400)
            }
            world.offers.forEach { assertThat(spent(it)).isEqualTo(400) }
            assertThat(number("SELECT count(*) FROM pharmacist_wallet_entries WHERE pharmacist_id IN (?,?)", first.employee.person, second.employee.person)).isEqualTo(4)
        }
    }

    @Test fun `sixteen concurrent normal shown ACKs sharing one campaign do not deadlock on its foreign key`() {
        val world = world(budget = 10_000)
        val first = employee()
        val actors = (0 until 16).map { index -> flow(world = world, employee = if (index == 0) first else employee(base = first)) }
        parallel(actors.map { actor -> { acknowledge(actor) } })
        assertThat(number("SELECT count(*) FROM recommendation_reward_shows WHERE promo_id=?", world.offers.single().promo)).isEqualTo(16)
        assertThat(spent(world.offers.single())).isZero()
        assertThat(number("SELECT coalesce(sum(balance),0) FROM pharmacists WHERE pharmacy_id=?", first.pharmacy)).isZero()
        actors.chunked(4).forEach { group -> parallel(group.map { actor -> { deliver(actor, payment(actor)) } }) }
        actors.forEach { actor ->
            assertCredit(actor, 400)
            assertThat(balance(actor.employee)).isEqualTo(400)
        }
        assertThat(spent(world.offers.single())).isEqualTo(6400)
        assertThat(number("SELECT coalesce(sum(balance),0) FROM pharmacists WHERE pharmacy_id=?", first.pharmacy)).isEqualTo(6400)
        assertThat(number("SELECT count(*) FROM recommendation_sale_rewards WHERE pharmacy_id=? AND state='credited'", first.pharmacy)).isEqualTo(16)
        assertThat(number("SELECT count(*) FROM pos_sales WHERE pharmacy_id=?", first.pharmacy)).isEqualTo(16)
        assertThat(number("""SELECT count(*) FROM pharmacist_wallet_entries e JOIN pharmacists p ON p.id=e.pharmacist_id
            WHERE p.pharmacy_id=? AND e.source='receipt_bonus'""", first.pharmacy)).isEqualTo(16)
    }

    @Test fun `HQ scope restricts list detail export and decisions while verified unknown units can be approved once`() {
        val local = flow()
        val foreign = flow()
        for (f in listOf(local, foreign)) {
            acknowledge(f)
            val request = payment(f)
            deliver(f, request.copy(rewardEvidence = request.rewardEvidence!!.let {
                it.copy(completion = it.completion.copy(lines = it.completion.lines.map { line -> line.copy(unitSemantics = "unknown") }))
            }))
        }
        val localId = cases(local).single()["id"].toString()
        val foreignId = cases(foreign).single()["id"].toString()
        val scoped = reviewer(setOf(local.employee.pharmacy), approve = true, export = true)
        val list = mvc.perform(get(REVIEW_PATH).header("Authorization", scoped)).andExpect(status().isOk).andReturn()
        val page = mapper.readTree(list.response.contentAsByteArray)
        assertThat(page["items"].map { it["id"].asText() }).containsExactly(localId)
        mvc.perform(get("$REVIEW_PATH/$foreignId").header("Authorization", scoped)).andExpect(status().isForbidden)
        decide(foreignId, scoped, RewardReviewDecision("reject", "Not my pharmacy"), 403)
        val exported = mvc.perform(get("$REVIEW_PATH/export.xlsx").header("Authorization", scoped))
            .andExpect(status().isOk).andReturn().response.contentAsByteArray
        XSSFWorkbook(ByteArrayInputStream(exported)).use { book ->
            assertThat(book.getSheetAt(0).lastRowNum).isEqualTo(1)
            assertThat(book.getSheetAt(0).getRow(1).getCell(0).stringCellValue).isEqualTo(localId)
            assertThat(book.getSheetAt(0).getRow(1).getCell(3).stringCellValue).isEqualTo(local.employee.pharmacy)
        }
        val viewOnly = reviewer(setOf(local.employee.pharmacy), approve = false)
        val decision = RewardReviewDecision("approve", "Checked original paid receipt and complete packages",
            local.employee.person, 2, confirmedPayment = true, confirmedPackages = true)
        decide(localId, viewOnly, decision, 403)
        assertThat(balance(local.employee)).isZero()
        decide(localId, scoped, decision)
        assertCredit(local, 400)
        assertThat(balance(local.employee)).isEqualTo(400)
        assertThat(spent(local.world.offers.single())).isEqualTo(400)
        decide(localId, scoped, decision, 409)
        assertThat(balance(local.employee)).isEqualTo(400)
        assertThat(balance(foreign.employee)).isZero()
    }

    @Test fun `even HQ confirmation cannot bypass the missing canonical fiscal receipt fence`() {
        val f = flow()
        acknowledge(f)
        val request = payment(f)
        deliver(f, request.copy(rewardEvidence = request.rewardEvidence!!.let {
            it.copy(completion = it.completion.copy(deviceNumber = ""))
        }))
        val reward = cases(f).single()
        assertThat(reward["state"]).isEqualTo("review_required")
        assertThat(rows("SELECT fiscal_key FROM recommendation_reward_receipts WHERE id=?", reward["canonical_receipt_id"]!!).single()["fiscal_key"]).isNull()
        val scoped = reviewer(setOf(f.employee.pharmacy))
        decide(reward["id"].toString(), scoped, RewardReviewDecision("approve", "Cannot replace missing original fiscal provenance",
            f.employee.person, 2, confirmedPayment = true, confirmedPackages = true), 409)
        assertThat(balance(f.employee)).isZero()
        assertThat(spent(f.world.offers.single())).isZero()
    }

    @Test fun `HQ acknowledging an already credited refund records its decision without an automatic debit`() {
        val f = flow()
        acknowledge(f)
        deliver(f, payment(f))
        deliver(f, refund(f, f.employee))
        val id = cases(f).single()["id"].toString()
        val scoped = reviewer(setOf(f.employee.pharmacy))
        val decision = RewardReviewDecision("acknowledge_refund", "Refund reviewed with original credit retained")
        decide(id, scoped, decision)
        assertThat(cases(f).single()["state"]).isEqualTo("credited")
        assertThat(balance(f.employee)).isEqualTo(400)
        assertThat(spent(f.world.offers.single())).isEqualTo(400)
        assertThat(number("SELECT count(*) FROM pharmacist_wallet_entries WHERE pharmacist_id=?", f.employee.person)).isEqualTo(1)
        assertThat(number("SELECT count(*) FROM recommendation_reward_audit WHERE reward_id=? AND action='acknowledge_refund'", id)).isEqualTo(1)
        decide(id, scoped, decision, 409)
        assertThat(balance(f.employee)).isEqualTo(400)
    }

    @Test fun `refund replay and physical transport alias cannot reopen the credited HQ decision or duplicate its effects`() {
        val f = flow()
        acknowledge(f)
        deliver(f, payment(f))
        val returned = refund(f, f.employee)
        deliver(f, returned)
        val id = cases(f).single()["id"].toString()
        decide(id, reviewer(setOf(f.employee.pharmacy)), RewardReviewDecision("acknowledge_refund", "Original credit retained after verified return"))
        val auditBefore = number("SELECT count(*) FROM recommendation_reward_audit WHERE pharmacy_id=?", f.employee.pharmacy)
        val alias = returned.copy(saleId = "refund_alias_${token()}")
        deliver(f, returned)
        deliver(f, alias)
        assertThat(cases(f).single()["state"]).isEqualTo("credited")
        assertThat(cases(f).single()["reason_code"]).isEqualTo("refund_reviewed")
        assertThat(number("SELECT count(*) FROM recommendation_reward_refund_holds WHERE pharmacy_id=?", f.employee.pharmacy)).isEqualTo(1)
        assertThat(number("SELECT count(*) FROM recommendation_reward_audit WHERE pharmacy_id=?", f.employee.pharmacy)).isEqualTo(auditBefore)
        assertThat(number("SELECT count(*) FROM recommendation_reward_receipts WHERE pharmacy_id=?", f.employee.pharmacy)).isEqualTo(2)
        assertThat(number("SELECT count(*) FROM pos_sales WHERE pharmacy_id=?", f.employee.pharmacy)).isEqualTo(3)
        assertCredit(f, 400)
        assertThat(balance(f.employee)).isEqualTo(400)
        assertThat(spent(f.world.offers.single())).isEqualTo(400)
    }

    @Test fun `known RPS source mismatch vetoes reward while absent matched or different source contexts are neutral`() {
        for (mode in listOf("mismatch", "match", "absent", "other-user", "other-session", "other-workstation", "other-device", "other-pharmacy", "expired", "revoked", "future")) {
            val f = flow()
            acknowledge(f)
            val paid = payment(f)
            if (mode != "absent") {
                val person = people.findById(f.employee.person).orElseThrow()
                val created = if (mode == "future") paid.printedAt.plusSeconds(1) else f.shownAt.minusSeconds(3600)
                val expires = if (mode == "expired") paid.printedAt.minusSeconds(1) else paid.printedAt.plusSeconds(3600)
                val revoked = if (mode == "revoked") java.sql.Timestamp.from(paid.printedAt.minusSeconds(1)) else null
                jdbc.update("""INSERT INTO posm_device_staff_sessions(token_hash,pharmacist_id,device_id,pharmacy_id,password_fingerprint,
                    process_instance_id,standard_n_user_id,standard_n_session_id,workstation_id,standard_n_iin,standard_n_iin_status,
                    expires_at,created_at,revoked_at) VALUES (?,?,?,?,?,?,?,?,?,?,'available',?,?,?)""",
                    sha("grant:${token()}"),person.id,if(mode=="other-device") "different-cash" else f.employee.device,
                    if(mode=="other-pharmacy") "different-pharmacy" else f.employee.pharmacy,sha("unused-test-password"),"process-test",
                    if(mode=="other-user") "different-user" else f.identity.rawUserId,
                    if(mode=="other-session") (f.identity.nativeSessionId+1).toString() else f.identity.nativeSessionId.toString(),
                    if(mode=="other-workstation") "different-workstation" else f.identity.workstationId,
                    if(mode=="match") person.iin else "111111111111",java.sql.Timestamp.from(expires),java.sql.Timestamp.from(created),revoked)
            }
            deliver(f, paid)
            val expected = if (mode == "mismatch") 0L else 400L
            assertCredit(f, expected)
            assertThat(balance(f.employee)).isEqualTo(expected)
            assertThat(spent(f.world.offers.single())).isEqualTo(expected)
            if (mode == "mismatch") {
                assertThat(cases(f).single()["state"]).isEqualTo("review_required")
                assertThat(cases(f).single()["reason_code"]).isEqualTo("rps_identity_mismatch")
            }
        }
    }

    @Test fun `RPS observations compare against the financial recipient rather than the account owning the grant`() {
        for (source in listOf("grant-profile", "recipient-profile", "unknown")) {
            val f = flow()
            val other = employee(base = f.employee, mapped = false)
            val recipient = people.findById(f.employee.person).orElseThrow()
            val grantOwner = people.findById(other.person).orElseThrow()
            acknowledge(f)
            val paid = payment(f)
            jdbc.update("""INSERT INTO posm_device_staff_sessions(token_hash,pharmacist_id,device_id,pharmacy_id,password_fingerprint,
                process_instance_id,standard_n_user_id,standard_n_session_id,workstation_id,standard_n_iin,standard_n_iin_status,
                expires_at,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                sha("grant:${token()}"),grantOwner.id,f.employee.device,f.employee.pharmacy,sha("unused-test-password"),"process-test",
                f.identity.rawUserId,f.identity.nativeSessionId.toString(),f.identity.workstationId,
                when(source) { "grant-profile" -> grantOwner.iin; "recipient-profile" -> recipient.iin; else -> null },
                if(source=="unknown") "missing" else "available",
                java.sql.Timestamp.from(paid.printedAt.plusSeconds(3600)),java.sql.Timestamp.from(f.shownAt.minusSeconds(3600)))
            deliver(f, paid)
            val expected = if(source=="grant-profile") 0L else 400L
            assertCredit(f, expected)
            assertThat(balance(f.employee)).isEqualTo(expected)
            assertThat(balance(other)).isZero()
            assertThat(spent(f.world.offers.single())).isEqualTo(expected)
            if(source=="grant-profile") {
                assertThat(cases(f).single()["state"]).isEqualTo("review_required")
                assertThat(cases(f).single()["reason_code"]).isEqualTo("rps_identity_mismatch")
            }
        }
    }

    @Test fun `conflicting known physical and fiscal originals create only unlinked HQ review without freezing either receipt`() {
        val first = flow()
        val second = flow(world = first.world, employee = first.employee)
        acknowledge(first); acknowledge(second)
        deliver(first, payment(first)); deliver(second, payment(second))
        val returned = refund(first, first.employee)
        val evidence = returned.rewardEvidence!!
        deliver(first, returned.copy(rewardEvidence = evidence.copy(completion = evidence.completion.copy(originalFiscalNumber = second.doc))))
        assertThat(cases(first).single()["state"]).isEqualTo("credited")
        assertThat(cases(second).single()["state"]).isEqualTo("credited")
        assertThat(number("SELECT count(*) FROM recommendation_reward_refund_holds WHERE pharmacy_id=?", first.employee.pharmacy)).isZero()
        val review = rows("""SELECT r.physical_key,r.fiscal_key,w.reason_code FROM recommendation_reward_receipts r
            JOIN recommendation_sale_rewards w ON w.canonical_receipt_id=r.id WHERE r.source_sale_id=?""", returned.saleId).single()
        assertThat(review["physical_key"]).isNull(); assertThat(review["fiscal_key"]).isNull()
        assertThat(review["reason_code"]).isEqualTo("refund_original_reference_conflict")
        assertThat(balance(first.employee)).isEqualTo(800)
        assertThat(spent(first.world.offers.single())).isEqualTo(800)
    }

    @Test fun `same fiscal return across source namespace document and transport aliases cannot reopen an acknowledged case`() {
        val f = flow()
        acknowledge(f)
        deliver(f, payment(f))
        val returned = refund(f, f.employee)
        deliver(f, returned)
        val id = cases(f).single()["id"].toString()
        decide(id, reviewer(setOf(f.employee.pharmacy)), RewardReviewDecision("acknowledge_refund", "Credit retained after verified return"))
        val before = number("SELECT count(*) FROM recommendation_reward_audit WHERE pharmacy_id=?", f.employee.pharmacy)
        val aliasDoc = documents.incrementAndGet()
        val aliasSession = "refund_alias_${token()}"
        val evidence = returned.rewardEvidence!!
        val alias = returned.copy(saleId = "refund_fiscal_alias_${token()}", sessionId = aliasSession, sourceDocumentId = aliasDoc,
            rewardEvidence = evidence.copy(checkoutSessionId = aliasSession, completion = evidence.completion.copy(
                sourceDocumentId = aliasDoc, identity = evidence.completion.identity.copy(sourceNamespace = "b".repeat(64)),
                lines = evidence.completion.lines.map { it.copy(sourceDocumentId = aliasDoc) })))
        deliver(f, alias)
        assertThat(cases(f).single()["state"]).isEqualTo("credited")
        assertThat(cases(f).single()["reason_code"]).isEqualTo("refund_reviewed")
        assertThat(number("SELECT count(*) FROM recommendation_reward_refund_holds WHERE pharmacy_id=?", f.employee.pharmacy)).isEqualTo(1)
        assertThat(number("SELECT count(*) FROM recommendation_reward_audit WHERE pharmacy_id=?", f.employee.pharmacy)).isEqualTo(before)
        assertThat(number("SELECT count(*) FROM recommendation_reward_receipts WHERE pharmacy_id=?", f.employee.pharmacy)).isEqualTo(2)
        assertCredit(f, 400)
        assertThat(balance(f.employee)).isEqualTo(400)
    }

    @Test fun `paid and return counters may overlap without sharing a canonical fiscal operation`() {
        val f = flow()
        acknowledge(f)
        deliver(f, payment(f))
        val returned = refund(f, f.employee)
        val evidence = returned.rewardEvidence!!
        deliver(f, returned.copy(rewardEvidence = evidence.copy(completion = evidence.completion.copy(fiscalNumber = f.doc))))
        assertThat(cases(f).single()["reason_code"]).isEqualTo("refund_reported")
        assertThat(number("SELECT count(*) FROM recommendation_reward_receipts WHERE pharmacy_id=?", f.employee.pharmacy)).isEqualTo(2)
        assertThat(number("SELECT count(DISTINCT fiscal_key) FROM recommendation_reward_receipts WHERE pharmacy_id=?", f.employee.pharmacy)).isEqualTo(2)
        assertThat(number("SELECT count(*) FROM recommendation_reward_audit WHERE pharmacy_id=? AND action='receipt_conflict'", f.employee.pharmacy)).isZero()
        assertThat(balance(f.employee)).isEqualTo(400)
    }

    @Test fun `same physical return with changed original reference is a conflict and creates no second hold`() {
        val f = flow()
        acknowledge(f)
        val returned = refund(f, f.employee)
        deliver(f, returned)
        val alias = returned.copy(saleId = "refund_changed_${token()}", rewardEvidence = returned.rewardEvidence!!.let {
            it.copy(completion = it.completion.copy(originalSourceDocumentId = f.doc + 5000))
        })
        deliver(f, alias)
        val review = rows("SELECT state,reason_code FROM recommendation_sale_rewards WHERE source_sale_id=?", returned.saleId).single()
        assertThat(review["state"]).isEqualTo("review_required")
        assertThat(review["reason_code"]).isEqualTo("receipt_payload_conflict")
        assertThat(number("SELECT count(*) FROM recommendation_reward_refund_holds WHERE pharmacy_id=?", f.employee.pharmacy)).isEqualTo(1)
        assertThat(number("SELECT count(*) FROM recommendation_reward_audit WHERE pharmacy_id=? AND action='receipt_conflict'", f.employee.pharmacy)).isEqualTo(1)
        assertThat(balance(f.employee)).isZero()
        assertThat(spent(f.world.offers.single())).isZero()
    }

    @Test fun `acknowledged unlinked refund stays closed on replay and alias while its later original remains fenced`() {
        val f = flow()
        acknowledge(f)
        val returned = refund(f, f.employee)
        deliver(f, returned)
        val unlinked = rows("SELECT * FROM recommendation_sale_rewards WHERE source_sale_id=?", returned.saleId).single()
        val id = unlinked["id"].toString()
        decide(id, reviewer(setOf(f.employee.pharmacy)), RewardReviewDecision("acknowledge_refund", "Verified return before delayed original delivery"))
        val auditBefore = number("SELECT count(*) FROM recommendation_reward_audit WHERE pharmacy_id=?", f.employee.pharmacy)
        deliver(f, returned)
        deliver(f, returned.copy(saleId = "refund_alias_${token()}"))
        val closed = rows("SELECT state,reason_code FROM recommendation_sale_rewards WHERE id=?", id).single()
        assertThat(closed["state"]).isEqualTo("rejected")
        assertThat(closed["reason_code"]).isEqualTo("refund_reviewed")
        assertThat(number("SELECT count(*) FROM recommendation_reward_audit WHERE pharmacy_id=?", f.employee.pharmacy)).isEqualTo(auditBefore)
        assertThat(number("SELECT count(*) FROM recommendation_reward_refund_holds WHERE pharmacy_id=?", f.employee.pharmacy)).isEqualTo(1)
        assertThat(number("SELECT count(*) FROM recommendation_reward_receipts WHERE pharmacy_id=?", f.employee.pharmacy)).isEqualTo(1)
        deliver(f, payment(f))
        assertCredit(f, 0)
        assertThat(cases(f).single()["state"]).isEqualTo("refund_review")
        assertThat(balance(f.employee)).isZero()
        assertThat(spent(f.world.offers.single())).isZero()
        assertThat(number("SELECT count(*) FROM pos_sales WHERE pharmacy_id=?", f.employee.pharmacy)).isEqualTo(3)
    }

    @Test fun `native same document final line alias credits only the observed item and keeps the actual posted ID`() {
        val f = flow()
        acknowledge(f)
        val paid = payment(f)
        val evidence = paid.rewardEvidence!!
        val posted = evidence.completion.lines.single().copy(sourceLineId = 801, observedSourceLineId = 10,
            lineLinkSource = "standardn-unique-final-line")
        deliver(f, paid.copy(rewardEvidence = evidence.copy(completion = evidence.completion.copy(lines = listOf(posted)))))
        assertCredit(f, 400)
        assertThat(balance(f.employee)).isEqualTo(400)
        assertThat(spent(f.world.offers.single())).isEqualTo(400)
        assertThat(number("SELECT (reward_evidence #>> '{completion,lines,0,sourceLineId}')::bigint FROM pos_sales WHERE id=?", f.sale)).isEqualTo(801)
        assertThat(number("SELECT (reward_evidence #>> '{completion,lines,0,observedSourceLineId}')::bigint FROM pos_sales WHERE id=?", f.sale)).isEqualTo(10)
    }

    @Test fun `duplicate observed aliases across two posted SKUs require review and cannot issue money`() {
        val f = flow(world = world(offerCount = 2), quantities = listOf(1, 1))
        acknowledge(f)
        val paid = payment(f)
        val evidence = paid.rewardEvidence!!
        deliver(f, paid.copy(rewardEvidence = evidence.copy(completion = evidence.completion.copy(lines =
            evidence.completion.lines.mapIndexed { i, line -> line.copy(sourceLineId = 801L + i,
                observedSourceLineId = 10, lineLinkSource = "standardn-unique-final-line") }))))
        assertCredit(f, 0)
        assertThat(cases(f).map { it["reason_code"] }).containsOnly("paid_line_link_inconsistent")
        assertThat(balance(f.employee)).isZero()
        f.world.offers.forEach { assertThat(spent(it)).isZero() }
    }

    @Test fun `unknown or unposted refund cannot poison a paid canonical receipt and remains in the raw journal`() {
        for (malformed in listOf("source", "unposted", "missing-type")) {
            val f = flow()
            acknowledge(f)
            val returned = refund(f, f.employee)
            val evidence = returned.rewardEvidence!!
            val complete = evidence.completion.let { when (malformed) {
                "source" -> it.copy(source = "standardn-posted-print")
                "unposted" -> it.copy(documentStatus = 0)
                else -> it.copy(documentType = null)
            } }.copy(sourceDocumentId = f.doc, fiscalNumber = f.doc)
            // An unverified claim may even reuse the paid document IDs: it still owns no physical/fiscal fence.
            val invalid = returned.copy(sourceDocumentId = f.doc, rewardEvidence = evidence.copy(completion = complete))
            deliver(f, invalid)
            assertThat(number("SELECT count(*) FROM recommendation_reward_refund_holds WHERE pharmacy_id=?", f.employee.pharmacy)).isZero()
            val unlinked = rows("SELECT r.physical_key,r.fiscal_key,w.state FROM recommendation_reward_receipts r JOIN recommendation_sale_rewards w ON w.canonical_receipt_id=r.id WHERE r.source_sale_id=?", invalid.saleId).single()
            assertThat(unlinked["physical_key"]).isNull(); assertThat(unlinked["fiscal_key"]).isNull()
            assertThat(unlinked["state"]).isEqualTo("refund_review")
            assertThat(number("SELECT count(*) FROM pos_sales WHERE id=?", invalid.saleId)).isEqualTo(1)
            deliver(f, payment(f))
            assertCredit(f, 400)
            assertThat(balance(f.employee)).isEqualTo(400)
            assertThat(spent(f.world.offers.single())).isEqualTo(400)
        }
    }

    @Test fun `proven original physical document alone fences the reward without any invented original fiscal fields`() {
        val f = flow()
        acknowledge(f)
        val returned = refund(f, f.employee)
        val evidence = returned.rewardEvidence!!
        deliver(f, returned.copy(rewardEvidence = evidence.copy(completion = evidence.completion.copy(
            originalDeviceNumber = null, originalFiscalNumber = null, originalFiscalShift = null))))
        deliver(f, payment(f))
        assertCredit(f, 0)
        assertThat(cases(f).single()["state"]).isEqualTo("refund_review")
        val hold = rows("SELECT physical_key,fiscal_key FROM recommendation_reward_refund_holds WHERE pharmacy_id=?", f.employee.pharmacy).single()
        assertThat(hold["physical_key"]).isNotNull(); assertThat(hold["fiscal_key"]).isNull()
        assertThat(balance(f.employee)).isZero()
        assertThat(spent(f.world.offers.single())).isZero()
    }

    @Test fun `cross register fiscal refund uses its original register and never guesses it from the return register`() {
        for (hasOriginalRegister in listOf(true, false)) {
            val f = flow()
            acknowledge(f)
            deliver(f, payment(f))
            val returned = refund(f, f.employee)
            val evidence = returned.rewardEvidence!!
            val completion = evidence.completion.copy(originalSourceDocumentId = null, deviceNumber = "other-register",
                originalDeviceNumber = if (hasOriginalRegister) f.employee.device else null)
            deliver(f, returned.copy(rewardEvidence = evidence.copy(completion = completion)))
            assertThat(cases(f).single()["state"]).isEqualTo(if (hasOriginalRegister) "refund_review" else "credited")
            assertThat(number("SELECT count(*) FROM recommendation_reward_refund_holds WHERE pharmacy_id=?", f.employee.pharmacy))
                .isEqualTo(if (hasOriginalRegister) 1L else 0L)
            assertThat(balance(f.employee)).isEqualTo(400)
            assertThat(spent(f.world.offers.single())).isEqualTo(400)
        }
    }

    @Test fun `refund does not masquerade as a sale in attribution campaign totals or receipt lift and remains visible`() {
        val f = flow()
        acknowledge(f)
        val returned = refund(f, f.employee).let { r ->
            r.copy(sessionId = f.session, rewardEvidence = r.rewardEvidence!!.copy(checkoutSessionId = f.session))
        }
        deliver(f, returned)
        assertThat(number("SELECT count(*) FROM recommendation_events WHERE session_id=? AND sold_at IS NOT NULL", f.session)).isZero()
        val since = f.shownAt.minusSeconds(1)
        assertThat(sales.countByPharmacySince(since).filter { it.pharmacyId == f.employee.pharmacy }).isEmpty()
        assertThat(sales.amountByPharmacySince(since).filter { it.pharmacyId == f.employee.pharmacy }).isEmpty()
        assertThat(sales.countByPharmaciesSince(listOf(f.employee.pharmacy), since)).isZero()
        val journal = recommendationEvents.findJournalRowsSince(since, 1000, 0)
            .filter { it.saleId == returned.saleId }
        assertThat(journal).hasSize(1)
        assertThat(journal.single().eventType).isEqualTo("sale")
        assertThat(journal.single().title).startsWith("Возврат: ")
        // Defend historical/stale recommendation links too, without inventing another paid sale.
        jdbc.update("UPDATE recommendation_events SET sale_id=?,sold_at=? WHERE session_id=?", returned.saleId,
            java.sql.Timestamp.from(returned.printedAt), f.session)
        val counts = campaignReports.aggregate(f.world.offers.single().promo, since, Instant.now().plusSeconds(1))
            .map { it.counts }
        assertThat(counts.sumOf { it.attributedSales }).isZero()
        assertThat(counts.sumOf { it.recommendationSalesUnder2m }).isZero()
        assertThat(number("SELECT count(*) FROM pos_sales WHERE id=?", returned.saleId)).isEqualTo(1)
        assertThat(balance(f.employee)).isZero()
    }

    private data class Employee(val pharmacy: String, val person: String, val rawUser: String, val device: String, val key: String)
    private data class Offer(val id: String, val barcode: String, val promo: String)
    private data class World(val trigger: String, val triggerBarcode: String, val offers: List<Offer>)
    private data class Flow(val employee: Employee, val world: World, val session: String, val sale: String, val doc: Long,
        val shownAt: Instant, val identity: RewardSourceIdentity, val eventIds: Map<String, String>,
        val order: List<Offer>, val quantities: Map<String, Long>, val preexisting: Boolean)
    private data class Step(val offer: Offer, val before: Long, val after: Long, val delay: Duration, val generation: String = "original")

    private fun employee(base: Employee? = null, mapped: Boolean = true): Employee {
        val id = token()
        val pharmacy = base?.pharmacy ?: "ph_$id"
        if (base == null) {
            chains.saveAndFlush(ChainEntity(id = "ch_$id", name = "Synthetic test chain"))
            pharmacies.saveAndFlush(PharmacyEntity(id = pharmacy, name = "Synthetic pharmacy", chainId = "ch_$id", chainName = "Synthetic test chain"))
        }
        val person = "person_$id"
        val n = sequence.incrementAndGet().toString()
        people.saveAndFlush(PharmacistEntity(id = person, name = "Synthetic employee $id", iin = n, phone = "+7${n.takeLast(10)}",
            pharmacyId = pharmacy, pharmacyName = "Synthetic pharmacy", statusRaw = PharmacistStatus.active.name))
        val key = base?.key ?: "test-only-device-${UUID.randomUUID()}-${UUID.randomUUID()}"
        val device = base?.device ?: "cash_$id"
        if (base == null) jdbc.update("INSERT INTO fulfillment_devices(id,device_id,pharmacy_id,secret_sha256,secret_hint) VALUES (?,?,?,?,?)",
            UUID.randomUUID(), device, pharmacy, sha(key), "test-only")
        val raw = "RAW_$id"
        if (mapped) {
            mvc.perform(post("/api/admin/posm/pharmacist-mappings").header("Authorization", hq())
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(
                    UpsertPosmPharmacistMappingRequest(pharmacy, raw, "Synthetic cashier", person))))
                .andExpect(status().isOk)
        }
        return Employee(pharmacy, person, raw, device, key)
    }

    private fun world(offerCount: Int = 1, budget: Long = 10_000): World {
        val id = token()
        val trigger = "trigger_$id"
        val barcode = barcode()
        products.saveAndFlush(ProductEntity(id = trigger, name = "Synthetic trigger $id", barcode = barcode, price = 1000))
        val offers = (1..offerCount).map { index ->
            val product = "offer_${index}_$id"
            val offerCode = barcode()
            products.saveAndFlush(ProductEntity(id = product, name = "Synthetic offer $product", barcode = offerCode, price = 1000))
            val promo = "promo_${index}_$id"
            promos.saveAndFlush(PromoEntity(id = promo, title = "Synthetic funded offer", brand = "TEST", budget = budget,
                medusaProductId = product, productName = "Synthetic offer", tiers = listOf(PromoTier(1, 1000, 200))).also { it.status = PromoStatus.active })
            rules.saveAndFlush(RuleEntity(id = "rule_${index}_$id", trigger = RuleTrigger("product", trigger), recommend = product,
                bonus = 200, promoId = promo).also { it.status = RuleStatus.active })
            Offer(product, offerCode, promo)
        }
        return World(trigger, barcode, offers)
    }

    private data class SourceLogin(val employee: Employee,val iin: String,val token: String,val context: kz.epharm.posm.staff.StaffDeviceContext)
    private fun validIin():String {
        while(true) {
            val prefix=sequence.incrementAndGet().toString().takeLast(11)
            (0..9).map { prefix+it }.firstOrNull(kz.epharm.shared.IinUtil::isValid)?.let { return it }
        }
    }
    private fun sourceLogin(base:Employee=employee(mapped=false),status:String="available",name:String="Сабиров Амир",
        sourceIin:String?=null,attachSource:Boolean=true):SourceLogin {
        val iin=validIin()
        jdbc.update("UPDATE pharmacists SET iin=?,name='Сабиров Амир',password_hash=? WHERE id=?",iin,encoder.encode("A1!b"),base.person)
        val source=kz.epharm.posm.staff.StaffContext(base.rawUser,"901",base.device,"process_${base.person}",
            if(status=="available") sourceIin ?: iin else null,status,name,"available",sha("synthetic-database:${base.pharmacy}"))
        val context=kz.epharm.posm.staff.StaffDeviceContext(source.processInstanceId,source)
        val body=kz.epharm.posm.staff.StaffDeviceLoginRequest(iin,"A1!b",if(attachSource)context else context.copy(standardNContext=null))
        val response=postDevice("/api/posm/staff/device/login",base,body)
        return SourceLogin(base,iin,response["sessionToken"].asText(),context)
    }
    private fun checkSource(login:SourceLogin) {
        mvc.perform(post("/api/posm/staff/device/session/check").header("X-Posm-Key",login.employee.key)
            .header("X-Staff-Session",login.token).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(login.context)))
            .andExpect(status().isOk)
    }
    private fun sourceFlow(login:SourceLogin):Flow = flow(employee=login.employee,
        sourceNamespace=login.context.standardNContext!!.sourceNamespace).let { f -> f.copy(shownAt=Instant.now().truncatedTo(ChronoUnit.MILLIS),
            identity=f.identity.copy(nativeSessionId=901)) }

    private fun flow(world: World = world(), employee: Employee = employee(), quantities: List<Long> = listOf(2),
        preexisting: Boolean = false, reverse: Boolean = false, sourceNamespace: String? = null): Flow {
        require(quantities.size == world.offers.size)
        val id = token()
        val session = "checkout_$id"
        val body = RecommendRequest(employee.rawUser, pharmacyId = employee.pharmacy, sessionId = session,
            scannedBarcode = world.triggerBarcode, cart = listOf(CartItemDto(barcode = world.triggerBarcode, qty = 1.0)), sourceNamespace = sourceNamespace)
        val response = postDevice("/api/posm/recommend", employee, body)
        assertThat(response["rewardEvidenceProtocol"].asInt()).isEqualTo(1)
        val events = response["recommendations"].associate { it["recommendSku"].asText() to it["eventId"].asText() }
        assertThat(events.keys).containsExactlyInAnyOrderElementsOf(world.offers.map { it.id })
        val doc = documents.incrementAndGet()
        val identity = RewardSourceIdentity(sha("synthetic-database:${employee.pharmacy}"), employee.rawUser, doc,
            employee.device, "match")
        return Flow(employee, world, session, "sale_$id", doc, Instant.now().minusSeconds(240).truncatedTo(ChronoUnit.MILLIS),
            identity, events, if (reverse) world.offers.reversed() else world.offers,
            world.offers.mapIndexed { index, offer -> offer.id to quantities[index] }.toMap(), preexisting)
    }

    private fun acknowledge(f: Flow) {
        val baseline = listOf(RewardCartLine(f.doc, 1, barcode = f.world.triggerBarcode, qty = BigDecimal.ONE, unitSemantics = "full_package")) +
            if (f.preexisting) f.world.offers.mapIndexed { index, offer -> RewardCartLine(f.doc, 10L + index, barcode = offer.barcode,
                qty = BigDecimal.ONE, unitSemantics = "full_package") } else emptyList()
        f.eventIds.forEach { (product, event) ->
            postDevice("/api/posm/recommendations/$event/shown", f.employee, MarkShownRequest(f.shownAt,
                RewardShownEvidence(checkoutSessionId = f.session, observationId = "show_${event.takeLast(32)}",
                    identity = f.identity, cartComplete = true, lines = baseline)))
        }
    }

    private fun payment(f: Flow, additionDelay: Duration = Duration.ofSeconds(40), steps: List<Step>? = null): PosSaleRequest {
        val transitions = mutableListOf<RewardLineChange>()
        fun add(at: Instant, line: Long, barcode: String, before: Long, after: Long, generation: String, event: String = "") {
            transitions += RewardLineChange("obs_${f.doc}_${transitions.size + 1}", event, "generation_${f.doc}_$generation",
                transitions.size + 1L, at, f.identity, f.doc, line, barcode = barcode,
                beforeQty = BigDecimal.valueOf(before), afterQty = BigDecimal.valueOf(after), unitSemantics = "full_package")
        }
        add(f.shownAt.minusSeconds(20), 1, f.world.triggerBarcode, 0, 1, "trigger")
        if (f.preexisting) f.world.offers.forEachIndexed { index, offer ->
            add(f.shownAt.minusSeconds(10), 10L + index, offer.barcode, 0, 1, "offer_${index}_original")
        }
        val additions = steps ?: f.order.map { offer -> Step(offer, if (f.preexisting) 1 else 0, f.quantities.getValue(offer.id), additionDelay) }
        additions.forEach { step ->
            val index = f.world.offers.indexOf(step.offer)
            add(f.shownAt.plus(step.delay), 10L + index, step.offer.barcode, step.before, step.after,
                "offer_${index}_${step.generation}", f.eventIds.getValue(step.offer.id))
        }
        add(f.shownAt.plusSeconds(170), 1, f.world.triggerBarcode, 1, 0, "trigger")
        val paidAt = f.shownAt.plusSeconds(180)
        val items = f.order.map { offer ->
            val quantity = f.quantities.getValue(offer.id)
            PosSaleItemDto(barcode = offer.barcode, name = "Synthetic offer ${offer.id}", qty = quantity.toDouble(), price = 1000, total = quantity * 1000)
        }
        val total = items.sumOf { it.total }
        val paidLines = f.order.map { offer ->
            val quantity = f.quantities.getValue(offer.id)
            RewardPaidLine(f.doc, 10L + f.world.offers.indexOf(offer), barcode = offer.barcode, qty = BigDecimal.valueOf(quantity),
                signedQuantity = BigDecimal.valueOf(-quantity), signedPrice = BigDecimal.valueOf(1000),
                signedAmount = BigDecimal.valueOf(-quantity * 1000), unitSemantics = "full_package")
        }
        val complete = RewardCompletionEvidence("paid", "standardn-posted-print", paidAt, f.identity, f.doc, 1, true,
            BigDecimal.valueOf(-total), f.employee.device, f.doc, 1, paidLines)
        return PosSaleRequest(f.sale, f.employee.rawUser, pharmacyId = f.employee.pharmacy, sessionId = f.session,
            sourceDocumentId = f.doc, captureSource = "standardn-print-log", fiscalId = "F-${f.doc}", totalAmount = total,
            items = items, rewardEvidence = RewardSaleEvidence(checkoutSessionId = f.session, journalComplete = true,
                changes = transitions, completion = complete), printedAt = paidAt)
    }

    private fun refund(f: Flow, cashier: Employee): PosSaleRequest {
        val original = payment(f)
        val doc = documents.incrementAndGet()
        val session = "refund_${token()}"
        val at = f.shownAt.plusSeconds(200)
        val identity = f.identity.copy(rawUserId = cashier.rawUser, nativeSessionId = doc, workstationId = cashier.device)
        val completion = original.rewardEvidence!!.completion.let {
            it.copy(status = "refund", source = "standardn-posted-refund", documentType = 9,
                completedAt = at, identity = identity, sourceDocumentId = doc, deviceNumber = cashier.device,
                signedTotal = it.signedTotal.abs(), fiscalNumber = doc,
                lines = it.lines.map { line -> line.copy(sourceDocumentId = doc, signedQuantity = line.signedQuantity.abs(), signedAmount = line.signedAmount.abs()) },
                originalSourceDocumentId = f.doc, originalDeviceNumber = f.employee.device, originalFiscalNumber = f.doc, originalFiscalShift = 1)
        }
        return original.copy(saleId = "refund_${token()}", pharmacistId = cashier.rawUser, sessionId = session,
            sourceDocumentId = doc, captureSource = "standardn-db-refund", fiscalId = "F-$doc", printedAt = at,
            rewardEvidence = RewardSaleEvidence(checkoutSessionId = session, journalComplete = true, completion = completion))
    }

    private fun deliver(f: Flow, request: PosSaleRequest, expectedStatus: Int = 200, employee: Employee = f.employee): JsonNode =
        postDevice("/api/posm/sales", employee, request, expectedStatus)
    private fun postDevice(path: String, employee: Employee, body: Any, expectedStatus: Int = 200): JsonNode {
        val result = mvc.perform(post(path).header("X-Posm-Key", employee.key).contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(body))).andExpect(status().`is`(expectedStatus)).andReturn()
        return mapper.readTree(result.response.contentAsByteArray)
    }
    private fun assertCredit(f: Flow, amount: Long) {
        assertThat(number("SELECT count(*) FROM pos_sales WHERE id=?", f.sale)).isEqualTo(1)
        assertThat(cases(f)).hasSize(f.world.offers.size)
        assertThat(number("SELECT coalesce(sum(r.bonus_credited),0) FROM receipts r JOIN recommendation_sale_rewards w ON w.receipt_id=r.id WHERE w.source_sale_id=?", f.sale)).isEqualTo(amount)
        assertThat(number("""SELECT count(*) FROM pharmacist_wallet_entries e JOIN recommendation_sale_rewards w ON w.pending_bonus_id=e.source_id
            WHERE w.source_sale_id=? AND e.source='receipt_bonus'""", f.sale)).isEqualTo(if (amount > 0) f.world.offers.size.toLong() else 0)
        if (amount > 0) assertThat(cases(f).map { it["state"] }).containsOnly("credited")
    }
    private fun cases(f: Flow) = rows("SELECT * FROM recommendation_sale_rewards WHERE source_sale_id=? ORDER BY product_id", f.sale)
    private fun balance(employee: Employee) = number("SELECT balance FROM pharmacists WHERE id=?", employee.person)
    private fun spent(offer: Offer) = number("SELECT spent FROM promos WHERE id=?", offer.promo)
    private fun number(sql: String, vararg args: Any) = jdbc.queryForObject(sql, Long::class.java, *args)!!
    private fun rows(sql: String, vararg args: Any) = jdbc.queryForList(sql, *args)
    private fun token() = UUID.randomUUID().toString().replace("-", "")
    private fun sha(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun barcode(): String {
        val prefix = sequence.incrementAndGet().toString()
        val check = (10 - prefix.mapIndexed { index, c -> c.digitToInt() * if (index % 2 == 0) 1 else 3 }.sum() % 10) % 10
        return prefix + check
    }
    private val REVIEW_PATH = "/api/admin/reconcile/recommendation-rewards"
    private fun reviewer(pharmacies: Set<String>, approve: Boolean = true, export: Boolean = false): String {
        val account = admins.saveAndFlush(AdminUserEntity(email = "review-${token()}@example.test", name = "Synthetic scoped reviewer",
            passwordHash = "not-used-by-this-test", roleRaw = AdminRole.FINANCE_REVIEWER.name))
        val person = UUID.randomUUID()
        jdbc.update("INSERT INTO unified_persons VALUES (?,?)", person, "synthetic-test-proof")
        jdbc.update("INSERT INTO unified_admin_profiles(account_id,person_id) VALUES (?,?)", account.id, person)
        val actions = listOf("view") + (if (approve) listOf("approve") else emptyList()) + (if (export) listOf("export") else emptyList())
        val grants = actions.map { StoredGrant("motivation", "sales_review", it, pharmacyIds = pharmacies) }
        jdbc.update("UPDATE unified_admin_profiles SET grants_json=?,version=version+1 WHERE account_id=?", mapper.writeValueAsString(grants), account.id)
        jdbc.update("UPDATE admin_users SET access_mode='MANAGED' WHERE id=?", account.id)
        return "Bearer ${jwt.issueAccessToken(account)}"
    }
    private fun decide(id: String, bearer: String, decision: RewardReviewDecision, expectedStatus: Int = 200): JsonNode {
        val response = mvc.perform(post("$REVIEW_PATH/$id/decision").header("Authorization", bearer)
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(decision)))
            .andExpect(status().`is`(expectedStatus)).andReturn().response.contentAsByteArray
        return mapper.readTree(response)
    }
    private fun hq(): String {
        bearer?.let { return it }
        val account = admins.saveAndFlush(AdminUserEntity(email = "reward-${token()}@example.test", name = "Synthetic HQ",
            passwordHash = "not-used-by-this-test", roleRaw = AdminRole.HQ_HEAD.name))
        return "Bearer ${jwt.issueAccessToken(account)}".also { bearer = it }
    }
    private fun <T> parallel(actions: List<() -> T>): List<T> {
        val gate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(actions.size)
        return try {
            val tasks = actions.map { action -> pool.submit(Callable { check(gate.await(10, TimeUnit.SECONDS)); action() }) }
            gate.countDown()
            tasks.map { it.get(30, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
    }
}
