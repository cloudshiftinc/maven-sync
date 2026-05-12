package io.cloudshiftdev.mavensync

import java.nio.file.Path
import kotlin.time.Duration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

internal class FakeMavenHttpRepository(private val label: String) : MavenHttpRepository {

    val metadata = mutableMapOf<Pair<Group, Artifact>, ArtifactMetadata>()
    val assets = mutableMapOf<Coordinates, List<ArtifactVersionAsset>>()

    val listAssetCalls = mutableListOf<Triple<Coordinates, Boolean, Boolean>>()
    val copyCalls = mutableListOf<Pair<ArtifactVersionAsset, MavenHttpRepository>>()
    val uploadCalls = mutableListOf<Pair<ArtifactVersionAsset, Path>>()
    val releaseCalls = mutableListOf<Coordinates>()

    fun seedMetadata(md: ArtifactMetadata) {
        metadata[md.group to md.artifact] = md
    }

    override fun crawl(paths: List<String>, crawlDelay: Duration): Flow<ArtifactMetadata> =
        emptyFlow()

    override suspend fun queryArtifactMetadata(group: Group, artifact: Artifact): ArtifactMetadata =
        metadata[group to artifact]
            ?: ArtifactMetadata(group = group, artifact = artifact, artifactVersions = emptyList())

    override suspend fun listArtifactVersionAssets(
        coordinates: Coordinates,
        includeChecksums: Boolean,
        includeSignatures: Boolean,
    ): List<ArtifactVersionAsset> {
        listAssetCalls += Triple(coordinates, includeChecksums, includeSignatures)
        return assets[coordinates].orEmpty()
    }

    var copyAssetBehavior: suspend (ArtifactVersionAsset) -> Long = { 0L }

    override suspend fun copyAsset(
        asset: ArtifactVersionAsset,
        targetRepository: MavenHttpRepository,
    ): Long {
        copyCalls += asset to targetRepository
        return copyAssetBehavior(asset)
    }

    override suspend fun uploadAsset(asset: ArtifactVersionAsset, file: Path): Long {
        uploadCalls += asset to file
        return 0L
    }

    override suspend fun releaseVersion(coordinates: Coordinates) {
        releaseCalls += coordinates
    }

    override fun close() {}

    override fun toString(): String = "FakeMavenHttpRepository($label)"
}
