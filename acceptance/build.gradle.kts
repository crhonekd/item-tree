plugins {
    java
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

val cucumberVersion = "7.20.1"

dependencies {
    testImplementation("io.cucumber:cucumber-java:$cucumberVersion")
    testImplementation("io.cucumber:cucumber-junit-platform-engine:$cucumberVersion")
    testImplementation("io.cucumber:cucumber-picocontainer:$cucumberVersion")
    testImplementation("org.junit.platform:junit-platform-suite:1.11.4")
    testImplementation("io.rest-assured:rest-assured:5.5.0")
    testImplementation("org.assertj:assertj-core:3.27.3")
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
}

// No default unit tests in this module.
tasks.named<Test>("test") {
    enabled = false
}

// Black-box suite: run explicitly against a running instance.
//   ./gradlew :acceptance:cucumber -Ditemtree.baseUrl=http://host:8080
tasks.register<Test>("cucumber") {
    description = "Runs the Cucumber acceptance suite against a running ITEMTREE instance."
    group = "verification"
    useJUnitPlatform()
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    // Forward -Ditemtree.* properties to the test JVM.
    System.getProperties().forEach { key, value ->
        val k = key.toString()
        if (k.startsWith("itemtree.")) systemProperty(k, value.toString())
    }
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
    outputs.upToDateWhen { false }
}
