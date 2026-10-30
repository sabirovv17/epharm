package kz.epharm.lms.controller

import kz.epharm.auth.security.AdminPrincipal
import kz.epharm.lms.service.CoursePackageImportService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/api/admin/lms/courses/import/package")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','TRAINING_MANAGER')")
class CoursePackageController(private val importer: CoursePackageImportService) {
    @PostMapping("/preview", consumes = ["multipart/form-data"])
    fun preview(@RequestParam("file") file: MultipartFile, @AuthenticationPrincipal actor: AdminPrincipal) =
        importer.preview(file, actor)

    @PostMapping(consumes = ["multipart/form-data"])
    fun create(@RequestParam("file") file: MultipartFile, @RequestParam("digest") digest: String,
               @AuthenticationPrincipal actor: AdminPrincipal) = importer.importPackage(file, digest, actor)
}
