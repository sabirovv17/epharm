package kz.epharm.lms

import kz.epharm.lms.entity.CourseLiveProvider
import kz.epharm.lms.service.LiveStreamUrlPolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LiveStreamUrlPolicyTest {
    @Test
    fun `accepts public HTTPS URLs for their selected provider`() {
        assertThat(LiveStreamUrlPolicy.safeUrl("https://www.youtube.com/watch?v=abc", CourseLiveProvider.youtube))
            .isEqualTo("https://www.youtube.com/watch?v=abc")
        assertThat(LiveStreamUrlPolicy.safeUrl("https://youtu.be/abc", CourseLiveProvider.youtube)).isNotNull
        assertThat(LiveStreamUrlPolicy.safeUrl("https://player.vimeo.com/video/123", CourseLiveProvider.vimeo)).isNotNull
        assertThat(LiveStreamUrlPolicy.safeUrl("https://us02web.zoom.us/j/123", CourseLiveProvider.zoom)).isNotNull
        assertThat(LiveStreamUrlPolicy.safeUrl("https://video.example.kz/embed/123", CourseLiveProvider.custom)).isNotNull
    }

    @Test
    fun `rejects unsafe schemes local endpoints credentials and provider mismatch`() {
        val rejected = listOf(
            "javascript:alert(1)",
            "data:text/html,<script>alert(1)</script>",
            "file:///etc/passwd",
            "http://www.youtube.com/watch?v=abc",
            "https://localhost/live",
            "https://10.10.1.76/live",
            "https://[::1]/live",
            "https://intranet.internal/live",
            "https://user:pass@youtube.com/live",
            "https://youtube.com:8443/live",
            "https://youtube.com.evil.example/live",
            "https://bad host/live",
        )
        rejected.forEach { url ->
            assertThat(LiveStreamUrlPolicy.safeUrl(url, CourseLiveProvider.youtube)).describedAs(url).isNull()
        }
        assertThat(LiveStreamUrlPolicy.safeUrl("https://vimeo.com/123", CourseLiveProvider.youtube)).isNull()
        assertThat(LiveStreamUrlPolicy.safeUrl("https://127.0.0.1/live", CourseLiveProvider.custom)).isNull()
    }
}
