package kz.epharm.pharmacists

import io.mockk.*
import kz.epharm.pharmacists.entity.StandardNPharmacistEntity
import kz.epharm.pharmacists.repository.StandardNPharmacistRepository
import kz.epharm.pharmacists.service.StandardNPharmacistSyncService
import kz.epharm.pharmacists.service.StandardNDirectoryConnectionFactory
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Statement
import java.util.Properties

/** Exercises the real scheduled self-call with an explicit Spring transaction and fake source JDBC. */
class StandardNPharmacistSyncTest {
    private val repository = mockk<StandardNPharmacistRepository>()
    private val tx = RecordingTransactions()
    private val connection = mockk<Connection>(relaxed = true)
    private val statement = mockk<Statement>(relaxed = true)
    private val source = mockk<ResultSet>(relaxed = true)
    private val connections = mockk<StandardNDirectoryConnectionFactory>()
    private val service = StandardNPharmacistSyncService(repository, tx, connections, true, "synthetic", 3050, "synthetic.fdb", "synthetic", "synthetic")

    @BeforeEach fun source() {
        every { connections.open("jdbc:firebirdsql://synthetic:3050/synthetic.fdb", any<Properties>()) } answers {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "Firebird read must not retain a PostgreSQL transaction")
            connection
        }
        every { connection.createStatement() } returns statement
        every { statement.executeQuery(any()) } returns source
        every { source.next() } returnsMany listOf(true, false)
        every { source.getString(any<String>()) } answers {
            when (firstArg<String>()) { "INN" -> "000101500011"; "USERNAME_N" -> "Synthetic"; else -> null }
        }
        every { source.getLong("G_PROFILE_ID") } returns 1L
        every { source.getLong("USER_ID") } returns 2L
        every { repository.deleteAllInBatch() } answers {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
            tx.events += "delete"
        }
        every { repository.saveAllAndFlush(any<List<StandardNPharmacistEntity>>()) } answers {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
            tx.events += "save"
            firstArg<List<StandardNPharmacistEntity>>()
        }
    }

    @Test fun `scheduled self-call commits delete and replacement together`() {
        service.scheduledSync()
        assertEquals(listOf("begin", "delete", "save", "commit"), tx.events)
        verify(exactly = 1) { connection.close() }
    }

    @Test fun `failed scheduled replacement rolls back instead of committing an empty directory`() {
        every { repository.saveAllAndFlush(any<List<StandardNPharmacistEntity>>()) } answers {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
            tx.events += "failed-save"
            throw IllegalStateException("synthetic replacement failure")
        }
        service.scheduledSync()
        assertEquals(listOf("begin", "delete", "failed-save", "rollback"), tx.events)
    }

    @Test fun `source read failure never starts replacement`() {
        every { statement.executeQuery(any()) } throws IllegalStateException("synthetic source failure")
        service.scheduledSync()
        assertTrue(tx.events.isEmpty())
        verify { repository wasNot Called }
    }

    @Test fun `source transport seam leaves other JDBC driver dispatch intact`() {
        val controlConnection = mockk<Connection>()
        val driver = object : java.sql.Driver {
            override fun acceptsURL(url: String?) = url == "jdbc:synthetic-control:dispatch"
            override fun connect(url: String?, info: Properties?): Connection? = if (acceptsURL(url)) controlConnection else null
            override fun getPropertyInfo(url: String?, info: Properties?) = emptyArray<java.sql.DriverPropertyInfo>()
            override fun getMajorVersion() = 1
            override fun getMinorVersion() = 0
            override fun jdbcCompliant() = false
            override fun getParentLogger(): java.util.logging.Logger = java.util.logging.Logger.getGlobal()
        }
        DriverManager.registerDriver(driver)
        try {
            // Only the injected source factory is mocked; other JDBC dispatch remains untouched.
            assertSame(controlConnection, DriverManager.getConnection("jdbc:synthetic-control:dispatch", Properties()))
        } finally { DriverManager.deregisterDriver(driver) }
    }

    private class RecordingTransactions : AbstractPlatformTransactionManager() {
        val events = mutableListOf<String>()
        override fun doGetTransaction(): Any = Any()
        override fun doBegin(transaction: Any, definition: TransactionDefinition) { events += "begin" }
        override fun doCommit(status: DefaultTransactionStatus) { events += "commit" }
        override fun doRollback(status: DefaultTransactionStatus) { events += "rollback" }
    }
}
