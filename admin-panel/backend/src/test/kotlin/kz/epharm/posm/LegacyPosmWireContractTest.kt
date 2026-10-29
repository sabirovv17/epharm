package kz.epharm.posm

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kz.epharm.appupdate.dto.AppVersionDto
import kz.epharm.appupdate.service.AppReleaseService
import kz.epharm.cdp.service.CdpService
import kz.epharm.fulfillment.security.FulfillmentCrypto
import kz.epharm.merchtasks.dto.MerchTaskDeliveryResult
import kz.epharm.merchtasks.dto.MerchTaskEnvelope
import kz.epharm.merchtasks.dto.MerchTaskShownRequest
import kz.epharm.merchtasks.service.MerchTaskClient
import kz.epharm.posm.controller.PosmController
import kz.epharm.posm.dto.MarkShownRequest
import kz.epharm.posm.dto.OutcomeRequest
import kz.epharm.posm.dto.PosSaleRequest
import kz.epharm.posm.dto.RecommendRequest
import kz.epharm.posm.dto.RecommendResponse
import kz.epharm.posm.service.DevicePresenceService
import kz.epharm.posm.service.PharmacistIdentitySource
import kz.epharm.posm.service.PosSaleService
import kz.epharm.posm.service.PosmDeviceAuthenticationService
import kz.epharm.posm.service.PosmDeviceIdentity
import kz.epharm.posm.service.PosmPharmacistIdentity
import kz.epharm.posm.service.PosmPharmacistIdentityService
import kz.epharm.posm.service.RecommendationService
import kz.epharm.screens.dto.ActivePlaylistDto
import kz.epharm.screens.service.ScreenService
import kz.epharm.shared.error.GlobalExceptionHandler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.json.JsonTest
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * One shared golden set is exercised by historical C# clients and these current Kotlin bindings.
 * The Spring JSON slice, real controller/query binding and Bean Validation run without a database.
 * Business services/device identity are mocked except the explicit retired-key test below.
 * This does not execute Windows, WPF, TLS, real device keys, attribution or a recommendation popup.
 */
@JsonTest
@ActiveProfiles("test")
class LegacyPosmWireContractTest {
    @Autowired private lateinit var mapper: ObjectMapper
    private lateinit var validator: LocalValidatorFactoryBean
    private lateinit var mvc: MockMvc
    private lateinit var recommendations: RecommendationService
    private lateinit var sales: PosSaleService
    private lateinit var devices: PosmDeviceAuthenticationService
    private lateinit var identity: PosmPharmacistIdentityService
    private lateinit var releases: AppReleaseService
    private lateinit var tasks: MerchTaskClient
    private lateinit var presence: DevicePresenceService

    companion object {
        private const val PHARMACY = "pharmacy-synthetic-1"
        private const val DEVICE = "SYNTHETIC-POS-01"
        private val sourceCommits = mapOf(
            "V1057" to "0d2bb3ba2291ecb0ee10aaf39268e713f45af61c",
            "V1066" to "84d50b29871de7a7ce460376f05c474c74de06ac",
        )
        // Do not copy these JSON files: the historical .NET tests own this same wire contract.
        private val fixtures: Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .map { it.resolve("App.Tests/Fixtures/LegacyPosm") }
            .firstOrNull { Files.isDirectory(it) }
            ?: error("Shared App.Tests/Fixtures/LegacyPosm fixtures are required in this checkout")
    }

    @BeforeEach
    fun setup() {
        validator = LocalValidatorFactoryBean().apply { afterPropertiesSet() }
        recommendations = mockk()
        sales = mockk()
        devices = mockk()
        identity = mockk()
        releases = mockk()
        tasks = mockk()
        presence = DevicePresenceService(ttlSeconds = 90)
        every { devices.authenticate(any(), any(), any(), any()) } returns
            PosmDeviceIdentity(DEVICE, PHARMACY, legacy = false)
        every { devices.authenticateForUpdate(any(), any()) } returns
            PosmDeviceIdentity(DEVICE, PHARMACY, legacy = false)
        every { identity.resolve(any(), any(), any()) } answers {
            PosmPharmacistIdentity(
                secondArg<String?>().orEmpty(), thirdArg(), PharmacistIdentitySource.STANDARDN_UNMAPPED,
                secondArg(), thirdArg(),
            )
        }
        mvc = mvc(devices)
    }

    @AfterEach
    fun closeValidator() = validator.close()

    private fun mvc(authentication: PosmDeviceAuthenticationService): MockMvc = MockMvcBuilders
        .standaloneSetup(
            PosmController(
                recommendations, sales, mockk<ScreenService>(), mockk<CdpService>(), releases,
                presence, identity, authentication, tasks,
            ),
        )
        .setControllerAdvice(GlobalExceptionHandler())
        .setMessageConverters(MappingJackson2HttpMessageConverter(mapper))
        .setValidator(validator)
        .build()

    private fun fixture(version: String): JsonNode = mapper.readTree(fixtures.resolve("$version/wire.json").toFile())
    private fun step(version: String, name: String): JsonNode = fixture(version)["steps"].single { it["name"].asText() == name }
    private fun body(version: String, name: String): ObjectNode = step(version, name)["body"].deepCopy<ObjectNode>()
    private inline fun <reified T> bind(value: JsonNode): T = mapper.treeToValue(value, T::class.java)
    private fun fields(bean: Any): Set<String> = validator.validate(bean).map { it.propertyPath.toString() }.toSet()

    private fun wireRequest(step: JsonNode, body: JsonNode = step["body"], key: String? = null): MockHttpServletRequestBuilder {
        val builder = request(HttpMethod.valueOf(step["method"].asText()), URI.create(step["pathAndQuery"].asText()))
        step["headers"].fields().forEach { (name, value) ->
            builder.header(name, if (name == "X-Posm-Key" && key != null) key else value.asText())
        }
        if (!body.isNull) builder.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body))
        return builder
    }

    @ParameterizedTest
    @ValueSource(strings = ["V1057", "V1066"])
    fun `shared fixtures identify historical source and contain only synthetic credentials`(version: String) {
        val wire = fixture(version)
        val provenance = mapper.readTree(fixtures.resolve("$version/provenance.json").toFile())
        assertThat(wire["sourceCommit"].asText()).isEqualTo(sourceCommits.getValue(version))
        assertThat(provenance["mainVersionEntryCommit"].asText()).isEqualTo(wire["sourceCommit"].asText())
        assertThat(wire["canonicalPublicOrigin"].asText()).isEqualTo("https://epharm.inkar.kz")
        assertThat(wire["syntheticOnly"].asBoolean()).isTrue()
        assertThat(provenance["historicalConfigCopied"].asBoolean()).isFalse()
        assertThat(provenance["installedBinaryCommitVerified"].asBoolean()).isFalse()
        wire["steps"].forEach { item ->
            item["headers"].fields().forEach { (name, value) ->
                if (name in setOf("X-Posm-Key", "X-Fulfillment-Device")) {
                    assertThat(value.asText()).startsWith("synthetic-")
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["V1057", "V1066"])
    fun `historical recommend body reaches current DTO and additive fields are ignored`(version: String) {
        val captured = slot<RecommendRequest>()
        every { recommendations.recommend(capture(captured)) } returns RecommendResponse("session-synthetic-1", emptyList())
        val request = step(version, "recommend")
        mvc.perform(wireRequest(request)).andExpect(status().isOk)
        assertThat(captured.captured.pharmacyId).isEqualTo(PHARMACY)
        assertThat(captured.captured.sessionId).isEqualTo("session-synthetic-1")
        assertThat(captured.captured.cart.single().barcode).isEqualTo("0000000000000")
        assertThat(captured.captured.cart.single().qty).isEqualTo(1.0)
        val extended = body(version, "recommend").put("futureDiagnostic", "synthetic")
        (extended.withArray("cart")[0] as ObjectNode).put("futureItemField", true)
        mvc.perform(wireRequest(request, extended)).andExpect(status().isOk)
        assertThat(captured.captured.cart.single().sku).isEqualTo("synthetic-ipart-1")
    }

    @ParameterizedTest
    @ValueSource(strings = ["V1057", "V1066"])
    fun `recommend keeps optional identities aliases quantities and empty cart semantics`(version: String) {
        val payload = body(version, "recommend")
        payload.remove(listOf("pharmacistId", "pharmacistName", "scannedBarcode"))
        val item = payload.withArray("cart")[0] as ObjectNode
        item.remove(listOf("qty", "barcode", "name"))
        val request = bind<RecommendRequest>(payload)
        assertThat(request.pharmacistId).isEmpty()
        assertThat(request.pharmacistName).isNull()
        assertThat(request.scannedBarcode).isNull()
        assertThat(request.cart.single().qty).isEqualTo(1.0)
        assertThat(request.cart.single().sku).isEqualTo("synthetic-ipart-1")
        assertThat(fields(request)).isEmpty()
        payload.remove("cart")
        assertThat(bind<RecommendRequest>(payload).cart).isEmpty() // No invented NotEmpty requirement.
        payload.putNull("cart")
        assertThrows<JsonProcessingException> { bind<RecommendRequest>(payload) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["V1057", "V1066"])
    fun `invalid recommend identity and null required fields fail before business services`(version: String) {
        val blank = body(version, "recommend").put("pharmacyId", " ").put("sessionId", "")
        assertThat(fields(bind<RecommendRequest>(blank))).containsExactlyInAnyOrder("pharmacyId", "sessionId")
        mvc.perform(wireRequest(step(version, "recommend"), blank)).andExpect(status().isBadRequest)
        val missing = body(version, "recommend").apply { remove("pharmacyId") }
        mvc.perform(wireRequest(step(version, "recommend"), missing)).andExpect(status().isBadRequest)
        verify(exactly = 0) { recommendations.recommend(any()) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["V1057", "V1066"])
    fun `historical completed sale binds quantities instant and nullable fiscal metadata`(version: String) {
        val captured = slot<PosSaleRequest>()
        every { sales.record(capture(captured), any()) } returns true
        mvc.perform(wireRequest(step(version, "sale")))
            .andExpect(status().isOk).andExpect(jsonPath("$.accepted").value(true))
        val sale = captured.captured
        assertThat(sale.saleId).isEqualTo("sale-synthetic-1")
        assertThat(sale.sourceDocumentId).isEqualTo(101L)
        assertThat(sale.printedAt).isEqualTo(Instant.parse("2026-10-29T00:00:00Z"))
        assertThat(sale.items.single().qty).isEqualTo(1.0)
        assertThat(sale.items.single().price).isEqualTo(900L)
        assertThat(sale.artifactSha256).isNull()
        assertThat(sale.fiscalId).isNull()
        assertThat(sale.fiscalSign).isNull()
        assertThat(sale.cashRegisterRegistrationNumber).isNull()
        assertThat(sale.ofdName).isNull()
        assertThat(fields(sale)).isEmpty()
    }

    @ParameterizedTest
    @ValueSource(strings = ["V1057", "V1066"])
    fun `sale decimals long money timezone and omitted optional fields retain wire meaning`(version: String) {
        val payload = body(version, "sale")
        payload.remove(listOf("pharmacistId", "pharmacistName", "sessionId", "sourceDocumentId", "captureSource",
            "artifactFormat", "artifactSha256", "artifactSource", "fiscalId", "fiscalSign", "cashRegisterRegistrationNumber", "ofdName"))
        payload.put("printedAt", "2026-10-29T06:00:00.125+06:00").put("futureField", true)
        val item = payload.withArray("items")[0] as ObjectNode
        item.put("qty", 0.125).put("price", 9_007_199_254_740_993L).put("total", 9_007_199_254_740_993L)
        payload.put("totalAmount", 9_007_199_254_740_993L)
        val sale = bind<PosSaleRequest>(payload)
        assertThat(sale.items.single().qty).isEqualTo(0.125)
        assertThat(sale.items.single().price).isEqualTo(9_007_199_254_740_993L)
        assertThat(sale.totalAmount).isEqualTo(9_007_199_254_740_993L)
        assertThat(sale.printedAt).isEqualTo(Instant.parse("2026-10-29T00:00:00.125Z"))
        assertThat(sale.pharmacistId).isEmpty()
        assertThat(sale.sourceDocumentId).isNull()
        assertThat(sale.fiscalId).isNull()
        assertThat(fields(sale)).isEmpty()
        item.remove("qty")
        assertThat(bind<PosSaleRequest>(payload).items.single().qty).isEqualTo(1.0)
    }

    @ParameterizedTest
    @ValueSource(strings = ["V1057", "V1066"])
    fun `invalid sale timestamp number or hash is not acknowledged as accepted`(version: String) {
        val step = step(version, "sale")
        val missing = body(version, "sale").apply { remove("printedAt") }
        val nullTime = body(version, "sale").putNull("printedAt")
        val badTime = body(version, "sale").put("printedAt", "not-an-instant")
        val badHash = body(version, "sale").put("artifactSha256", "not-a-sha256")
        val badMoney = body(version, "sale").apply { (withArray("items")[0] as ObjectNode).put("price", "not-a-price") }
        listOf(missing, nullTime, badTime, badHash, badMoney).forEach { payload ->
            mvc.perform(wireRequest(step, payload)).andExpect(status().isBadRequest)
        }
        verify(exactly = 0) { sales.record(any(), any()) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["V1057", "V1066"])
    fun `outcome strings remain validated rather than silently accepting unknown enum values`(version: String) {
        assertThat(fields(bind<OutcomeRequest>(step(version, "outcome")["body"]))).isEmpty()
        val payload = body(version, "outcome").put("outcome", "future-approval")
        assertThat(fields(bind<OutcomeRequest>(payload))).containsExactly("outcome")
        mvc.perform(wireRequest(step(version, "outcome"), payload)).andExpect(status().isBadRequest)
        assertThat(bind<MarkShownRequest>(step(version, "shown")["body"]).shownAt)
            .isEqualTo(Instant.parse("2026-10-29T00:00:00Z"))
        mvc.perform(wireRequest(step(version, "shown"), body(version, "shown").putNull("shownAt")))
            .andExpect(status().isBadRequest)
        verify(exactly = 0) { recommendations.recordOutcome(any(), any(), any()) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["V1057", "V1066"])
    fun `heartbeat query binds historical fields and omitted newer diagnostics never gate presence`(version: String) {
        val heartbeat = step(version, "heartbeat")
        mvc.perform(wireRequest(heartbeat).param("futureDiagnostic", "ignored"))
            .andExpect(status().isOk).andExpect(jsonPath("$.deviceId").value(DEVICE))
        val connected = presence.connected().single()
        assertThat(connected.monitorCount).isEqualTo(2)
        assertThat(connected.appVersion).isEqualTo(fixture(version)["clientVersion"].asText())
        presence.heartbeat(DEVICE, PHARMACY, staffAuthStatus = "authenticated")
        val key = heartbeat["headers"]["X-Posm-Key"].asText()
        mvc.perform(post("/api/posm/heartbeat").header("X-Posm-Key", key)
            .param("deviceId", DEVICE).param("pharmacyId", PHARMACY))
            .andExpect(status().isOk)
        assertThat(presence.connected().single().staffAuthStatus).isNull()
        assertThat(presence.connected().single().monitorCount).isEqualTo(2)
        mvc.perform(post("/api/posm/heartbeat").header("X-Posm-Key", key)
            .param("monitorCount", "99").param("staffAuthStatus", "unknown-future-status"))
            .andExpect(status().isOk)
        assertThat(presence.connected().single().staffAuthStatus).isNull()
        mvc.perform(post("/api/posm/heartbeat").header("X-Posm-Key", key).param("monitorCount", "two"))
            .andExpect(status().isBadRequest)
    }

    @ParameterizedTest
    @ValueSource(strings = ["V1057", "V1066"])
    fun `task polling accepts the historical optional device header and ACK binds exact capability`(version: String) {
        val taskStep = step(version, "task")
        every { tasks.activeTask(PHARMACY) } returns bind(taskStep["response"])
        mvc.perform(wireRequest(taskStep)).andExpect(status().isOk)
            .andExpect(jsonPath("$.task.id").value("11111111-2222-4333-8444-555555555555"))
        val suppliedDevice = taskStep["headers"]["X-Device-Id"]?.asText()
        assertThat(suppliedDevice).isEqualTo(if (version == "V1057") null else DEVICE)
        verify { devices.authenticate(any(), PHARMACY, suppliedDevice, false) }
        val ack = slot<MerchTaskShownRequest>()
        every { tasks.markShown(capture(ack)) } returns MerchTaskDeliveryResult(accepted = true)
        val payload = body(version, "taskShown").put("futureField", "ignored")
        mvc.perform(wireRequest(step(version, "taskShown"), payload))
            .andExpect(status().isOk).andExpect(jsonPath("$.accepted").value(true))
        assertThat(ack.captured.deliveryToken).isEqualTo(payload["deliveryToken"].asText())
        assertThat(ack.captured.deviceId).isEqualTo(DEVICE)
        assertThat(fields(ack.captured)).isEmpty()
    }

    @ParameterizedTest
    @ValueSource(strings = ["V1057", "V1066"])
    fun `incomplete or unsafe shown ACK never reaches delivery service`(version: String) {
        val noToken = body(version, "taskShown").apply { remove("deliveryToken") }
        val blankId = body(version, "taskShown").put("dispatchId", "")
        val unsafeToken = body(version, "taskShown").put("deliveryToken", "https://not-a-capability")
        listOf(noToken, blankId, unsafeToken).forEach { payload ->
            mvc.perform(wireRequest(step(version, "taskShown"), payload)).andExpect(status().isBadRequest)
        }
        verify(exactly = 0) { tasks.markShown(any()) }
        mvc.perform(get("/api/posm/tasks")).andExpect(status().isBadRequest) // pharmacyId remains required.
    }

    @ParameterizedTest
    @ValueSource(strings = ["V1057", "V1066"])
    fun `task envelopes preserve absent additive fields nullable dates and explicit unavailable state`(version: String) {
        val wire = fixture(version)
        val envelope = bind<MerchTaskEnvelope>(step(version, "task")["response"])
        assertThat(envelope.task!!.dueAt).isEqualTo(Instant.parse("2100-01-01T00:00:00Z"))
        val extended = step(version, "task")["response"].deepCopy<ObjectNode>()
        extended.remove("available")
        extended.put("futureEnvelopeField", true)
        (extended["task"] as ObjectNode).put("priority", "future-priority").putNull("dueAt").put("futureTaskField", 1)
        val compatible = bind<MerchTaskEnvelope>(extended)
        assertThat(compatible.available).isTrue()
        assertThat(compatible.task!!.dueAt).isNull()
        assertThat(compatible.task.priority).isEqualTo("future-priority")
        assertThat(bind<MerchTaskEnvelope>(mapper.readTree("{}")).task).isNull()
        assertThat(bind<MerchTaskEnvelope>(wire["taskUnavailable"]).available).isFalse()
        val unavailableAck = bind<MerchTaskDeliveryResult>(wire["taskShownUnavailable"])
        assertThat(unavailableAck.accepted).isFalse()
        assertThat(unavailableAck.available).isFalse()
        assertThat(bind<MerchTaskDeliveryResult>(mapper.readTree("{}")).accepted).isFalse()
        val oldAck = bind<MerchTaskDeliveryResult>(mapper.readTree("""{"accepted":true,"futureAckField":1}"""))
        assertThat(oldAck.accepted).isTrue()
        assertThat(oldAck.available).isTrue()
    }

    @ParameterizedTest
    @ValueSource(strings = ["V1057", "V1066"])
    fun `current reply serialization preserves legacy fields and explicit no-update response`(version: String) {
        val update = bind<AppVersionDto>(step(version, "version")["response"])
        every { releases.currentFor("win-x64") } returns update
        mvc.perform(wireRequest(step(version, "version"))).andExpect(status().isOk)
            .andExpect(jsonPath("$.manifestSignature").value(update.manifestSignature))
        verify { devices.authenticateForUpdate(any(), DEVICE) }
        val noUpdate = mapper.valueToTree<JsonNode>(AppVersionDto.none())
        assertThat(noUpdate.fieldNames().asSequence().toSet()).containsExactlyInAnyOrderElementsOf(
            step(version, "version")["response"].fieldNames().asSequence().toSet(),
        )
        assertThat(noUpdate["current"].asBoolean()).isFalse()
        assertThat(noUpdate["manifestSignature"].asText()).isEmpty()
        val response = step(version, "recommend")["response"].deepCopy<ObjectNode>()
        val offered = response.withArray("recommendations")[0] as ObjectNode
        offered.remove(listOf("source", "balance"))
        val recommendation = bind<RecommendResponse>(response).recommendations.single()
        assertThat(recommendation.source).isEqualTo("epharm")
        assertThat(recommendation.balance).isNull() // Unknown stock must not become zero stock.
        val playlist = bind<ActivePlaylistDto>(step(version, "playlist")["response"])
        assertThat(mapper.valueToTree<JsonNode>(playlist)["slides"][0]["kind"].asText()).isEqualTo("video")
        val unknownKind = step(version, "playlist")["response"].deepCopy<ObjectNode>()
        (unknownKind.withArray("slides")[0] as ObjectNode).put("kind", "future-slide-kind")
        assertThrows<JsonProcessingException> { bind<ActivePlaylistDto>(unknownKind) }
    }

    @Test
    fun `retired fleet key remains read-only for update and cannot authorize core traffic`() {
        val jdbc = mockk<JdbcTemplate>()
        val fleetKey = "synthetic-retired-fleet-key"
        val authentication = PosmDeviceAuthenticationService(
            jdbc, FulfillmentCrypto("synthetic-test-secret", enabled = false, signatureSkewSeconds = 300),
            fleetKey, legacyEnabled = false,
        )
        val protectedMvc = mvc(authentication)
        every { releases.currentFor("win-x64") } returns AppVersionDto.none()
        protectedMvc.perform(wireRequest(step("V1057", "version"), key = fleetKey))
            .andExpect(status().isOk).andExpect(jsonPath("$.current").value(false))
        listOf("recommend", "sale", "heartbeat").forEach { name ->
            protectedMvc.perform(wireRequest(step("V1057", name), key = fleetKey))
                .andExpect(status().isUnauthorized)
        }
        verify(exactly = 0) { recommendations.recommend(any()) }
        verify(exactly = 0) { sales.record(any(), any()) }
        verify { jdbc wasNot Called }
    }
}
