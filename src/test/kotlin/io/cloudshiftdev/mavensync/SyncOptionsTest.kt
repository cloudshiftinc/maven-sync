package io.cloudshiftdev.mavensync

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class SyncOptionsTest :
    FunSpec({
        test("SyncConfig.toSyncOptions maps every field") {
            val config =
                SyncConfig(
                    transferChecksums = true,
                    transferSignatures = false,
                    artifactConcurrency = 7,
                    source =
                        SourceRepositoryConfig(
                            url = "https://source.example.com/maven/",
                            credentials = null,
                            logHttpHeaders = false,
                            trustAllCerts = true,
                            crawlDelay = 250.milliseconds,
                            downloadDelay = 2.seconds,
                            paths = listOf("com/example", "org/other"),
                        ),
                    target =
                        TargetRepositoryConfig(
                            url = "https://target.example.com/maven/",
                            credentials = null,
                            logHttpHeaders = false,
                            trustAllCerts = true,
                        ),
                )

            config.toSyncOptions() shouldBe
                SyncOptions(
                    transferChecksums = true,
                    transferSignatures = false,
                    artifactConcurrency = 7,
                    crawlDelay = 250.milliseconds,
                    downloadDelay = 2.seconds,
                    paths = listOf("com/example", "org/other"),
                )
        }
    })
