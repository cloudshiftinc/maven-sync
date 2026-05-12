package io.cloudshiftdev.mavensync

import com.fleeksoft.ksoup.nodes.Document
import io.ktor.http.Url

internal object MavenMetadataXmlParser {
    fun parse(doc: Document): ArtifactMetadata {
        val groupId = doc.select("groupId").firstOrNull()
        val artifactId = doc.select("artifactId").firstOrNull()
        val artifactVersions =
            doc.select("version").map { ArtifactVersion(it.text()) }.filterNot { it.isSnapshot }

        return ArtifactMetadata(
            group = Group(groupId?.text() ?: error("Invalid group id: $doc")),
            artifact = Artifact(artifactId?.text() ?: error("Invalid artifact id: $doc")),
            artifactVersions = artifactVersions,
        )
    }
}

internal suspend fun MavenHttpClient.fetchArtifactMetadata(url: Url): ArtifactMetadata? {
    val doc =
        try {
            parseContent(url)
        } catch (_: MissingContentException) {
            return null
        }
    return MavenMetadataXmlParser.parse(doc)
}
