@file:OptIn(ExperimentalHoplite::class)

package io.cloudshiftdev.mavensync

import ch.qos.logback.classic.LoggerContext
import com.sksamuel.hoplite.ConfigException
import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.ExperimentalHoplite
import com.sksamuel.hoplite.addFileSource
import com.sksamuel.hoplite.addResourceSource
import com.sksamuel.hoplite.sources.CommandLinePropertySource
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import kotlin.system.exitProcess
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

private val logger = KotlinLogging.logger {}

@OptIn(ExperimentalCoroutinesApi::class)
public suspend fun main(args: Array<String>) {
    if (args.contains("--debug")) {
        val loggerContext = LoggerFactory.getILoggerFactory() as LoggerContext
        loggerContext.getLogger("io.cloudshiftdev.mavensync").level =
            ch.qos.logback.classic.Level.DEBUG
    }

    val config = loadConfiguration(args)

    logger.info { "Effective configuration: $config" }

    executeSync(config)
}

private fun loadConfiguration(args: Array<String>): SyncConfig =
    try {
        val builder =
            ConfigLoaderBuilder.newBuilder().allowEmptyConfigFiles().withExplicitSealedTypes()
        builder.addPropertySource(
            CommandLinePropertySource(arguments = args, prefix = "--", delimiter = "=")
        )

        val fileArgs = args.filter { !it.startsWith("-") }.map { File(it.trim()) }
        fileArgs.forEach { builder.addFileSource(file = it, optional = true) }
        builder.addResourceSource("/config/defaults.json")
        builder.build().loadConfigOrThrow<SyncConfig>()
    } catch (e: ConfigException) {
        logger.error(e) { "Error loading configuration" }
        exitProcess(1)
    }

@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun executeSync(config: SyncConfig) {
    config.target.toMavenHttpRepository().use { targetRepository ->
        config.source.toMavenHttpRepository().use { sourceRepository ->
            coroutineScope {
                val artifactChannel =
                    produce(capacity = config.artifactConcurrency) {
                        sourceRepository.crawlArtifactMetadata(
                            channel = this,
                            crawlDelay = config.source.crawlDelay,
                            paths = config.source.paths,
                        )
                    }
                repeat(config.artifactConcurrency) {
                    launch {
                        artifactChannel.consumeEach { artifact ->
                            handleArtifact(artifact, sourceRepository, targetRepository, config)
                        }
                    }
                }
            }
        }
    }
}

private fun RepositoryConfig.toMavenHttpRepository(): MavenHttpRepository {
    return MavenHttpRepository.create(
        url = url,
        credentials = credentials,
        logHttpHeaders = logHttpHeaders,
    )
}

private suspend fun handleArtifact(
    metadata: ArtifactMetadata,
    sourceRepository: MavenHttpRepository,
    targetRepository: MavenHttpRepository,
    config: SyncConfig,
) {
    val targetMetadata = targetRepository.queryArtifactMetadata(metadata.group, metadata.artifact)
    val sourceVersions = metadata.artifactVersions.toSet()
    val targetVersions = targetMetadata.artifactVersions.toSet()
    val missingVersions = sourceVersions - targetVersions
    logger.info {
        "Syncing missing versions for ${metadata.group}:${metadata.artifact}: $missingVersions"
    }
    missingVersions
        .map { Coordinates(metadata.group, metadata.artifact, it) }
        .forEach { coordinates ->

            // find all assets for this version
            val assets =
                sourceRepository.listArtifactVersionAssets(
                    coordinates,
                    config.transferChecksums,
                    config.transferSignatures,
                )

            // copy each asset to target
            assets.forEach { asset -> sourceRepository.copyAsset(asset, targetRepository) }

            targetRepository.releaseVersion(coordinates)

            delay(config.source.downloadDelay)
        }
}

internal data class Arg(
    val names: List<String>,
    val description: String,
    val default: String? = null,
    val required: Boolean = false,
) {
    companion object {
        fun create(
            name: String,
            description: String,
            default: String? = null,
            required: Boolean = false,
        ) = Arg(listOf(name), description, default, required)
    }

    fun toHelpText(): String {
        val defaultText = if (default != null) " (default: $default)" else ""
        val requiredText = if (required) " (required)" else ""
        return names.joinToString(", ") + " : " + description + defaultText + requiredText
    }
}
