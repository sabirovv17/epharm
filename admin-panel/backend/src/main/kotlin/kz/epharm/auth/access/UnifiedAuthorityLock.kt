package kz.epharm.auth.access

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * Acquire before actor/dispatch/phone/recipient locks: reviewers can also be each other's mapping auditors.
 * Only administrative writes and merch recipient writes use this gate; POSM scans, sales and reads do not.
 */
object UnifiedAuthorityLock {
    fun acquire(jdbc: JdbcTemplate) {
        check(TransactionSynchronizationManager.isActualTransactionActive()) { "Authority lock needs a transaction" }
        check(!TransactionSynchronizationManager.isCurrentTransactionReadOnly()) { "Authority lock needs a write transaction" }
        jdbc.queryForList("SELECT pg_advisory_xact_lock(817109,1)")
    }
}
