package io.cloudshiftdev.mavensync

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Instant

class ClockTest :
    FunSpec({
        test("formats UTC instant as yyyyMMddHHmmss") {
            Instant.parse("2024-03-15T08:30:45Z").toMavenLastUpdated() shouldBe "20240315083045"
        }

        test("zero-pads single-digit fields") {
            Instant.parse("2001-01-02T03:04:05Z").toMavenLastUpdated() shouldBe "20010102030405"
        }

        test("uses UTC regardless of system zone") {
            Instant.parse("2023-12-31T23:59:59Z").toMavenLastUpdated() shouldBe "20231231235959"
        }
    })
