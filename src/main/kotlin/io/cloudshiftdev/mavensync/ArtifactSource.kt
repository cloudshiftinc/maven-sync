package io.cloudshiftdev.mavensync

import io.github.oshai.kotlinlogging.KotlinLogging
import java.lang.AutoCloseable
import java.nio.file.Path
import kotlinx.coroutines.channels.SendChannel

private val logger = KotlinLogging.logger {}

internal interface ArtifactSource : AutoCloseable {
    suspend fun visitArtifactMetadata(channel: SendChannel<ArtifactMetadata>)

    suspend fun listArtifactVersionAssets(coordinates: Coordinates): List<ArtifactVersionAsset>

    suspend fun downloadAsset(asset: ArtifactVersionAsset, file: Path)
}
/*
internal class WebCrawlingArtifactSource : ArtifactSource {
    private val httpClient: HttpClient

    override suspend fun visitArtifactMetadata(channel: SendChannel<ArtifactMetadata>) {
        TODO("Not yet implemented")
    }

    override suspend fun listArtifactVersionAssets(
        coordinates: Coordinates
    ): List<ArtifactVersionAsset> {
        TODO("Not yet implemented")
    }

    override suspend fun downloadAsset(asset: ArtifactVersionAsset, file: Path) {
        httpClient.prepareGet(url(asset.coordinates, asset.name)).execute { response ->
            if (response.status.isSuccess()) {
                file.outputStream().buffered().use { outputStream ->
                    response.bodyAsChannel().copyTo(outputStream)
                }
                true
            } else {
                logger.warn { "Failed to download asset: $asset ${response.status}" }
                false
            }
        }
    }

    override fun close() {
        TODO("Not yet implemented")
    }

    private suspend fun crawlDirectoryListings(
        url: Url,
        channel: SendChannel<ArtifactMetadata>,
        crawlDelay: Duration
    ) {
        logger.info { "Reading index: $url" }
        val recurseDirs = mutableListOf<Url>()

        httpClient.parseChildLinks(url).forEach {
            when {
                it.isDirectory -> recurseDirs.add(it)
                it.filename.value == MavenSpec.MavenMetadataXmlFile -> {
                    readPossibleArtifactMetadata(it)?.let {
                        channel.send(it)
                        return@crawlDirectoryListings
                    }
                }
                it.filename.isChecksum -> logger.debug { "Ignoring checksum: ${it}" }
                it.filename.isSignature -> logger.debug { "Ignoring signature: ${it}" }
                it.filename.value in MavenSpec.IgnoredFiles -> logger.debug { "Ignoring: ${it}" }
                else ->
                    logger.warn {
                        "Ignoring unknown artifact (likely missing maven-metadata.xml): ${it}"
                    }
            }
        }

        recurseDirs.forEach {
            delay(crawlDelay)
            crawlDirectoryListings(it, channel, crawlDelay)
        }
    }
}*/
