package kz.epharm.posm.service

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kz.epharm.fulfillment.security.FulfillmentCrypto
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

data class PosmDeviceIdentity(
    val deviceId: String?,
    val pharmacyId: String?,
    val legacy: Boolean,
)

/**
 * One authentication boundary for every /api/posm endpoint. Per-device secrets are stored only as
 * SHA-256 in fulfillment_devices (the historical table name is retained for migration safety).
 * The fleet key can be kept for a controlled migration window and is disabled in production config.
 */
@Service
class PosmDeviceAuthenticationService(
    private val jdbc: JdbcTemplate,
    private val crypto: FulfillmentCrypto,
    @Value("\${app.posm.device-key:dev-posm-key}") private val legacyDeviceKey: String,
    @Value("\${app.posm.legacy-device-key-enabled:true}") private val legacyEnabled: Boolean,
) {
    fun authenticate(
        token: String?,
        claimedPharmacyId: String? = null,
        claimedDeviceId: String? = null,
        touchLastSeen: Boolean = false,
    ): PosmDeviceIdentity {
        val normalized = token?.trim()?.takeIf { it.isNotEmpty() }
            ?: unauthorized()
        if (normalized.length in 32..256) {
            val matches = jdbc.query(
                """
                SELECT d.device_id, d.pharmacy_id
                FROM fulfillment_devices d
                JOIN pharmacies p ON p.id = d.pharmacy_id AND p.active = true
                WHERE d.secret_sha256 = ? AND d.active = true
                """.trimIndent(),
                { rs, _ -> PosmDeviceIdentity(rs.getString(1), rs.getString(2), legacy = false) },
                crypto.sha256Hex(normalized),
            )
            val identity = matches.singleOrNull()
            if (identity != null) {
                if (!claimedPharmacyId.isNullOrBlank() && identity.pharmacyId != claimedPharmacyId.trim()) {
                    unauthorized()
                }
                if (!claimedDeviceId.isNullOrBlank() &&
                    !identity.deviceId.equals(claimedDeviceId.trim(), ignoreCase = true)
                ) {
                    unauthorized()
                }
                if (touchLastSeen) {
                    jdbc.update(
                        "UPDATE fulfillment_devices SET last_seen_at = now() " +
                            "WHERE device_id = ? AND pharmacy_id = ? AND active = true",
                        identity.deviceId,
                        identity.pharmacyId,
                    )
                }
                return identity
            }
        }

        if (legacyEnabled && constantTimeEquals(normalized, legacyDeviceKey)) {
            return PosmDeviceIdentity(
                deviceId = claimedDeviceId?.trim()?.takeIf { it.isNotEmpty() },
                pharmacyId = claimedPharmacyId?.trim()?.takeIf { it.isNotEmpty() },
                legacy = true,
            )
        }
        unauthorized()
    }

    fun requireLegacyBootstrap(token: String?) {
        if (!legacyEnabled || token.isNullOrBlank() || !constantTimeEquals(token, legacyDeviceKey)) {
            unauthorized()
        }
    }

    private fun constantTimeEquals(left: String, right: String): Boolean = MessageDigest.isEqual(
        left.toByteArray(StandardCharsets.UTF_8),
        right.toByteArray(StandardCharsets.UTF_8),
    )

    private fun unauthorized(): Nothing = throw AppException(
        ErrorCode.UNAUTHORIZED,
        "Invalid, revoked or missing POSM device credential",
        HttpStatus.UNAUTHORIZED,
    )
}
