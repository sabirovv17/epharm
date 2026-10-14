import java.util.Properties
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dep.mgmt)
}

group = "kz.epharm"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(22))
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_22)
    }
}

dependencies {
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.data.redis)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.sentry.spring.boot)

    implementation(libs.kotlin.reflect)
    implementation(libs.jackson.module.kotlin)

    implementation(libs.jjwt.api)
    runtimeOnly(libs.jjwt.impl)
    runtimeOnly(libs.jjwt.jackson)

    runtimeOnly(libs.postgresql)
    // Standard-N pharmacist directory connects to Firebird at runtime.
    runtimeOnly(libs.jaybird)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)

    implementation(libs.aws.s3)
    implementation(libs.poi.ooxml)
    implementation(libs.pdfbox)
    implementation(libs.zxing.core)

    implementation(libs.springdoc.openapi.starter)

    developmentOnly(libs.spring.boot.devtools)

    testImplementation(libs.spring.boot.starter.test) {
        exclude(group = "org.mockito", module = "mockito-core")
    }
    testImplementation(libs.spring.security.test)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.springmockk)
}

tasks.withType<Test> {
    useJUnitPlatform()
    maxHeapSize = "1g"
    forkEvery = 12
    systemProperty("spring.test.context.cache.maxSize", "6")
}

// Стабильное имя исполняемого jar — чтобы Dockerfile копировал build/libs/app.jar
// без зависимости от версии. Plain-jar не нужен в runtime-образе.
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("app.jar")
}
tasks.named<Jar>("jar") {
    enabled = false
}

val verifyMerchandisingArtifact by tasks.registering {
    dependsOn("bootJar")
    doLast {
        val contract = Properties().apply {
            file("src/main/resources/merch-artifact-contract.properties").inputStream().use { load(it) }
        }
        ZipFile(layout.buildDirectory.file("libs/app.jar").get().asFile).use { jar ->
            contract.forEach { path, markers ->
                val entry = requireNotNull(jar.getEntry(path.toString())) { "Missing merchandising artifact: $path" }
                val bytes = jar.getInputStream(entry).use { it.readBytes().toString(Charsets.ISO_8859_1) }
                markers.toString().split(',').forEach { marker ->
                    check(bytes.contains(marker)) { "Obsolete merchandising artifact $path: missing $marker" }
                }
            }
        }
    }
}
tasks.named("check") { dependsOn(verifyMerchandisingArtifact) }
