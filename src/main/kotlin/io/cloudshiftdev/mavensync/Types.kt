package io.cloudshiftdev.mavensync

internal data class ArtifactMetadata(
    val group: Group,
    val artifact: Artifact,
    val artifactVersions: List<ArtifactVersion>,
)

@JvmInline
internal value class Group(val value: String) {
    init {
        require(value.isNotBlank()) { "Group cannot be blank" }
        require(!value.startsWith(".")) { "Group cannot start with a dot: $value" }
    }

    override fun toString(): String = value
}

@JvmInline
internal value class Artifact(val value: String) {
    override fun toString(): String = value
}

@JvmInline
internal value class ArtifactVersion(val value: String) {
    override fun toString(): String = value

    val isSnapshot: Boolean
        get() = value.endsWith("-SNAPSHOT")
}

internal data class Coordinates(
    val group: Group,
    val artifact: Artifact,
    val artifactVersion: ArtifactVersion,
) {
    override fun toString(): String {
        return "${group.value}:${artifact.value}:${artifactVersion.value}"
    }
}

internal data class ArtifactVersionAsset(val coordinates: Coordinates, val name: Filename)

@JvmInline
internal value class Filename(val value: String) {
    override fun toString(): String = value

    val extension: String
        get() = value.substringAfterLast(".")

    val isPom: Boolean
        get() = extension == "pom"

    val isChecksum: Boolean
        get() = extension in MavenSpec.ChecksumExtensions

    val isSignature: Boolean
        get() = extension in MavenSpec.SignatureExtensions
}

internal object MavenSpec {
    val ChecksumExtensions = setOf("md5", "sha1", "sha256", "sha512")
    val SignatureExtensions = setOf("asc")
    const val MavenMetadataXmlFile = "maven-metadata.xml"

    val IgnoredFiles = setOf("archetype-catalog.xml", "last_updated.txt", "robots.txt")
}
