package io.cloudshiftdev.mavensync

import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.Padding
import kotlinx.datetime.toLocalDateTime

internal val SystemClock: Clock = Clock.System

private val mavenLastUpdatedFormat = LocalDateTime.Format {
    year(padding = Padding.ZERO)
    monthNumber(padding = Padding.ZERO)
    day(padding = Padding.ZERO)
    hour(padding = Padding.ZERO)
    minute(padding = Padding.ZERO)
    second(padding = Padding.ZERO)
}

internal fun Instant.toMavenLastUpdated(): String =
    mavenLastUpdatedFormat.format(this.toLocalDateTime(TimeZone.UTC))
