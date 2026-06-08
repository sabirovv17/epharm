package kz.epharm.mobile.auth.security

/**
 * Principal фармацевта в SecurityContext после JwtAuthenticationFilter (typ=pharmacist).
 * Аналог AdminPrincipal, но id — строковый (pharmacists.id = VARCHAR(64)).
 */
data class PharmacistPrincipal(
    val pharmacistId: String,
    val name: String,
    val phone: String,
)
