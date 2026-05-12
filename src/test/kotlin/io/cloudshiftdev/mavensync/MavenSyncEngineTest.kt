package io.cloudshiftdev.mavensync

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlin.time.Duration

private fun defaultOptions(transferChecksums: Boolean = false, transferSignatures: Boolean = true) =
    SyncOptions(
        transferChecksums = transferChecksums,
        transferSignatures = transferSignatures,
        artifactConcurrency = 1,
        crawlDelay = Duration.ZERO,
        downloadDelay = Duration.ZERO,
        paths = emptyList(),
    )

private val group = Group("com.example")
private val artifact = Artifact("foo")

private fun coords(v: String) = Coordinates(group, artifact, ArtifactVersion(v))

class MavenSyncEngineTest :
    FunSpec({
        test("does nothing when target already has all source versions") {
            val source = FakeMavenHttpRepository("source")
            val target = FakeMavenHttpRepository("target")
            target.seedMetadata(
                ArtifactMetadata(
                    group,
                    artifact,
                    listOf(ArtifactVersion("1.0"), ArtifactVersion("1.1")),
                )
            )
            val engine = MavenSyncEngine(source, target, defaultOptions(), SyncMetrics())

            engine.handleArtifact(
                ArtifactMetadata(
                    group,
                    artifact,
                    listOf(ArtifactVersion("1.0"), ArtifactVersion("1.1")),
                )
            )

            source.listAssetCalls.shouldBeEmpty()
            source.copyCalls.shouldBeEmpty()
            target.releaseCalls.shouldBeEmpty()
        }

        test("copies missing versions and releases each one") {
            val source = FakeMavenHttpRepository("source")
            val target = FakeMavenHttpRepository("target")
            target.seedMetadata(ArtifactMetadata(group, artifact, listOf(ArtifactVersion("1.0"))))

            val v11 = coords("1.1")
            val v20 = coords("2.0")
            source.assets[v11] = listOf(ArtifactVersionAsset(v11, Filename("foo-1.1.jar")))
            source.assets[v20] = listOf(ArtifactVersionAsset(v20, Filename("foo-2.0.jar")))

            val engine = MavenSyncEngine(source, target, defaultOptions(), SyncMetrics())

            engine.handleArtifact(
                ArtifactMetadata(
                    group,
                    artifact,
                    listOf(ArtifactVersion("1.0"), ArtifactVersion("1.1"), ArtifactVersion("2.0")),
                )
            )

            source.listAssetCalls.map { it.first } shouldContainExactlyInAnyOrder listOf(v11, v20)
            source.copyCalls.map { (asset, repo) ->
                asset.coordinates to repo
            } shouldContainExactlyInAnyOrder listOf(v11 to target, v20 to target)
            target.releaseCalls shouldContainExactlyInAnyOrder listOf(v11, v20)
        }

        test("skips releaseVersion when listed assets are empty for a missing version") {
            val source = FakeMavenHttpRepository("source")
            val target = FakeMavenHttpRepository("target")
            val v10 = coords("1.0")
            source.assets[v10] = emptyList()

            val engine = MavenSyncEngine(source, target, defaultOptions(), SyncMetrics())

            engine.handleArtifact(ArtifactMetadata(group, artifact, listOf(ArtifactVersion("1.0"))))

            source.listAssetCalls.map { it.first } shouldContainExactly listOf(v10)
            source.copyCalls.shouldBeEmpty()
            target.releaseCalls.shouldBeEmpty()
        }

        test("passes transferChecksums and transferSignatures flags through to source") {
            val source = FakeMavenHttpRepository("source")
            val target = FakeMavenHttpRepository("target")
            val v = coords("1.0")
            source.assets[v] = listOf(ArtifactVersionAsset(v, Filename("foo-1.0.jar")))

            val engine =
                MavenSyncEngine(
                    source,
                    target,
                    defaultOptions(transferChecksums = true, transferSignatures = false),
                    SyncMetrics(),
                )

            engine.handleArtifact(ArtifactMetadata(group, artifact, listOf(ArtifactVersion("1.0"))))

            source.listAssetCalls shouldContainExactly listOf(Triple(v, true, false))
        }

        test("records in-sync versions in metrics when target already has them") {
            val source = FakeMavenHttpRepository("source")
            val target = FakeMavenHttpRepository("target")
            target.seedMetadata(
                ArtifactMetadata(
                    group,
                    artifact,
                    listOf(ArtifactVersion("1.0"), ArtifactVersion("1.1")),
                )
            )
            val metrics = SyncMetrics()
            val engine = MavenSyncEngine(source, target, defaultOptions(), metrics)

            engine.handleArtifact(
                ArtifactMetadata(
                    group,
                    artifact,
                    listOf(ArtifactVersion("1.0"), ArtifactVersion("1.1")),
                )
            )

            val report = metrics.snapshot()
            report.inSyncTotal shouldBe 2
            report.syncedTotal shouldBe 0
            report.failedTotal shouldBe 0
        }

        test("records synced versions with asset count and bytes") {
            val source = FakeMavenHttpRepository("source")
            val target = FakeMavenHttpRepository("target")
            val v11 = coords("1.1")
            source.assets[v11] =
                listOf(
                    ArtifactVersionAsset(v11, Filename("foo-1.1.jar")),
                    ArtifactVersionAsset(v11, Filename("foo-1.1.pom")),
                )
            source.copyAssetBehavior = { 100L }
            val metrics = SyncMetrics()
            val engine = MavenSyncEngine(source, target, defaultOptions(), metrics)

            engine.handleArtifact(ArtifactMetadata(group, artifact, listOf(ArtifactVersion("1.1"))))

            val report = metrics.snapshot()
            report.syncedTotal shouldBe 1
            report.assetsTotal shouldBe 2
            report.bytesTotal shouldBe 200L
        }

        test("records failure and continues to the next version when copyAsset throws") {
            val source = FakeMavenHttpRepository("source")
            val target = FakeMavenHttpRepository("target")
            val v11 = coords("1.1")
            val v20 = coords("2.0")
            source.assets[v11] = listOf(ArtifactVersionAsset(v11, Filename("foo-1.1.jar")))
            source.assets[v20] = listOf(ArtifactVersionAsset(v20, Filename("foo-2.0.jar")))
            source.copyAssetBehavior = { asset ->
                if (asset.coordinates == v11) error("boom") else 50L
            }
            val metrics = SyncMetrics()
            val engine = MavenSyncEngine(source, target, defaultOptions(), metrics)

            engine.handleArtifact(
                ArtifactMetadata(
                    group,
                    artifact,
                    listOf(ArtifactVersion("1.1"), ArtifactVersion("2.0")),
                )
            )

            val report = metrics.snapshot()
            report.syncedTotal shouldBe 1
            report.failedTotal shouldBe 1
            target.releaseCalls shouldContainExactly listOf(v20)
        }
    })
