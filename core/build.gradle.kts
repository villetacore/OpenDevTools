plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.intellij.platform.module)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdea(libs.versions.intellijIdea)
    }
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.junit4)
}

intellijPlatform {
    // Pure Kotlin module: no Java sources, no GUI forms - nothing to instrument.
    instrumentCode = false
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
