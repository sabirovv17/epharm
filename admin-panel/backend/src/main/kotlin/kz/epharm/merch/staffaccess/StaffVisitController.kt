package kz.epharm.merch.staffaccess

import java.time.LocalDate
import java.util.UUID
import kz.epharm.merch.tasks.StaffVisitService
import kz.epharm.merch.tasks.StartStaffVisit
import kz.epharm.merch.tasks.SubmitStaffVisit
import kz.epharm.merch.tasks.evidenceResponse
import kz.epharm.merch.tasks.privateResponse
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile

/** Every object/byte route rechecks the limited staff session; a JWT cannot replace it. */
@RestController
@RequestMapping("/api/staff")
class StaffVisitController(private val visits: StaffVisitService) {
    @GetMapping("/workspace")
    fun workspace(@RequestHeader(name="X-Staff-Session", defaultValue="") session: String,
        @RequestParam(required=false) month: LocalDate?,
        @RequestParam(defaultValue="50") limit: Int, @RequestParam(defaultValue="0") offset: Int) =
        privateResponse(visits.workspace(session, month, limit, offset))

    @PostMapping("/visits")
    fun start(@RequestHeader(name="X-Staff-Session", defaultValue="") session: String, @RequestBody request: StartStaffVisit) =
        privateResponse(visits.start(session, request))

    @GetMapping("/visits/{visitId}")
    fun get(@RequestHeader(name="X-Staff-Session", defaultValue="") session: String, @PathVariable visitId: UUID) =
        privateResponse(visits.get(session, visitId))

    @PostMapping("/visits/{visitId}/items/{itemId}/evidence", consumes=[MediaType.MULTIPART_FORM_DATA_VALUE])
    fun upload(@RequestHeader(name="X-Staff-Session", defaultValue="") session: String,
        @PathVariable visitId: UUID, @PathVariable itemId: UUID,
        @RequestParam clientUploadId: UUID, @RequestPart file: MultipartFile) =
        privateResponse(visits.upload(session, visitId, itemId, clientUploadId, file))

    @GetMapping("/visits/{visitId}/evidence/{evidenceId}")
    fun evidence(@RequestHeader(name="X-Staff-Session", defaultValue="") session: String,
        @PathVariable visitId: UUID, @PathVariable evidenceId: UUID) =
        evidenceResponse(visits.evidence(session, visitId, evidenceId))

    @GetMapping("/visits/{visitId}/items/{itemId}/reference")
    fun reference(@RequestHeader(name="X-Staff-Session", defaultValue="") session: String,
        @PathVariable visitId: UUID, @PathVariable itemId: UUID) =
        evidenceResponse(visits.reference(session, visitId, itemId))

    @PostMapping("/visits/{visitId}/submit")
    fun submit(@RequestHeader(name="X-Staff-Session", defaultValue="") session: String,
        @PathVariable visitId: UUID, @RequestBody request: SubmitStaffVisit) =
        privateResponse(visits.submit(session, visitId, request))
}
