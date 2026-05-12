package io.cloudshiftdev.mavensync

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val g = Group("com.example")
private val a = Artifact("foo")

private fun v(s: String) = ArtifactVersion(s)

private fun c(s: String) = Coordinates(g, a, v(s))

class SyncMetricsTest :
    FunSpec({
        test("empty snapshot has zero totals") {
            val report = SyncMetrics().snapshot()
            report.inSyncTotal shouldBe 0
            report.syncedTotal shouldBe 0
            report.failedTotal shouldBe 0
            report.assetsTotal shouldBe 0
            report.bytesTotal shouldBe 0L
        }

        test("aggregates in-sync, synced and failed totals across artifacts") {
            val metrics = SyncMetrics()
            metrics.recordInSync(g, a, listOf(v("1.0"), v("1.1")))
            metrics.recordSynced(
                c("1.2"),
                assetCount = 3,
                bytes = 1024L,
                duration = 500.milliseconds,
            )
            metrics.recordSynced(c("1.3"), assetCount = 2, bytes = 2048L, duration = 1.seconds)
            metrics.recordFailure(c("1.4"), error = "HTTP 500", duration = 200.milliseconds)

            val report = metrics.snapshot()
            report.inSyncTotal shouldBe 2
            report.syncedTotal shouldBe 2
            report.failedTotal shouldBe 1
            report.assetsTotal shouldBe 5
            report.bytesTotal shouldBe 3072L
        }

        test("groups results by artifact and preserves order of records within an artifact") {
            val metrics = SyncMetrics()
            metrics.recordInSync(g, a, listOf(v("1.0")))
            metrics.recordSynced(c("1.1"), 1, 10L, 1.seconds)
            metrics.recordSynced(c("1.2"), 1, 20L, 1.seconds)
            metrics.recordFailure(c("1.3"), "bad", 1.seconds)

            val report = metrics.snapshot()
            report.artifacts.size shouldBe 1
            val am = report.artifacts.single()
            am.inSync.map { it.value } shouldContainExactlyInAnyOrder listOf("1.0")
            am.synced.map { it.version.value } shouldBe listOf("1.1", "1.2")
            am.failed.map { it.version.value } shouldBe listOf("1.3")
        }

        test("renderDetailed and renderSummary include the expected fields") {
            val metrics = SyncMetrics()
            metrics.recordInSync(g, a, listOf(v("1.0")))
            metrics.recordSynced(c("1.1"), 2, 1024L, 1500.milliseconds)
            metrics.recordFailure(c("1.2"), "HTTP 500", 200.milliseconds)

            val report = metrics.snapshot()
            val detailed = report.renderDetailed()
            detailed shouldContain "com.example:foo"
            detailed shouldContain "in sync (1)"
            detailed shouldContain "1.0"
            detailed shouldContain "synced (1)"
            detailed shouldContain "1.1 — 2 assets"
            detailed shouldContain "failed (1)"
            detailed shouldContain "HTTP 500"

            val summary = report.renderSummary()
            summary shouldContain "artifacts:         1"
            summary shouldContain "versions in sync:  1"
            summary shouldContain "versions synced:   1"
            summary shouldContain "versions failed:   1"
            summary shouldContain "assets copied:     2"
        }

        test("renderDetailed handles an empty report") {
            SyncMetrics().snapshot().renderDetailed() shouldContain "(no artifacts processed)"
        }
    })
