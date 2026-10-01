plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.intellij.platform)
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
        pluginVerifier()
        zipSigner()
    }

    // Compiled against the shared module; its classes are merged into the plugin jar below.
    compileOnly(project(":core"))
    // `compileOnly` does not reach the test compile classpath, and the tests use core too.
    testImplementation(project(":core"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.junit4)
}

intellijPlatform {
    // Pure Kotlin module: no Java sources, no GUI forms - nothing to instrument.
    instrumentCode = false

    // Optional index for the settings search; it starts a headless IDE and is not worth the build time here.
    buildSearchableOptions = false

    pluginConfiguration {
        version = project.version.toString()

        ideaVersion {
            sinceBuild = "253"
            untilBuild = provider { null }
        }
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

// The IntelliJ Platform plugin puts a project dependency into `lib/modules/`, where it only loads
// if the plugin descriptor also declares it as a content module. Merging the shared classes into
// the plugin's own jar avoids that second moving part entirely.
tasks.jar {
    from(project(":core").tasks.named<Jar>("jar").flatMap { it.archiveFile }.map { zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
