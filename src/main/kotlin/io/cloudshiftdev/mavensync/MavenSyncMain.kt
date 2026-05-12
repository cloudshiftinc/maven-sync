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
import org.slf4j.LoggerFactory

private val logger = KotlinLogging.logger {}

public suspend fun main(args: Array<String>) {
    if (args.contains("--debug")) {
        val loggerContext = LoggerFactory.getILoggerFactory() as LoggerContext
        loggerContext.getLogger("io.cloudshiftdev.mavensync").level =
            ch.qos.logback.classic.Level.DEBUG
    }

    val config = loadConfiguration(args)

    logger.info { "Effective configuration: $config" }

    val metrics = SyncMetrics()
    try {
        config.source.toMavenHttpRepository().use { source ->
            config.target.toMavenHttpRepository().use { target ->
                MavenSyncEngine(source, target, config.toSyncOptions(), metrics).sync()
            }
        }
    } finally {
        val report = metrics.snapshot()
        logger.info { "\n" + report.renderDetailed() }
        logger.info { "\n" + report.renderSummary() }
    }
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
