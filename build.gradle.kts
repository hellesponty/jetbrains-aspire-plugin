import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.21"
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        val localPath = providers.gradleProperty("platformLocalPath").orNull
        if (localPath != null && file(localPath).exists()) {
            local(localPath)
        } else {
            intellijIdea(providers.gradleProperty("platformVersion"))
        }
        pluginVerifier()
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        // Stay on a stdlib API level that older IDE builds ship.
        apiVersion.set(KotlinVersion.KOTLIN_2_0)
        languageVersion.set(KotlinVersion.KOTLIN_2_1)
    }
}

intellijPlatform {
    buildSearchableOptions = false
    pluginVerification {
        ides {
            // One release of each IDE line from pluginSinceBuild to the newest; downloaded on demand.
            recommended()
            // Explicitly supported IDEs beyond IntelliJ IDEA: WebStorm (TypeScript AppHosts) and GoLand.
            select {
                types = listOf(
                    org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.WebStorm,
                    org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.GoLand,
                )
                channels = listOf(org.jetbrains.intellij.platform.gradle.models.ProductRelease.Channel.RELEASE)
                sinceBuild = providers.gradleProperty("pluginSinceBuild")
            }
        }
    }
    // Secrets come from the environment and are never committed. See README "Publishing".
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
    pluginConfiguration {
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }
}

// The IntelliJ Platform plugin derives the JVM target from the IDE we compile against (Java 25 for 2026.3);
// pin it so the plugin also loads in older IDEs running on Java 21.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
}

// `./gradlew runIde -PopenProject=/path/to/repo` opens that repo in the sandbox IDE.
tasks.named<org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask>("runIde") {
    providers.gradleProperty("openProject").orNull?.let { args(it) }
}

// `./gradlew runGoland -PopenProject=/path/to/repo` runs the plugin in a sandboxed copy of the local GoLand.
intellijPlatformTesting {
    runIde {
        register("runGoland") {
            val golandPath = providers.gradleProperty("golandLocalPath").orNull
            if (golandPath != null && file(golandPath).exists()) {
                localPath = file(golandPath)
            } else {
                type = org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.GoLand
                version = providers.gradleProperty("platformVersion")
            }
            task {
                providers.gradleProperty("openProject").orNull?.let { args(it) }
            }
        }
    }
}

// Refuse to sign or publish while plugin.xml or LICENSE still contain TODO placeholders (vendor, copyright).
val releaseFiles = listOf(
    layout.projectDirectory.file("src/main/resources/META-INF/plugin.xml").asFile,
    layout.projectDirectory.file("LICENSE").asFile,
)
listOf("signPlugin", "publishPlugin").forEach { name ->
    tasks.named(name) {
        doFirst {
            val unfinished = releaseFiles.filter { it.readText().contains("TODO") }
            if (unfinished.isNotEmpty()) {
                throw GradleException("Replace the TODO placeholders before releasing: ${unfinished.joinToString { it.name }}")
            }
        }
    }
}
