package kz.epharm.finance.merch

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kz.epharm.shared.error.AppException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class MerchRewardSecurityTest {
    private val json = jacksonObjectMapper()
    private val now = Instant.ofEpochSecond(2_000_000_000)
    private val secret = "integration-contract-test-secret-0001"
    private val security = MerchRewardSecurity(json, true, secret, false)
    private fun mac(value: String) = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")); doFinal(value.toByteArray())
    }
    private fun claim(changes: Map<String, Any> = emptyMap()): String {
        val values = mapOf("iss" to "crm.inkar.kz", "aud" to "epharm", "dispatchId" to UUID.randomUUID().toString(),
            "pharmacyId" to "sloc_test", "nonce" to Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32)),
            "iat" to now.epochSecond, "exp" to now.epochSecond + 600, "taskDueAt" to now.epochSecond + 3600,
            "taskTitle" to "Фото полки", "rewardKzt" to 500) + changes
        val b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(values))
        return "v1.$b64.${Base64.getUrlEncoder().withoutPadding().encodeToString(mac("epharm.merch.claim.v1\n$b64"))}"
    }

    @Test fun `trusted Unicode context is decoded only after signature verification`() {
        val context = security.challenge(claim(), now)
        assertThat(context.taskTitle).isEqualTo("Фото полки")
        assertThat(context.rewardKzt).isEqualTo(500)
    }
    @Test fun `bad signature issuer scope deadline and amount fail closed`() {
        for (change in listOf(mapOf("iss" to "evil.example"), mapOf("aud" to "crm"), mapOf("exp" to now.epochSecond),
            mapOf("exp" to now.epochSecond+601), mapOf("iat" to now.epochSecond+31), mapOf("rewardKzt" to 0),
            mapOf("rewardKzt" to 1.1), mapOf("pharmacyId" to "../other"), mapOf("nonce" to "short"),
            mapOf("taskDueAt" to now.epochSecond+100), mapOf("dispatchId" to "1-1-1-1-1"))) {
            assertThatThrownBy { security.challenge(claim(change), now) }.isInstanceOf(AppException::class.java)
        }
        val token = claim()
        assertThatThrownBy { security.challenge(token.dropLast(5)+"wrong", now) }.isInstanceOf(AppException::class.java)
    }
    @Test fun `financial signing interoperates with Python published vector`() {
        security.verifyRequest("2000000000", "655ec55f8c5071166686d5f87d5fd68c1e82004fad8f2685f15baaa64e4cf28d",
            "POST", "/api/integrations/merch/reward-events", "{}".toByteArray(), now)
    }
    @Test fun `Python Unicode challenge vector decodes without JSON reserialization`() {
        val token = "v1.eyJhdWQiOiJlcGhhcm0iLCJkaXNwYXRjaElkIjoiMTExMTExMTEtMTExMS00MTExLTgxMTEtMTExMTExMTExMTExIiwiZXhwIjoyMDAwMDAwNjAwLCJpYXQiOjIwMDAwMDAwMDAsImlzcyI6ImNybS5pbmthci5reiIsIm5vbmNlIjoibm5ubm5ubm5ubm5ubm5ubm5ubm5ubm5ubm5ubm5ubm5ubm5ubm5ubm5ubiIsInBoYXJtYWN5SWQiOiJzbG9jX2NvbnRyYWN0IiwicmV3YXJkS3p0Ijo1MDAsInRhc2tEdWVBdCI6MjAwMDA4NjQwMCwidGFza1RpdGxlIjoi0KTQvtGC0L4g0L_QvtC70LrQuCJ9.FjcCIb_LeHFVllq1LPtIYz9U0GcJ-inNdH2A1R529Cc"
        assertThat(security.challenge(token, now).taskTitle).isEqualTo("Фото полки")
    }
    @Test fun `signed requests bind timestamp body exact method path and size`() {
        val signature = "655ec55f8c5071166686d5f87d5fd68c1e82004fad8f2685f15baaa64e4cf28d"
        for (attempt in listOf(
            { security.verifyRequest("2000000000", signature, "POST", "/api/integrations/merch/reward-events", "{ }".toByteArray(), now) },
            { security.verifyRequest("2000000000", signature, "GET", "/api/integrations/merch/reward-events", "{}".toByteArray(), now) },
            { security.verifyRequest("2000000000", signature, "POST", "/api/integrations/merch/reward-events?x=1", "{}".toByteArray(), now) },
            { security.verifyRequest("2000000000", signature, "POST", "/api/integrations/merch/reward-events", "{}".toByteArray(), now.plusSeconds(301)) },
            { security.verifyRequest("2000000000", signature, "POST", "/api/integrations/merch/reward-events", ByteArray(16385), now) },
        )) assertThatThrownBy { attempt() }.isInstanceOf(AppException::class.java)
    }
    @Test fun `disabled mock OTP or short key cannot expose financial flow`() {
        for (instance in listOf(MerchRewardSecurity(json, false, secret, false), MerchRewardSecurity(json, true, "short", false),
            MerchRewardSecurity(json, true, secret, true))) {
            assertThatThrownBy { instance.challenge(claim(), now) }.isInstanceOf(AppException::class.java)
        }
    }
}
