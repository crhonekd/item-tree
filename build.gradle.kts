plugins {
    java
    id("org.springframework.boot") version "3.4.1"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.openapi.generator") version "7.10.0"
}

group = "com.myxcomp.ice"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // ── Web + validation + jdbc + actuator ───────────────────────────────
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // ── Jackson — XML ───────────────────────────────────────────────────────
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-xml")

    // ── Observability ────────────────────────────────────────────────────
    implementation("io.micrometer:micrometer-registry-prometheus")

    // ── OpenAPI doc endpoint (spec authoring lands in Phase 1) ───────────
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.6")

    // ── Lombok ───────────────────────────────────────────────────────────
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")

    // ── H2 (Phase A runtime + tests) ─────────────────────────────────────
    runtimeOnly("com.h2database:h2")
    testRuntimeOnly("com.h2database:h2")

    // ── Test stack ───────────────────────────────────────────────────────
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.withType<Test> {
    useJUnitPlatform()
    systemProperty("user.timezone", "UTC")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}
