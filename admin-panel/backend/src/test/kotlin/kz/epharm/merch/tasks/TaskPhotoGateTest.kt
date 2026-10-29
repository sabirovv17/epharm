package kz.epharm.merch.tasks

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kz.epharm.shared.error.AppException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus

class TaskPhotoGateTest {
    @Test fun `busy photo pipeline rejects before work and releases admission after success or exception`() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val executor=Executors.newSingleThreadExecutor()
        val first=executor.submit<Int> { TaskPhotoGate.run { entered.countDown();check(release.await(10,TimeUnit.SECONDS));1 } }
        try {
            assertTrue(entered.await(10,TimeUnit.SECONDS))
            var allocated=false
            val error=assertThrows<AppException> { TaskPhotoGate.run { allocated=true } }
            assertEquals(HttpStatus.TOO_MANY_REQUESTS,error.status);assertFalse(allocated)
        } finally {
            release.countDown()
            try { assertEquals(1,first.get(10,TimeUnit.SECONDS)) } finally { executor.shutdownNow() }
        }
        assertThrows<IllegalStateException> { TaskPhotoGate.run { throw IllegalStateException("synthetic") } }
        assertEquals(2,TaskPhotoGate.run { 2 })
    }
}
