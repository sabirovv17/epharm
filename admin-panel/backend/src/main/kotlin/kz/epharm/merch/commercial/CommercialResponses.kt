package kz.epharm.merch.commercial

import kz.epharm.reporting.xlsx.ReportWorkbook
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity

internal fun <T> commercialPrivate(value:T):ResponseEntity<T> =ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"no-store")
    .header("X-Content-Type-Options","nosniff").body(value)
internal fun commercialAttachment(bytes:ByteArray):ResponseEntity<ByteArray> =ResponseEntity.ok().contentType(MediaType.parseMediaType(ReportWorkbook.MIME))
    .header(HttpHeaders.CACHE_CONTROL,"no-store").header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"media-kit.xlsx\"")
    .header("X-Content-Type-Options","nosniff").body(bytes)
