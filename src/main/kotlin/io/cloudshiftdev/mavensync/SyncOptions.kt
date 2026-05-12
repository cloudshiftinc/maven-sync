package io.cloudshiftdev.mavensync

import kotlin.time.Duration

internal data class SyncOptions(
    val transferChecksums: Boolean,
    val transferSignatures: Boolean,
    val artifactConcurrency: Int,
    val crawlDelay: Duration,
    val downloadDelay: Duration,
    val paths: List<String>,
)

internal fun SyncConfig.toSyncOptions(): SyncOptions =
    SyncOptions(
        transferChecksums = transferChecksums,
        transferSignatures = transferSignatures,
        artifactConcurrency = artifactConcurrency,
        crawlDelay = source.crawlDelay,
        downloadDelay = source.downloadDelay,
        paths = source.paths,
    )
