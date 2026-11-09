package kz.epharm.pharmacists

import io.mockk.*
import kz.epharm.pharmacists.entity.StandardNPharmacistEntity
import kz.epharm.pharmacists.repository.StandardNPharmacistRepository
import kz.epharm.pharmacists.service.StandardNPharmacistSyncService
import kz.epharm.pharmacists.service.StandardNDirectoryConnectionFactory
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement
import java.util.Properties
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real PostgreSQL replacement/rollback; only the external Firebird source is synthetic. */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class StandardNPharmacistSyncIntegrationTest {
    companion object {
        @Container @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine").apply { start() }
        @JvmStatic @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
        }
    }

    @Autowired private lateinit var repository: StandardNPharmacistRepository
    @Autowired private lateinit var transactionManager: PlatformTransactionManager
    @Autowired private lateinit var jdbc: JdbcTemplate
    private val source = mockk<ResultSet>(relaxed = true)
    private val connections = mockk<StandardNDirectoryConnectionFactory>()

    @BeforeEach fun setup() {
        repository.deleteAllInBatch()
        repository.saveAndFlush(StandardNPharmacistEntity(directoryKey = "1:1", sourceProfileId = 1, sourceUserId = 1,
            iin = "000101500011", fullName = "Synthetic old snapshot", sourceStatus = 0))
        val connection = mockk<Connection>(relaxed = true)
        val statement = mockk<Statement>(relaxed = true)
        every { connections.open("jdbc:firebirdsql://synthetic:3050/synthetic.fdb", any<Properties>()) } returns connection
        every { connection.createStatement() } returns statement
        every { statement.executeQuery(any()) } returns source
        every { source.next() } returnsMany listOf(true, false)
        every { source.getString(any<String>()) } answers {
            when (firstArg<String>()) { "INN" -> "000101500011"; "USERNAME_N" -> "Synthetic new snapshot"; else -> null }
        }
        every { source.getLong("G_PROFILE_ID") } returns 2L
        every { source.getLong("USER_ID") } returns 2L
    }

    private fun service(store: StandardNPharmacistRepository = repository) = StandardNPharmacistSyncService(
        store, transactionManager, connections, true, "synthetic", 3050, "synthetic.fdb", "synthetic", "synthetic",
    )

    @Test fun `failed scheduled insert retains the previous committed directory`() {
        every { source.getString("USERNAME_N") } returns "x".repeat(256)
        val delegated = mockk<StandardNPharmacistRepository>()
        var insertFailure: Throwable? = null
        every { delegated.deleteAllInBatch() } answers { repository.deleteAllInBatch() }
        every { delegated.saveAllAndFlush(any<List<StandardNPharmacistEntity>>()) } answers {
            try {
                repository.saveAllAndFlush(firstArg<List<StandardNPharmacistEntity>>())
            } catch (failure: RuntimeException) {
                insertFailure = failure
                throw failure
            }
        }
        service(delegated).scheduledSync()
        // scheduledSync logs failures; assert outside its catch that this was an actual DB insert failure.
        assertInstanceOf(DataIntegrityViolationException::class.java, insertFailure)
        verify(exactly = 1) { delegated.deleteAllInBatch() }
        verify(exactly = 1) { delegated.saveAllAndFlush(any<List<StandardNPharmacistEntity>>()) }
        assertEquals(listOf("1:1"), repository.findAll().map { it.directoryKey })
    }

    @Test fun `successful scheduled replacement is visible only after the entire transaction commits`() {
        val delegated = mockk<StandardNPharmacistRepository>()
        val reader = Executors.newSingleThreadExecutor()
        try {
            every { delegated.deleteAllInBatch() } answers {
                repository.deleteAllInBatch()
                val visible = reader.submit<List<String>> {
                    jdbc.query("SELECT directory_key FROM standardn_pharmacist_directory ORDER BY directory_key", { rs, _ -> rs.getString(1) })
                }.get(5, TimeUnit.SECONDS)
                assertEquals(listOf("1:1"), visible, "Concurrent reader must not observe the uncommitted deletion")
            }
            every { delegated.saveAllAndFlush(any<List<StandardNPharmacistEntity>>()) } answers {
                repository.saveAllAndFlush(firstArg<List<StandardNPharmacistEntity>>())
            }
            service(delegated).scheduledSync()
            assertEquals(listOf("2:2"), repository.findAll().map { it.directoryKey })
        } finally { reader.shutdownNow() }
    }
}
