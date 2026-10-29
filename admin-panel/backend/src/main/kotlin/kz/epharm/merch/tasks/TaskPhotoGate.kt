package kz.epharm.merch.tasks

import java.util.concurrent.Semaphore
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus

/** Admission happens before file.bytes or ImageIO; one task upload cannot multiply heap pressure. */
object TaskPhotoGate {
    private val slot = Semaphore(1)
    fun <T> run(work: () -> T): T {
        if (!slot.tryAcquire()) throw AppException(ErrorCode.CONFLICT,
            "Обрабатывается другая фотография: повторите загрузку позже", HttpStatus.TOO_MANY_REQUESTS)
        try { return work() } finally { slot.release() }
    }
}
