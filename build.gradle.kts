// Aggregator only: every module carries its own IntelliJ Platform configuration, because the
// plugin modules and the shared library module apply different variants of the platform plugin.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}

allprojects {
    group = "com.opendevtools"
    version = providers.gradleProperty("openDevToolsVersion").getOrElse("0.2.0")
}

/**
 * The JetBrains plugin verifier downloads and unpacks the IDEs it verifies against into one
 * shared directory. With parallel execution the two plugin modules race on that cache and one of
 * them fails, so the verifications are ordered instead.
 */
gradle.projectsEvaluated {
    subprojects
        .mapNotNull { it.tasks.findByName("verifyPlugin") }
        .zipWithNext { earlier, later -> later.mustRunAfter(earlier) }
}

tasks.wrapper {
    gradleVersion = "9.7.1"
}
