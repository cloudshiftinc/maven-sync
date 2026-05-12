package io.cloudshiftdev.mavensync

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class MavenMetadataXmlTest :
    FunSpec({
        test("produces well-formed XML with all expected elements") {
            val xml =
                MavenMetadataXml(
                        group = "com.example",
                        artifactId = "foo",
                        versions = listOf("1.0", "1.1", "2.0"),
                        lastUpdated = "20240315083045",
                        latest = "2.0",
                        release = "2.0",
                    )
                    .toXml()

            xml shouldBe
                """
                |<?xml version="1.0" encoding="UTF-8"?>
                |<metadata>
                |  <groupId>com.example</groupId>
                |  <artifactId>foo</artifactId>
                |  <versioning>
                |    <latest>2.0</latest>
                |    <release>2.0</release>
                |    <versions>
                |      <version>1.0</version>
                |      <version>1.1</version>
                |      <version>2.0</version>
                |    </versions>
                |    <lastUpdated>20240315083045</lastUpdated>
                |  </versioning>
                |</metadata>
                |"""
                    .trimMargin()
        }

        test("emits empty <versions> block when no versions") {
            val xml =
                MavenMetadataXml(
                        group = "com.example",
                        artifactId = "foo",
                        versions = emptyList(),
                        lastUpdated = "20240315083045",
                        latest = "1.0",
                        release = "1.0",
                    )
                    .toXml()

            xml shouldContain "    <versions>\n    </versions>\n"
        }

        test("escapes XML-special characters in values") {
            val xml =
                MavenMetadataXml(
                        group = "com.<example>",
                        artifactId = "foo&bar",
                        versions = listOf("1.0<beta>"),
                        lastUpdated = "20240315083045",
                        latest = "1.0<beta>",
                        release = "1.0<beta>",
                    )
                    .toXml()

            xml shouldContain "<groupId>com.&lt;example&gt;</groupId>"
            xml shouldContain "<artifactId>foo&amp;bar</artifactId>"
            xml shouldContain "<version>1.0&lt;beta&gt;</version>"
            xml shouldContain "<latest>1.0&lt;beta&gt;</latest>"
            xml shouldContain "<release>1.0&lt;beta&gt;</release>"
        }
    })
