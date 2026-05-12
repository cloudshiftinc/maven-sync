package io.cloudshiftdev.mavensync

internal data class HttpClientConfig(
    val logHttpHeaders: Boolean = false,
    val requestTimeoutMs: Long = 45_000,
    val connectTimeoutMs: Long = 3_000,
    val socketTimeoutMs: Long = 45_000,
    val userAgent: String = DEFAULT_USER_AGENT,
    val trustAllCerts: Boolean = true,
) {
    companion object {
        private const val DEFAULT_USER_AGENT: String =
            "Gradle/8.7 (Mac OS X;15.1.1;aarch64) (Eclipse Adoptium;21.0.2;21.0.2+13-LTS)"
    }
}
