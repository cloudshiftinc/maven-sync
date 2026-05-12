@file:OptIn(ExperimentalHoplite::class)

package io.cloudshiftdev.mavensync

import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.ExperimentalHoplite
import com.sksamuel.hoplite.addFileSource
import com.sksamuel.hoplite.addResourceSource
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.TreeMap

/**
 * Source-side integration test. Drives a real `MavenHttpRepository` against a user-supplied repo
 * URL (and optional credentials) and runs everything the sync engine does *up to but excluding*
 * the upload to the target: crawl, parse, target-diff (against an empty fake), and per-version
 * asset listing.
 *
 * Gated on the `MAVEN_SYNC_IT_CONFIG` env var (path to a JSON file using the standard [SyncConfig]
 * schema). When unset, the test is reported as disabled and no network calls are made.
 *
 * Optional `MAVEN_SYNC_IT_EXPECTED` env var: path to a previously-saved discovery report JSON to
 * compare against (snapshot mode).
 */
class MavenSyncIntegrationTest :
    FunSpec({
        val configPath = System.getenv("MAVEN_SYNC_IT_CONFIG")
        val enabled = !configPath.isNullOrBlank()

        test("discovers artifacts, versions, and assets from a real source repo")
            .config(enabled = enabled) {
                val config = loadIntegrationConfig(File(configPath!!))
                val options = config.toSyncOptions()

                config.source.toMavenHttpRepository().use { source ->
                    val target = FakeMavenHttpRepository("integration-test-target")
                    val report = DiscoveryReport()

                    source.crawl(options.paths, options.crawlDelay).collect { metadata ->
                        val targetMetadata =
                            target.queryArtifactMetadata(metadata.group, metadata.artifact)
                        val missingVersions =
                            metadata.artifactVersions.toSet() -
                                targetMetadata.artifactVersions.toSet()
                        missingVersions.forEach { version ->
                            val coordinates = Coordinates(metadata.group, metadata.artifact, version)
                            val assets =
                                source.listArtifactVersionAssets(
                                    coordinates,
                                    options.transferChecksums,
                                    options.transferSignatures,
                                )
                            report.add(coordinates, assets)
                        }
                    }

                    target.copyCalls.shouldBeEmpty()
                    target.uploadCalls.shouldBeEmpty()
                    target.releaseCalls.shouldBeEmpty()

                    val reportPath = writeReport(report)
                    println(
                        """
                        |Maven-sync integration test discovery report:
                        |  source:    ${config.source.url}
                        |  paths:     ${options.paths}
                        |  artifacts: ${report.artifactCount}
                        |  versions:  ${report.versionCount}
                        |  assets:    ${report.assetCount}
                        |  report:    $reportPath
                        """
                            .trimMargin()
                    )

                    withClue(
                        "No artifacts discovered — check source URL, credentials, and paths."
                    ) {
                        report.artifacts.keys.shouldNotBeEmpty()
                    }
                    report.artifacts.forEach { (artifactKey, versions) ->
                        withClue("Artifact $artifactKey has no versions") {
                            versions.keys.shouldNotBeEmpty()
                        }
                        versions.forEach { (version, assets) ->
                            withClue("Artifact $artifactKey version $version has no assets") {
                                assets.shouldNotBeEmpty()
                            }
                        }
                    }

                    val expectedPath = System.getenv("MAVEN_SYNC_IT_EXPECTED")
                    if (!expectedPath.isNullOrBlank()) {
                        val expected = File(expectedPath).readText().trim()
                        withClue(
                            "Discovery report differs from snapshot at $expectedPath " +
                                "(live report: $reportPath)"
                        ) {
                            report.toJson().trim() shouldBe expected
                        }
                    }
                }
            }
    })

private fun loadIntegrationConfig(file: File): SyncConfig =
    ConfigLoaderBuilder.newBuilder()
        .allowEmptyConfigFiles()
        .withExplicitSealedTypes()
        .addFileSource(file = file, optional = false)
        .addResourceSource("/config/defaults.json")
        .build()
        .loadConfigOrThrow<SyncConfig>()

private fun writeReport(report: DiscoveryReport): Path {
    val outDir = Path.of("build", "reports", "maven-sync-it")
    Files.createDirectories(outDir)
    val reportPath = outDir.resolve("discovery-report.json")
    Files.writeString(reportPath, report.toJson())
    return reportPath
}

/**
 * Deterministic, sorted record of what was discovered: `group:artifact` → version → sorted asset
 * filenames. Used for both assertions and the on-disk JSON report.
 */
private class DiscoveryReport {

    val artifacts: TreeMap<String, TreeMap<String, MutableList<String>>> = TreeMap()

    fun add(coordinates: Coordinates, assets: List<ArtifactVersionAsset>) {
        val artifactKey = "${coordinates.group.value}:${coordinates.artifact.value}"
        val versions = artifacts.getOrPut(artifactKey) { TreeMap() }
        val files = versions.getOrPut(coordinates.artifactVersion.value) { mutableListOf() }
        assets.forEach { files += it.name.value }
        files.sort()
    }

    val artifactCount: Int
        get() = artifacts.size

    val versionCount: Int
        get() = artifacts.values.sumOf { it.size }

    val assetCount: Int
        get() = artifacts.values.sumOf { versions -> versions.values.sumOf { it.size } }

    fun toJson(): String = buildString {
        append("{\n")
        val artifactEntries = artifacts.entries.toList()
        artifactEntries.forEachIndexed { i, (artifactKey, versions) ->
            append("  ").append(jsonString(artifactKey)).append(": {\n")
            val versionEntries = versions.entries.toList()
            versionEntries.forEachIndexed { j, (version, files) ->
                append("    ").append(jsonString(version)).append(": [")
                append(files.joinToString(", ") { jsonString(it) })
                append("]")
                if (j < versionEntries.lastIndex) append(",")
                append("\n")
            }
            append("  }")
            if (i < artifactEntries.lastIndex) append(",")
            append("\n")
        }
        append("}\n")
    }

    private fun jsonString(value: String): String {
        val sb = StringBuilder("\"")
        value.forEach { c ->
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else ->
                    if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append("\"")
        return sb.toString()
    }
}
