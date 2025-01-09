import com.ncorti.ktfmt.gradle.tasks.KtfmtCheckTask
import com.ncorti.ktfmt.gradle.tasks.KtfmtFormatTask
import com.vanniktech.maven.publish.SonatypeHost
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktfmt)
    alias(libs.plugins.vanniktech.mavenPublishBase)
    application
}

application {
    mainClass = "io.cloudshiftdev.mavensync.MavenSyncMainKt"
}

// publish just the distZip artifact such that consumers can download and run the application
val distConf = configurations.create("dist")

val distArtifact = artifacts.add(distConf.name, tasks.named("distZip"))

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifact(distArtifact)
        }
    }
}

dependencies {
    implementation(libs.ksoup.base)
    implementation(platform(libs.ktor.bom))
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.auth)
    implementation(libs.ktor.client.logging)
    implementation(libs.kotlinx.coroutines)

    implementation(libs.oshai.kotlinLogging)

    implementation(libs.guava)
    implementation(libs.pearx.kasechange)

    implementation(libs.sksmauel.hoplite)
    implementation(libs.sksmauel.hoplite.json)

    implementation("ch.qos.logback:logback-classic:1.5.16")
}

ktfmt {
    kotlinLangStyle()
}

java {
    consistentResolution { useCompileClasspathVersions() }
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

kotlin {
    explicitApi()
    jvmToolchain(21)
}

mavenPublishing {
    publishToMavenCentral(SonatypeHost.S01, true)
    signAllPublications()

    pom {
        name = "maven-sync"
        description = "Sync Maven repositories"
        inceptionYear = "2023"
        url = "https://github.com/cloudshiftinc/maven-sync"
        licenses {
            license {
                name = "Apache License, Version 2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                distribution = "https://www.apache.org/licenses/LICENSE-2.0.txt"
            }
        }
        developers {
            developer {
                id = "cloudshiftchris"
                name = "Chris Lee"
                email = "chris@cloudshiftconsulting.com"
            }
        }
        scm {
            connection = "scm:git:git://github.com/cloudshiftinc/maven-sync.git"
            developerConnection = "scm:git:https://github.com/cloudshiftinc/maven-sync.git"
            url = "https://github.com/cloudshiftinc/maven-sync"
        }
    }

}

testing {
    suites {
        val test by getting(JvmTestSuite::class) {
            useJUnitJupiter()
            dependencies {
                implementation(platform(libs.kotest.bom))
                implementation(libs.kotest.assertions.core)
                implementation(libs.kotest.runner.junit5)
            }
            targets {
                all {
                    testTask.configure {
                        outputs.upToDateWhen { false }
                        testLogging {
                            events =
                                setOf(
                                    TestLogEvent.FAILED,
                                    TestLogEvent.PASSED,
                                    TestLogEvent.SKIPPED,
                                    TestLogEvent.STANDARD_OUT,
                                    TestLogEvent.STANDARD_ERROR
                                )
                            exceptionFormat = TestExceptionFormat.FULL
                            showExceptions = true
                            showCauses = true
                            showStackTraces = true
                        }
                    }
                }
            }
        }
    }
}

val ciBuild = System.getenv("CI") != null

val precommit = tasks.register("precommit") {
    group = "verification"
    dependsOn("check", "ktfmtFormat")
}

// only check formatting for CI builds
tasks.withType<KtfmtCheckTask>().configureEach {
    enabled = ciBuild
}

// always format for non-CI builds
tasks.withType<KtfmtFormatTask>().configureEach {
    enabled = !ciBuild
}
