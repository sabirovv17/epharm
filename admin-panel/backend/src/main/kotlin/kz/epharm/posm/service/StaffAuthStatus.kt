package kz.epharm.posm.service

/** Fixed, non-personal health codes; never accept free text from a cash desk. */
object StaffAuthStatus {
    private val allowed = setOf(
        "initializing", "local_disabled", "device_auth_required", "forbidden", "server_disabled",
        "unsupported_protocol", "endpoint_unavailable", "network_unavailable", "enabled",
        "no_process", "process_unavailable", "ambiguous_process", "process_changing",
        "database_disabled", "database_unavailable", "no_session", "ambiguous_session",
        "unknown_workstation", "unsupported_session_time", "future_session_time", "stale_session",
        "invalid_session_time", "unsupported_process_time", "session_predates_process",
        "login_required", "login_visible", "authenticated",
    )

    fun normalize(value: String?): String? = value?.takeIf { it in allowed }
}
