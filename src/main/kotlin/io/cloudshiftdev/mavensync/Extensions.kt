package io.cloudshiftdev.mavensync

import io.ktor.http.*

internal val Url.filename: Filename
    get() = Filename(encodedPath.substringAfterLast("/"))
