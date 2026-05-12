package io.cloudshiftdev.mavensync

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.*
import kotlin.time.Duration
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import org.apache.maven.artifact.versioning.DefaultArtifactVersion

private val logger = KotlinLogging.logger {}

internal class MavenRepositoryCrawler(
    private val httpClient: MavenHttpClient,
    private val repoUrl: Url,
) {
    fun crawl(paths: List<String>, crawlDelay: Duration): Flow<ArtifactMetadata> = channelFlow {
        if (paths.isEmpty()) {
            crawlDirectoryListings(repoUrl, crawlDelay)
        } else {
            paths.forEach { path ->
                val startUrl =
                    URLBuilder(repoUrl)
                        .apply { appendPathSegments(path.normalizeUrlPath()) }
                        .build()
                crawlDirectoryListings(startUrl, crawlDelay)
            }
        }
    }

    private suspend fun ProducerScope<ArtifactMetadata>.crawlDirectoryListings(
        url: Url,
        crawlDelay: Duration,
    ) {
        logger.info { "Reading index: $url" }
        val childLinks = httpClient.parseDirectoryListing(url)

        logger.debug { "Found ${childLinks.size} links in $url" }

        val mavenMetadata =
            childLinks
                .firstOrNull { it.filename.value == MavenSpec.MavenMetadataXmlFile }
                ?.let { httpClient.fetchArtifactMetadata(it) }

        if (mavenMetadata != null && mavenMetadata.artifactVersions.isNotEmpty()) {
            logger.debug { "Found maven-metadata.xml in: $url" }
            channel.send(mavenMetadata)
            return
        }

        val dirs = childLinks.filter { it.isDirectory }
        logger.debug { "Found ${dirs.size} directories in $url" }

        if (dirs.isNotEmpty()) {
            val versions = dirs.mapNotNull { artifactVersion(it.directoryName) }.sorted()
            if (versions.size == dirs.size) {
                logger.warn { "Missing maven-metadata.xml; synthesizing from versions: $url" }
                val relativeUrl =
                    url.segments
                        .joinToString("/")
                        .removePrefix(repoUrl.segments.joinToString("/"))
                        .removeSuffix("/")
                val artifact = relativeUrl.substringAfterLast("/")
                val group = relativeUrl.substringBeforeLast("/").removePrefix("/").replace("/", ".")
                val artifactMetadata =
                    ArtifactMetadata(
                        group = Group(group),
                        artifact = Artifact(artifact),
                        artifactVersions = versions.map { ArtifactVersion(it.toString()) },
                    )
                logger.debug { "Synthesized metadata: $artifactMetadata" }
                channel.send(artifactMetadata)
                return
            }
        }

        dirs.forEach {
            delay(crawlDelay)
            crawlDirectoryListings(it, crawlDelay)
        }
    }

    private val Url.directoryName: String
        get() = encodedPath.removeSuffix("/").substringAfterLast("/")
}

internal fun artifactVersion(
    version: String
): org.apache.maven.artifact.versioning.ArtifactVersion? {
    val parsedVersion = DefaultArtifactVersion(version)
    return when {
        parsedVersion.qualifier == version -> null
        else -> parsedVersion
    }
}
