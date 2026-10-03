plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // core:common owns cross-cutting primitives (Outcome, Logger, ErrorMapper, AppConfig).
    // It depends on the domain *vocabulary* (AppError, transports, settings models) so those
    // primitives can be typed; the domain never depends on core:common.
    api(project(":domain:model"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach {
    useJUnit()
}
