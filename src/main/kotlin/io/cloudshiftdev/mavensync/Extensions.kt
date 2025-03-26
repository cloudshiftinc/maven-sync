package io.cloudshiftdev.mavensync

import io.ktor.http.*

internal val Url.filename: Filename
    get() = Filename(encodedPath.substringAfterLast("/"))

internal fun String.normalizeUrlPath(): String {
    return when {
        endsWith('/') -> this
        else -> "$this/"
    }
}

internal fun <T> List<T>.groupPairs(): List<Pair<T, T>> {
    return this.windowed(size = 2, step = 2, partialWindows = false) { it[0] to it[1] }
}
