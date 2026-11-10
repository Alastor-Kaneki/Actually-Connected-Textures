import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-library`
    id("org.jetbrains.kotlin.jvm")
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}
dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation(kotlin("test"))
}
tasks.named("processResources") {
    doFirst {
        check(file("src/main/resources/vanilla_fallbacks.zip").isFile) {
            "Run python3 tools/build_vanilla_fallbacks.py <bedrock-samples/resource_pack> from the repository root before building"
        }
    }
}
tasks.test {
    useJUnitPlatform()
    maxHeapSize = "2g"
    systemProperty("ctm.golden", System.getProperty("ctm.golden") ?: "")
    testLogging { events("passed", "failed", "skipped"); showStandardStreams = true; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
