package io.cloudshiftdev.mavensync

import kotlin.time.Duration

internal data class SyncConfig(
    val transferChecksums: Boolean,
    val transferSignatures: Boolean,
    val artifactConcurrency: Int,
    val source: SourceRepositoryConfig,
    val target: TargetRepositoryConfig,
)

internal interface RepositoryConfig {
    val url: String
    val credentials: RepositoryCredentials?
    val logHttpHeaders: Boolean
}

internal data class TargetRepositoryConfig(
    override val url: String,
    override val credentials: RepositoryCredentials? = null,
    override val logHttpHeaders: Boolean,
) : RepositoryConfig

internal data class SourceRepositoryConfig(
    override val url: String,
    override val credentials: RepositoryCredentials? = null,
    override val logHttpHeaders: Boolean,
    val crawlDelay: Duration,
    val downloadDelay: Duration,
    val paths: List<String>,
) : RepositoryConfig

internal data class RepositoryCredentials(val username: String, val password: String)
