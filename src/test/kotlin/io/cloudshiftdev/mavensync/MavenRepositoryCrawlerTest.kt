package io.cloudshiftdev.mavensync

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class MavenRepositoryCrawlerTest :
    FunSpec({
        context("artifactVersion") {
            test("parses standard release versions") {
                artifactVersion("1.0.0").shouldNotBeNull()
                artifactVersion("2.4.1").shouldNotBeNull()
            }

            test("parses qualifier-bearing release versions") {
                artifactVersion("1.0.0-RC1").shouldNotBeNull()
                artifactVersion("1.0-beta").shouldNotBeNull()
            }

            test("returns null for non-version strings") {
                // Maven's DefaultArtifactVersion treats unparseable input as pure qualifier,
                // i.e. qualifier == input. The helper rejects those.
                artifactVersion("foo") shouldBe null
                artifactVersion("not-a-version") shouldBe null
            }
        }
    })
