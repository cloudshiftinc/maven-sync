package io.cloudshiftdev.mavensync

import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.TimeSource

internal class SyncMetrics {
    private val mark = TimeSource.Monotonic.markNow()
    private val artifacts = ConcurrentHashMap<Pair<Group, Artifact>, Entry>()

    fun recordInSync(group: Group, artifact: Artifact, versions: Collection<ArtifactVersion>) {
        if (versions.isEmpty()) return
        entry(group, artifact).inSync.addAll(versions)
    }

    fun recordSynced(coordinates: Coordinates, assetCount: Int, bytes: Long, duration: Duration) {
        entry(coordinates.group, coordinates.artifact)
            .synced
            .add(
                VersionResult.Success(
                    version = coordinates.artifactVersion,
                    assetCount = assetCount,
                    bytes = bytes,
                    duration = duration,
                )
            )
    }

    fun recordFailure(coordinates: Coordinates, error: String, duration: Duration) {
        entry(coordinates.group, coordinates.artifact)
            .failed
            .add(
                VersionResult.Failure(
                    version = coordinates.artifactVersion,
                    error = error,
                    duration = duration,
                )
            )
    }

    fun snapshot(): SyncReport {
        val artifactMetrics =
            artifacts.values
                .map { e ->
                    ArtifactMetrics(
                        group = e.group,
                        artifact = e.artifact,
                        inSync = e.inSync.toList(),
                        synced = e.synced.toList(),
                        failed = e.failed.toList(),
                    )
                }
                .sortedWith(compareBy({ it.group.value }, { it.artifact.value }))
        return SyncReport(totalDuration = mark.elapsedNow(), artifacts = artifactMetrics)
    }

    private fun entry(group: Group, artifact: Artifact): Entry =
        artifacts.computeIfAbsent(group to artifact) { Entry(group, artifact) }

    private class Entry(val group: Group, val artifact: Artifact) {
        val inSync: MutableList<ArtifactVersion> = mutableListOf()
        val synced: MutableList<VersionResult.Success> = mutableListOf()
        val failed: MutableList<VersionResult.Failure> = mutableListOf()
    }
}

internal data class ArtifactMetrics(
    val group: Group,
    val artifact: Artifact,
    val inSync: List<ArtifactVersion>,
    val synced: List<VersionResult.Success>,
    val failed: List<VersionResult.Failure>,
)

internal sealed interface VersionResult {
    val version: ArtifactVersion
    val duration: Duration

    data class Success(
        override val version: ArtifactVersion,
        val assetCount: Int,
        val bytes: Long,
        override val duration: Duration,
    ) : VersionResult

    data class Failure(
        override val version: ArtifactVersion,
        val error: String,
        override val duration: Duration,
    ) : VersionResult
}

internal data class SyncReport(val totalDuration: Duration, val artifacts: List<ArtifactMetrics>) {
    val inSyncTotal: Int = artifacts.sumOf { it.inSync.size }
    val syncedTotal: Int = artifacts.sumOf { it.synced.size }
    val failedTotal: Int = artifacts.sumOf { it.failed.size }
    val assetsTotal: Int = artifacts.sumOf { a -> a.synced.sumOf { it.assetCount } }
    val bytesTotal: Long = artifacts.sumOf { a -> a.synced.sumOf { it.bytes } }

    fun renderDetailed(): String =
        buildString {
                appendLine("========== SYNC METRICS (detailed) ==========")
                if (artifacts.isEmpty()) {
                    appendLine("(no artifacts processed)")
                    return@buildString
                }
                artifacts.forEach { a ->
                    appendLine("${a.group.value}:${a.artifact.value}")
                    if (a.inSync.isNotEmpty()) {
                        appendLine(
                            "  in sync (${a.inSync.size}): ${a.inSync.joinToString(", ") { it.value }}"
                        )
                    }
                    if (a.synced.isNotEmpty()) {
                        appendLine("  synced (${a.synced.size}):")
                        a.synced.forEach { s ->
                            appendLine(
                                "    ${s.version.value} — ${s.assetCount} assets, ${formatBytes(s.bytes)}, ${s.duration}"
                            )
                        }
                    }
                    if (a.failed.isNotEmpty()) {
                        appendLine("  failed (${a.failed.size}):")
                        a.failed.forEach { f ->
                            appendLine("    ${f.version.value} — ${f.error} (${f.duration})")
                        }
                    }
                }
            }
            .trimEnd()

    fun renderSummary(): String = buildString {
        appendLine("========== SYNC METRICS (summary) ==========")
        appendLine("artifacts:         ${artifacts.size}")
        appendLine("versions in sync:  $inSyncTotal")
        appendLine("versions synced:   $syncedTotal")
        appendLine("versions failed:   $failedTotal")
        appendLine("assets copied:     $assetsTotal")
        appendLine("bytes transferred: ${formatBytes(bytesTotal)}")
        append("duration:          $totalDuration")
    }
}

internal fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024.0
    var idx = 0
    while (value >= 1024.0 && idx < units.lastIndex) {
        value /= 1024.0
        idx++
    }
    return "%.1f %s".format(value, units[idx])
}
