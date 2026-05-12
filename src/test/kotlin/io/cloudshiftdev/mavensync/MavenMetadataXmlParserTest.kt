package io.cloudshiftdev.mavensync

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.parser.Parser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private fun parseXml(xml: String) = Ksoup.parse(xml, parser = Parser.xmlParser())

class MavenMetadataXmlParserTest :
    FunSpec({
        test("parses group, artifact, and release versions") {
            val doc =
                parseXml(
                    """
                    <metadata>
                      <groupId>com.example</groupId>
                      <artifactId>foo</artifactId>
                      <versioning>
                        <versions>
                          <version>1.0</version>
                          <version>1.1</version>
                        </versions>
                      </versioning>
                    </metadata>
                    """
                        .trimIndent()
                )

            val md = MavenMetadataXmlParser.parse(doc)
            md.group shouldBe Group("com.example")
            md.artifact shouldBe Artifact("foo")
            md.artifactVersions shouldBe listOf(ArtifactVersion("1.0"), ArtifactVersion("1.1"))
        }

        test("filters out SNAPSHOT versions") {
            val doc =
                parseXml(
                    """
                    <metadata>
                      <groupId>com.example</groupId>
                      <artifactId>foo</artifactId>
                      <versioning>
                        <versions>
                          <version>1.0</version>
                          <version>1.1-SNAPSHOT</version>
                          <version>2.0</version>
                        </versions>
                      </versioning>
                    </metadata>
                    """
                        .trimIndent()
                )

            MavenMetadataXmlParser.parse(doc).artifactVersions shouldBe
                listOf(ArtifactVersion("1.0"), ArtifactVersion("2.0"))
        }

        test("throws when groupId missing") {
            val doc =
                parseXml(
                    """
                    <metadata>
                      <artifactId>foo</artifactId>
                    </metadata>
                    """
                        .trimIndent()
                )

            shouldThrow<IllegalStateException> { MavenMetadataXmlParser.parse(doc) }
        }

        test("throws when artifactId missing") {
            val doc =
                parseXml(
                    """
                    <metadata>
                      <groupId>com.example</groupId>
                    </metadata>
                    """
                        .trimIndent()
                )

            shouldThrow<IllegalStateException> { MavenMetadataXmlParser.parse(doc) }
        }

        test("returns empty version list when no <version> elements present") {
            val doc =
                parseXml(
                    """
                    <metadata>
                      <groupId>com.example</groupId>
                      <artifactId>foo</artifactId>
                    </metadata>
                    """
                        .trimIndent()
                )

            MavenMetadataXmlParser.parse(doc).artifactVersions shouldBe emptyList()
        }
    })
