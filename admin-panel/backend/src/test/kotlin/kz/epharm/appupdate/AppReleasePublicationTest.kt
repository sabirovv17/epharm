package kz.epharm.appupdate

import io.mockk.*
import kz.epharm.appupdate.dto.RegisterReleaseRequest
import kz.epharm.appupdate.entity.AppReleaseEntity
import kz.epharm.appupdate.repository.AppReleaseRepository
import kz.epharm.appupdate.service.AppReleaseService
import kz.epharm.appupdate.service.AppUpdateManifestVerifier
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate

class AppReleasePublicationTest {
    @Test fun `signature validation then platform lock then flush precedes current insertion`() {
        val repo = mockk<AppReleaseRepository>()
        val verifier = mockk<AppUpdateManifestVerifier>()
        val jdbc = mockk<JdbcTemplate>()
        val old = AppReleaseEntity(id = "old", isCurrent = true)
        val request = RegisterReleaseRequest("1.0.81", "https://example.invalid/release.zip", " win-x64 ", "a".repeat(64), "AQID")
        every { verifier.validate(request) } just Runs
        every { jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 718096))", "win-x64") } returns emptyList()
        every { repo.findAllByPlatformAndIsCurrentTrue("win-x64") } returns listOf(old)
        every { repo.save(any()) } answers { firstArg() }
        every { repo.flush() } just Runs
        val result = AppReleaseService(repo, verifier, jdbc).register(request)
        assertFalse(old.isCurrent)
        assertTrue(result.isCurrent)
        assertEquals("win-x64", result.platform)
        verifyOrder {
            verifier.validate(request)
            jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 718096))", "win-x64")
            repo.findAllByPlatformAndIsCurrentTrue("win-x64")
            repo.save(old)
            repo.flush()
            repo.save(match { it.id != old.id && it.isCurrent })
        }
    }
}
