package io.cloudshiftdev.mavensync

import io.github.oshai.kotlinlogging.KotlinLogging
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
            .forEach { coordinates ->
                val assets =
                    source.listArtifactVersionAssets(
                        coordinates,
                        options.transferChecksums,
                        options.transferSignatures,
                    )

                if (assets.isNotEmpty()) {
                    assets.forEach { asset -> source.copyAsset(asset, target) }

                    target.releaseVersion(coordinates)

                    delay(options.downloadDelay)
                }
            }
    }
}
