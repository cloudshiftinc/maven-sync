package io.cloudshiftdev.mavensync

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

internal class MavenSyncEngine(
    private val source: MavenHttpRepository,
    private val target: MavenHttpRepository,
    private val options: SyncOptions,
    private val metrics: SyncMetrics,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun sync() = coroutineScope {
        val artifactChannel =
            source
                .crawl(options.paths, options.crawlDelay)
                .buffer(options.artifactConcurrency)
                .produceIn(this)
        repeat(options.artifactConcurrency) {
            launch { artifactChannel.consumeEach { handleArtifact(it) } }
        }
    }

    internal suspend fun handleArtifact(metadata: ArtifactMetadata) {
        val targetMetadata = target.queryArtifactMetadata(metadata.group, metadata.artifact)
        val sourceVersions = metadata.artifactVersions.toSet()
        val targetVersions = targetMetadata.artifactVersions.toSet()
        val inSyncVersions = sourceVersions intersect targetVersions
        metrics.recordInSync(metadata.group, metadata.artifact, inSyncVersions)

        val missingVersions = sourceVersions - targetVersions
        if (missingVersions.isEmpty()) {
            logger.debug { "No missing versions for ${metadata.group}:${metadata.artifact}" }
            return
        }
        logger.info {
            "Syncing missing versions for ${metadata.group}:${metadata.artifact}: $missingVersions"
        }
        missingVersions
            .map { Coordinates(metadata.group, metadata.artifact, it) }
            .forEach { coordinates -> syncVersion(coordinates) }
    }

    private suspend fun syncVersion(coordinates: Coordinates) {
        val mark = TimeSource.Monotonic.markNow()
        try {
            val assets =
                source.listArtifactVersionAssets(
                    coordinates,
                    options.transferChecksums,
                    options.transferSignatures,
                )

            if (assets.isEmpty()) return

            var bytes = 0L
            assets.forEach { asset -> bytes += source.copyAsset(asset, target) }
            target.releaseVersion(coordinates)
            metrics.recordSynced(
                coordinates = coordinates,
                assetCount = assets.size,
                bytes = bytes,
                duration = mark.elapsedNow(),
            )

            delay(options.downloadDelay)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = e.message ?: e.toString()
            logger.error(e) { "Failed to sync $coordinates: $msg" }
            metrics.recordFailure(coordinates, msg, mark.elapsedNow())
        }
    }
}
