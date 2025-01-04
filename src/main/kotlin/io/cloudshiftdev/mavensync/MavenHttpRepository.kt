package io.cloudshiftdev.mavensync

import com.google.common.xml.XmlEscapers
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.plugins.auth.providers.*
import io.ktor.http.*
import java.nio.file.Path
import kotlin.time.Duration
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay

private val logger = KotlinLogging.logger {}

internal interface MavenHttpRepository : AutoCloseable {

    suspend fun crawlArtifactMetadata(
        channel: SendChannel<ArtifactMetadata>,
        crawlDelay: Duration,
        paths: List<String>
    )

    suspend fun queryArtifactMetadata(group: Group, artifact: Artifact): ArtifactMetadata

    suspend fun listArtifactVersionAssets(
        coordinates: Coordinates,
        includeChecksums: Boolean,
        includeSignatures: Boolean
    ): List<ArtifactVersionAsset>

    suspend fun copyAsset(asset: ArtifactVersionAsset, targetRepository: MavenHttpRepository)

    suspend fun uploadAsset(asset: ArtifactVersionAsset, file: Path)

    suspend fun releaseVersion(coordinates: Coordinates)

    companion object {
        fun create(
            url: String,
            credentials: RepositoryCredentials? = null,
            logHttpHeaders: Boolean = false
        ): MavenHttpRepository {
            val theBaseUrl =
                when {
                    url.endsWith("/") -> Url(url)
                    else -> Url("$url/")
                }
            return DefaultMavenHttpRepository(theBaseUrl, credentials, logHttpHeaders)
        }
    }
}

private class DefaultMavenHttpRepository(
    private val repoUrl: Url,
    credentials: RepositoryCredentials?,
    logHttpHeaders: Boolean,
) : MavenHttpRepository {
    private val mavenHttpClient =
        MavenHttpClient(
            logHttpHeaders,
            credentials?.let { BasicAuthCredentials(it.username, it.password) }
        )

    override fun close() {
        mavenHttpClient.close()
    }

    override suspend fun crawlArtifactMetadata(
        channel: SendChannel<ArtifactMetadata>,
        crawlDelay: Duration,
        paths: List<String>
    ) {
        if (paths.isEmpty()) {
            crawlDirectoryListings(repoUrl, channel, crawlDelay)
        } else {
            paths.forEach { path ->
                val startUrl =
                    URLBuilder(repoUrl)
                        .apply { appendPathSegments(path.normalizeUrlPath()) }
                        .build()
                crawlDirectoryListings(startUrl, channel, crawlDelay)
            }
        }
    }

    private fun String.normalizeUrlPath(): String {
        return when {
            endsWith('/') -> this
            else -> "$this/"
        }
    }

    override suspend fun queryArtifactMetadata(group: Group, artifact: Artifact): ArtifactMetadata {
        return readPossibleArtifactMetadata(metadataUrl(group, artifact))
            ?: ArtifactMetadata(
                group = group,
                artifact = artifact,
                artifactVersions = emptyList(),
            )
    }

    override suspend fun listArtifactVersionAssets(
        coordinates: Coordinates,
        includeChecksums: Boolean,
        includeSignatures: Boolean
    ): List<ArtifactVersionAsset> {
        val baseName = "${coordinates.artifact.value}-${coordinates.artifactVersion.value}"
        val url = url(coordinates)
        logger.debug { "Listing assets for: $coordinates @ $url" }
        return mavenHttpClient
            .parseChildLinks(url)
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
        targetRepository: MavenHttpRepository
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

        // update maven-metadata.xml with new version
        val metadataXmlHolder =
            MavenMetadataXml(
                group = coordinates.group.value,
                artifactId = coordinates.artifact.value,
                versions = versions.map { it.value },
                lastUpdated = "20241224173330", // TODO
                latest = coordinates.artifactVersion.value,
                release = coordinates.artifactVersion.value
            )

        val metadataXml = metadataXmlHolder.toXml()

        // PUT maven-metadata.xml to target
        mavenHttpClient.upload(metadataUrl(coordinates.group, coordinates.artifact), metadataXml)
    }

    private suspend fun crawlDirectoryListings(
        url: Url,
        channel: SendChannel<ArtifactMetadata>,
        crawlDelay: Duration
    ) {
        logger.info { "Reading index: $url" }
        val recurseDirs = mutableListOf<Url>()

        mavenHttpClient.parseChildLinks(url).forEach {
            when {
                it.isDirectory -> recurseDirs.add(it)
                it.filename.value == MavenSpec.MavenMetadataXmlFile -> {
                    readPossibleArtifactMetadata(it)?.let {
                        channel.send(it)
                        return@crawlDirectoryListings
                    }
                }
                it.filename.isPom -> {
                    logger.warn { "Missing maven-metadata.xml for: $it" }
                    return@crawlDirectoryListings
                }
                it.filename.isChecksum -> logger.debug { "Ignoring checksum: $it" }
                it.filename.isSignature -> logger.debug { "Ignoring signature: $it" }
                it.filename.value in MavenSpec.IgnoredFiles -> logger.debug { "Ignoring: $it" }
                else ->
                    logger.warn {
                        "Ignoring unknown artifact (likely missing maven-metadata.xml): $it"
                    }
            }
        }

        recurseDirs.forEach {
            delay(crawlDelay)
            crawlDirectoryListings(it, channel, crawlDelay)
        }
    }

    private suspend fun readPossibleArtifactMetadata(url: Url): ArtifactMetadata? {
        val doc =
            try {
                mavenHttpClient.parseContent(url)
            } catch (_: MissingContentException) {
                null
            } ?: return null

        val groupId = doc.select("groupId").firstOrNull()
        val artifactId = doc.select("artifactId").firstOrNull()
        val artifactVersions = doc.select("version").map { ArtifactVersion(it.text()) }

        val artifactMetadata =
            ArtifactMetadata(
                group = Group(groupId?.text() ?: error("Invalid group id: $doc")),
                artifact = Artifact(artifactId?.text() ?: error("Invalid artifact id: $doc")),
                artifactVersions = artifactVersions,
            )
        return artifactMetadata
    }

    private val Url.isDirectory: Boolean
        get() = encodedPath.endsWith("/")

    private val Url.filename: Filename
        get() = Filename(encodedPath.substringAfterLast("/"))

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

/**
 * 2024-12-24T09:33:30.618-0800 [DEBUG] [org.apache.http.wire] http-outgoing-1 >> "<?xml
 * version="1.0" encoding="UTF-8"?>[\n]" 2024-12-24T09:33:30.618-0800 [DEBUG] [org.apache.http.wire]
 * http-outgoing-1 >> "<metadata>[\n]" 2024-12-24T09:33:30.618-0800 [DEBUG] [org.apache.http.wire]
 * http-outgoing-1 >> " <groupId>io.cloudshiftdev.foo</groupId>[\n]" 2024-12-24T09:33:30.618-0800
 * [DEBUG] [org.apache.http.wire] http-outgoing-1 >> " <artifactId>bar.baz</artifactId>[\n]"
 * 2024-12-24T09:33:30.618-0800 [DEBUG] [org.apache.http.wire] http-outgoing-1 >> "
 * <versioning>[\n]" 2024-12-24T09:33:30.618-0800 [DEBUG] [org.apache.http.wire] http-outgoing-1 >>
 * " <latest>1.2.5</latest>[\n]" 2024-12-24T09:33:30.618-0800 [DEBUG] [org.apache.http.wire]
 * http-outgoing-1 >> " <release>1.2.5</release>[\n]" 2024-12-24T09:33:30.618-0800 [DEBUG]
 * [org.apache.http.wire] http-outgoing-1 >> " <versions>[\n]" 2024-12-24T09:33:30.618-0800 [DEBUG]
 * [org.apache.http.wire] http-outgoing-1 >> " <version>1.2.3</version>[\n]"
 * 2024-12-24T09:33:30.618-0800 [DEBUG] [org.apache.http.wire] http-outgoing-1 >> "
 * <version>1.2.4</version>[\n]" 2024-12-24T09:33:30.618-0800 [DEBUG] [org.apache.http.wire]
 * http-outgoing-1 >> " <version>1.2.5</version>[\n]" 2024-12-24T09:33:30.618-0800 [DEBUG]
 * [org.apache.http.wire] http-outgoing-1 >> " </versions>[\n]" 2024-12-24T09:33:30.618-0800 [DEBUG]
 * [org.apache.http.wire] http-outgoing-1 >> " <lastUpdated>20241224173330</lastUpdated>[\n]"
 * 2024-12-24T09:33:30.618-0800 [DEBUG] [org.apache.http.wire] http-outgoing-1 >> "
 * </versioning>[\n]" 2024-12-24T09:33:30.618-0800 [DEBUG] [org.apache.http.wire] http-outgoing-1 >>
 * "</metadata>[\n]"
 */
private data class MavenMetadataXml(
    val group: String,
    val artifactId: String,
    val versions: List<String>,
    val lastUpdated: String,
    val latest: String,
    val release: String
) {
    fun toXml(): String {
        val escaper = XmlEscapers.xmlContentEscaper()
        return buildString(1024 + (100 * versions.size)) {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<metadata>\n")
            append("  <groupId>${escaper.escape(group)}</groupId>\n")
            append("  <artifactId>${escaper.escape(artifactId)}</artifactId>\n")
            append("  <versioning>\n")
            append("    <latest>${escaper.escape(latest)}</latest>\n")
            append("    <release>${escaper.escape(release)}</release>\n")
            append("    <versions>\n")
            versions.forEach { version ->
                append("      <version>${escaper.escape(version)}</version>\n")
            }
            append("    </versions>\n")
            append("    <lastUpdated>${escaper.escape(lastUpdated)}</lastUpdated>\n")
            append("  </versioning>\n")
            append("</metadata>\n")
        }
    }
}
