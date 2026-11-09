package kz.epharm.mobile.auth

import kz.epharm.mobile.auth.service.OtpTransactionAdmission
import kz.epharm.mobile.auth.service.OtpTransactionAdmissionAspect
import kz.epharm.shared.error.AppException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import io.mockk.every
import io.mockk.mockk
import org.aspectj.lang.ProceedingJoinPoint
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.TypeExcludeFilter
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.*
import org.springframework.context.support.GenericApplicationContext
import org.springframework.core.env.MapPropertySource
import org.springframework.core.io.support.SpringFactoriesLoader
import org.springframework.test.context.ContextCustomizerFactory
import org.springframework.test.context.MergedContextConfiguration
import org.springframework.test.context.support.AnnotationConfigContextLoader
import org.springframework.transaction.*
import org.springframework.transaction.annotation.*
import org.springframework.jdbc.datasource.AbstractDataSource
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import com.zaxxer.hikari.HikariDataSource
import java.lang.reflect.Proxy
import java.sql.Connection
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException
import java.util.concurrent.CompletableFuture

/** Real Spring advisors/transactions/Hikari, fake physical JDBC connections: no external I/O. */
class OtpTransactionAdmissionTest {
    @Test fun `healthy work waits through a brief CPU burst before acquiring the existing permit`() {
        val admission = OtpTransactionAdmissionAspect(1, 4)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val followerStarted = CountDownLatch(1)
        val holder = mockk<ProceedingJoinPoint>()
        every { holder.proceed() } answers {
            entered.countDown(); check(release.await(10, TimeUnit.SECONDS)); "holder"
        }
        val follower = mockk<ProceedingJoinPoint>()
        every { follower.proceed() } returns "accepted"
        val executor = Executors.newFixedThreadPool(2)
        try {
            val held = executor.submit<Any?> { admission.invoke(holder) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val waiting = executor.submit<Any?> {
                followerStarted.countDown()
                admission.invoke(follower)
            }
            assertTrue(followerStarted.await(5, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) { waiting.get(1100, TimeUnit.MILLISECONDS) }
            io.mockk.verify(exactly = 0) { follower.proceed() }
            release.countDown()
            assertEquals("holder", held.get(5, TimeUnit.SECONDS))
            assertEquals("accepted", waiting.get(5, TimeUnit.SECONDS))
            io.mockk.verify(exactly = 1) { follower.proceed() }
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun `interrupted waiter preserves interrupt flag without retaining or inflating admission`() {
        val admission = OtpTransactionAdmissionAspect(1, 4)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val waiting = CountDownLatch(1)
        val observed = CompletableFuture<Boolean>()
        val holder = mockk<ProceedingJoinPoint>()
        every { holder.proceed() } answers {
            entered.countDown(); check(release.await(10, TimeUnit.SECONDS)); "holder"
        }
        val next = mockk<ProceedingJoinPoint>()
        every { next.proceed() } returns "accepted"
        val executor = Executors.newSingleThreadExecutor()
        val interrupted = Thread {
            try {
                waiting.countDown()
                val error = assertThrows(AppException::class.java) { admission.invoke(next) }
                val preserved = Thread.currentThread().isInterrupted
                Thread.interrupted() // The caller, not admission, clears cancellation before new work.
                assertEquals(503, assertThrows(AppException::class.java) { admission.invoke(next) }.status.value(),
                    "Interrupted waiter must not retain the nested-admission flag")
                observed.complete(error.status.value() == 503 && preserved)
            } catch (failure: Throwable) { observed.completeExceptionally(failure) }
        }
        try {
            val held = executor.submit<Any?> { admission.invoke(holder) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            interrupted.start()
            assertTrue(waiting.await(5, TimeUnit.SECONDS))
            interrupted.interrupt()
            assertTrue(observed.get(5, TimeUnit.SECONDS))
            assertEquals(503, assertThrows(AppException::class.java) { admission.invoke(next) }.status.value(),
                "Interrupted waiter must not release a permit owned by the holder")
            io.mockk.verify(exactly = 0) { next.proceed() }
            release.countDown()
            assertEquals("holder", held.get(5, TimeUnit.SECONDS))
            assertEquals("accepted", admission.invoke(next))
        } finally {
            release.countDown(); interrupted.interrupt(); interrupted.join(1000); executor.shutdownNow()
        }
    }

    @Test fun `fake JDBC configuration is excluded by the Boot test component scan`() {
        assertNotNull(Config::class.java.getAnnotation(TestConfiguration::class.java),
            "Synthetic JDBC beans must be explicitly marked test-only")
        val configName = Config::class.java.name
        val packageName = javaClass.packageName
        val scanner = ClassPathScanningCandidateComponentProvider(true)
        assertTrue(scanner.findCandidateComponents(packageName).any { it.beanClassName == configName },
            "Positive control: an unfiltered component scan would find the fixture")

        GenericApplicationContext().use { context ->
            // Use the actual Boot factory/filter, as @SpringBootApplication does in integration tests.
            // No context refresh, datasource creation, Docker or application startup is required.
            val factory = SpringFactoriesLoader.loadFactories(ContextCustomizerFactory::class.java, javaClass.classLoader)
                .single { it.javaClass.name == "org.springframework.boot.test.context.filter.ExcludeFilterContextCustomizerFactory" }
            val merged = MergedContextConfiguration(javaClass, emptyArray(), emptyArray(), emptyArray(), AnnotationConfigContextLoader())
            requireNotNull(factory.createContextCustomizer(javaClass, emptyList())).customizeContext(context, merged)
            scanner.addExcludeFilter(TypeExcludeFilter().apply { setBeanFactory(context.beanFactory) })
            assertFalse(scanner.findCandidateComponents(packageName).any { it.beanClassName == configName },
                "Boot must not discover the fake datasource, transaction manager or admission beans")
        }
    }

    @Test fun `cancellation does not retain admission or thread-local nesting state`() {
        val admission = OtpTransactionAdmissionAspect(1, 4)
        val cancelled = mockk<ProceedingJoinPoint>()
        every { cancelled.proceed() } throws CancellationException("synthetic cancellation")
        repeat(8) { assertThrows(CancellationException::class.java) { admission.invoke(cancelled) } }
        val next = mockk<ProceedingJoinPoint>()
        every { next.proceed() } returns "accepted"
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = mockk<ProceedingJoinPoint>()
        every { holder.proceed() } answers {
            entered.countDown(); check(release.await(10, TimeUnit.SECONDS)); "holder"
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val held = executor.submit<Any?> { admission.invoke(holder) }
            assertTrue(entered.await(5, TimeUnit.SECONDS), "Cancellation must return the permit for another thread")
            assertEquals(503, assertThrows(AppException::class.java) { admission.invoke(next) }.status.value(),
                "Cancelled caller must not retain a nesting flag that bypasses another holder")
            release.countDown(); assertEquals("holder", held.get(5, TimeUnit.SECONDS))
            assertEquals("accepted", admission.invoke(next))
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @ParameterizedTest @ValueSource(ints = [8, 10])
    fun `slow OTP and same identity storm are rejected before borrowing transactions`(poolSize: Int) {
        AnnotationConfigApplicationContext().use { context ->
            context.environment.propertySources.addFirst(MapPropertySource("test", mapOf("test.database.pool" to poolSize)))
            context.register(Config::class.java)
            context.refresh()
            val service = context.getBean(Outer::class.java)
            val slow = context.getBean(Slow::class.java)
            val dataSource = context.getBean(HikariDataSource::class.java)
            val admittedCount = (poolSize - 2) / 2
            val executor = Executors.newFixedThreadPool(admittedCount)
            val rejectedExecutor = Executors.newFixedThreadPool(20)
            try {
                val active = (1..admittedCount).map { executor.submit<Unit> { service.call() } }
                assertTrue(slow.entered.await(5, TimeUnit.SECONDS))
                // Every admitted outer mobile transaction suspends for one REQUIRES_NEW OTP transaction.
                assertEquals(poolSize - 2, dataSource.hikariPoolMXBean.activeConnections)
                val start = CountDownLatch(1)
                val rejected = (1..20).map { rejectedExecutor.submit<AppException> {
                    check(start.await(5, TimeUnit.SECONDS))
                    assertThrows(AppException::class.java) { service.call() }
                } }
                val started = System.nanoTime()
                start.countDown()
                rejected.forEach {
                    val error = it.get(3, TimeUnit.SECONDS)
                    assertEquals(503, error.status.value())
                    assertNull(error.retryAfterSeconds)
                }
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 3000,
                    "Held-budget admission must stop waiting within its bounded deadline")
                assertEquals(poolSize - 2, dataSource.hikariPoolMXBean.activeConnections, "Rejected callers must not borrow connections")
                dataSource.connection.use {
                    dataSource.connection.use {
                        assertEquals(poolSize, dataSource.hikariPoolMXBean.activeConnections,
                            "Two remaining real Hikari slots are immediately usable by unrelated work")
                    }
                }
                slow.release.countDown()
                active.forEach { it.get(5, TimeUnit.SECONDS) }
                assertEquals(0, dataSource.hikariPoolMXBean.activeConnections)
                service.call() // permits survive success and nested proxy calls do not reacquire
                assertEquals(0, dataSource.hikariPoolMXBean.activeConnections)
                slow.fail = true
                repeat(10) { assertThrows(IllegalStateException::class.java) { service.call() } }
                slow.fail = false
                service.call()
                assertEquals(0, dataSource.hikariPoolMXBean.activeConnections)
            } finally { slow.release.countDown(); executor.shutdownNow(); rejectedExecutor.shutdownNow() }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    @EnableTransactionManagement
    class Config {
        @Bean fun admission(@Value("\${test.database.pool}") poolSize: Int) = OtpTransactionAdmissionAspect(100, poolSize)
        @Bean(destroyMethod = "close") fun dataSource(@Value("\${test.database.pool}") poolSize: Int) = HikariDataSource().apply {
            maximumPoolSize = poolSize; minimumIdle = 0; connectionTimeout = 500
            dataSource = object : AbstractDataSource() {
                override fun getConnection(): Connection = fakeConnection()
                override fun getConnection(username: String?, password: String?): Connection = fakeConnection()
                override fun setLoginTimeout(seconds: Int) = Unit
            }
        }
        @Bean fun transactionManager(dataSource: HikariDataSource) = DataSourceTransactionManager(dataSource)
        @Bean fun slow(@Value("\${test.database.pool}") poolSize: Int) = Slow((poolSize - 2) / 2)
        @Bean fun outer(slow: Slow) = Outer(slow)
    }

    open class Outer(private val slow: Slow) {
        @OtpTransactionAdmission @Transactional
        open fun call() = slow.call()
    }
    open class Slow(admittedCount: Int) {
        open val entered = CountDownLatch(admittedCount)
        open val release = CountDownLatch(1)
        open var fail = false
        @OtpTransactionAdmission @Transactional(propagation = Propagation.REQUIRES_NEW)
        open fun call() {
            entered.countDown(); check(release.await(10, TimeUnit.SECONDS))
            if (fail) throw IllegalStateException("synthetic failure")
        }
    }
    companion object {
        private fun fakeConnection(): Connection {
            var closed = false
            var autoCommit = true
            return Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { proxy, method, args ->
                when (method.name) {
                    "getAutoCommit" -> autoCommit
                    "setAutoCommit" -> { autoCommit = args!![0] as Boolean; null }
                    "isValid" -> !closed
                    "isClosed" -> closed
                    "close", "abort" -> { closed = true; null }
                    "isReadOnly", "isWrapperFor" -> false
                    "getNetworkTimeout" -> 0
                    "getTransactionIsolation" -> Connection.TRANSACTION_READ_COMMITTED
                    "setReadOnly", "setNetworkTimeout", "setTransactionIsolation", "setCatalog", "setSchema",
                    "commit", "rollback", "clearWarnings" -> null
                    "getCatalog", "getSchema", "getWarnings" -> null
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args!![0]
                    "toString" -> "synthetic-jdbc-connection"
                    else -> throw UnsupportedOperationException("Unexpected JDBC call: ${method.name}")
                }
            } as Connection
        }
    }
}
