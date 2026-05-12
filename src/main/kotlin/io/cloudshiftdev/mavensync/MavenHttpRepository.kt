package io.cloudshiftdev.mavensync

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.plugins.auth.providers.*
import io.ktor.http.*
import java.nio.file.Path
import kotlin.time.Clock
import kotlin.time.Duration
import kotlinx.coroutines.flow.Flow

private val logger = KotlinLogging.logger {}

internal interface MavenHttpRepository : AutoCloseable {

    fun crawl(paths: List<String>, crawlDelay: Duration): Flow<ArtifactMetadata>

    suspend fun queryArtifactMetadata(group: Group, artifact: Artifact): ArtifactMetadata

    suspend fun listArtifactVersionAssets(
        coordinates: Coordinates,
        includeChecksums: Boolean,
        includeSignatures: Boolean,
    ): List<ArtifactVersionAsset>

    suspend fun copyAsset(asset: ArtifactVersionAsset, targetRepository: MavenHttpRepository)

    suspend fun uploadAsset(asset: ArtifactVersionAsset, file: Path)

    suspend fun releaseVersion(coordinates: Coordinates)

    companion object {
        fun create(
            url: String,
            credentials: RepositoryCredentials? = null,
            logHttpHeaders: Boolean = false,
            trustAllCerts: Boolean = true,
            clock: Clock = SystemClock,
        ): MavenHttpRepository {
            val theBaseUrl = Url(url.normalizeUrlPath())
            val httpClient =
                MavenHttpClient(
                    config =
                        HttpClientConfig(
                            logHttpHeaders = logHttpHeaders,
                            trustAllCerts = trustAllCerts,
                        ),
                    credentials =
                        credentials?.let { BasicAuthCredentials(it.username, it.password) },
                )
            return DefaultMavenHttpRepository(theBaseUrl, httpClient, clock)
        }
    }
}

internal class DefaultMavenHttpRepository(
    private val repoUrl: Url,
    private val mavenHttpClient: MavenHttpClient,
    private val clock: Clock = SystemClock,
) : MavenHttpRepository {

    private val crawler = MavenRepositoryCrawler(mavenHttpClient, repoUrl)

    override fun close() {
        mavenHttpClient.close()
    }

    override fun crawl(paths: List<String>, crawlDelay: Duration): Flow<ArtifactMetadata> =
        crawler.crawl(paths, crawlDelay)

    override suspend fun queryArtifactMetadata(group: Group, artifact: Artifact): ArtifactMetadata {
        return mavenHttpClient.fetchArtifactMetadata(metadataUrl(group, artifact))
            ?: ArtifactMetadata(group = group, artifact = artifact, artifactVersions = emptyList())
    }

    override suspend fun listArtifactVersionAssets(
        coordinates: Coordinates,
        includeChecksums: Boolean,
        includeSignatures: Boolean,
    ): List<ArtifactVersionAsset> {
        val baseName = "${coordinates.artifact.value}-${coordinates.artifactVersion.value}"
        val url = url(coordinates)
        logger.debug { "Listing assets for: $coordinates @ $url" }
        return mavenHttpClient
            .parseDirectoryListing(url)
            .filter { !it.isDirectory }
            .map { it.filename }
            .filter {
                when {
                    !it.value.startsWith(baseName) -> false
                    it.isChecksum -> includeChecksums
                    it.isSignature -> includeSignatures
                    else -> true
                }
            }
            .map { ArtifactVersionAsset(coordinates, it) }
            .toList()
            .also { logger.debug { "Found assets for $it" } }
    }

    override suspend fun copyAsset(
        asset: ArtifactVersionAsset,
        targetRepository: MavenHttpRepository,
    ) {
        mavenHttpClient.download(url(asset.coordinates, asset.name)) { _, file ->
            targetRepository.uploadAsset(asset, file.toPath())
        }
    }

    override suspend fun uploadAsset(asset: ArtifactVersionAsset, file: Path) {
        mavenHttpClient.upload(url(asset.coordinates, asset.name), file.toFile())
    }

    override suspend fun releaseVersion(coordinates: Coordinates) {
        // get maven-metadata.xml from target
        val metadata = queryArtifactMetadata(coordinates.group, coordinates.artifact)
        val versions = metadata.artifactVersions + coordinates.artifactVersion

        val metadataXml =
            MavenMetadataXml(
                group = coordinates.group.value,
                artifactId = coordinates.artifact.value,
                versions = versions.map { it.value },
                lastUpdated = clock.now().toMavenLastUpdated(),
                latest = coordinates.artifactVersion.value,
                release = coordinates.artifactVersion.value,
            )

        mavenHttpClient.upload(
            metadataUrl(coordinates.group, coordinates.artifact),
            metadataXml.toXml(),
        )
    }

    private val Group.pathSegments: List<String>
        get() = value.split('.')

    private fun url(coordinates: Coordinates, assetName: Filename? = null): Url {
        val builder =
            URLBuilder(repoUrl)
                .appendPathSegments(coordinates.group.pathSegments)
                .appendPathSegments(coordinates.artifact.value, coordinates.artifactVersion.value)
        if (assetName != null) builder.appendPathSegments(assetName.value)
        else builder.appendPathSegments("")
        return builder.build()
    }

    private fun metadataUrl(group: Group, artifact: Artifact) =
        URLBuilder(repoUrl)
            .appendPathSegments(group.pathSegments)
            .appendPathSegments(artifact.value, "maven-metadata.xml")
            .build()
}
