package kz.epharm.posm.service

/** Non-personal observation health, independent of login and never identity proof. */
object StaffSourceStatus {
    private val allowed = setOf(
        "initializing", "unknown", "local_disabled", "service_disabled", "resolved",
        "no_process", "process_unavailable", "ambiguous_process", "process_changing",
        "database_disabled", "database_unavailable", "no_session", "ambiguous_session",
        "unknown_workstation", "unsupported_session_time", "future_session_time", "stale_session",
        "invalid_session_time", "unsupported_process_time", "session_predates_process",
    )

    fun normalize(value: String?): String? = value?.takeIf { it in allowed }
}
