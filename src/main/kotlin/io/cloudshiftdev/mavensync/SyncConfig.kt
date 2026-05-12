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
    val trustAllCerts: Boolean
}

internal data class TargetRepositoryConfig(
    override val url: String,
    override val credentials: RepositoryCredentials? = null,
    override val logHttpHeaders: Boolean,
    override val trustAllCerts: Boolean,
) : RepositoryConfig

internal data class SourceRepositoryConfig(
    override val url: String,
    override val credentials: RepositoryCredentials? = null,
    override val logHttpHeaders: Boolean,
    override val trustAllCerts: Boolean,
    val crawlDelay: Duration,
    val downloadDelay: Duration,
    val paths: List<String>,
) : RepositoryConfig

internal data class RepositoryCredentials(val username: String, val password: String)

internal fun RepositoryConfig.toMavenHttpRepository(): MavenHttpRepository =
    MavenHttpRepository.create(
        url = url,
        credentials = credentials,
        logHttpHeaders = logHttpHeaders,
        trustAllCerts = trustAllCerts,
    )
