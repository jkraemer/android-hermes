plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

// End-to-end tests run against a real Hermes server; they are opt-in and not
// part of `check`.
val e2eTest: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
    runtimeClasspath += sourceSets.main.get().output + sourceSets.test.get().output
}
configurations[e2eTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[e2eTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

// A separate task, so the check also fires while no e2e test sources exist
// (a Test task without sources is skipped before doFirst runs).
val requireE2eEnv by tasks.registering {
    doFirst {
        if (System.getenv("HERMES_E2E_ENV").isNullOrBlank()) {
            throw GradleException("HERMES_E2E_ENV is not set; point it at the e2e environment file.")
        }
    }
}

tasks.register<Test>("e2eTest") {
    description = "Runs the end-to-end tests against a real Hermes server."
    group = "verification"
    dependsOn(requireE2eEnv)
    testClassesDirs = e2eTest.output.classesDirs
    classpath = e2eTest.runtimeClasspath
    // The env file is written by the e2e setup script; a relative path is relative to the
    // Gradle build root, not to this module's test working directory.
    environment("HERMES_E2E_ENV", System.getenv("HERMES_E2E_ENV")?.let { rootProject.file(it).absolutePath } ?: "")
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coroutines.core)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
