package kz.epharm.training.controller

import kz.epharm.training.dto.CertificateVerificationDto
import kz.epharm.training.service.TrainingCertificatePdfService
import kz.epharm.training.service.TrainingService
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Duration
import java.util.UUID

@RestController
@RequestMapping("/api/public/training/certificates")
class PublicTrainingController(
    private val trainingService: TrainingService,
    private val certificatePdfService: TrainingCertificatePdfService,
) {
    @GetMapping("/{token}")
    fun verify(@PathVariable token: UUID): CertificateVerificationDto =
        trainingService.verifyCertificate(token)

    @GetMapping("/{token}/pdf")
    fun pdf(@PathVariable token: UUID): ResponseEntity<ByteArray> {
        val certificate = trainingService.verifyCertificate(token)
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic())
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                "inline; filename=certificate-${certificate.number}.pdf",
            )
            .body(certificatePdfService.render(token, certificate))
    }
}
