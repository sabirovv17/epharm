package kz.epharm.shared.storage

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class S3MediaStorageTest {
    @Test
    fun `certificate reads are restricted to managed image objects`() {
        val storage = S3MediaStorage(
            endpoint = "http://127.0.0.1:9000",
            publicUrl = "https://epharm.example.test/media",
            region = "us-east-1",
            accessKey = "test",
            secretKey = "test",
            bucket = "epharm-receipts",
        )
        val managed = "screens/123e4567-e89b-12d3-a456-426614174000.png"

        assertThat(storage.keyFromUrl("https://epharm.example.test/media/epharm-receipts/$managed"))
            .isEqualTo(managed)
        assertThat(storage.keyFromUrl("https://evil.example.test/epharm-receipts/$managed")).isNull()
        assertThat(storage.keyFromUrl("https://epharm.example.test/media/epharm-receipts/private/secret"))
            .isNull()
        assertThat(storage.keyFromUrl("https://epharm.example.test/media/epharm-receipts/$managed/../secret"))
            .isNull()
    }
}
