package io.cloudshiftdev.mavensync

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class TypesTest :
    FunSpec({
        context("Group") {
            test("accepts a normal group id") {
                Group("com.example").value shouldBe "com.example"
                Group("com.example").toString() shouldBe "com.example"
            }

            test("rejects blank values") {
                shouldThrow<IllegalArgumentException> { Group("") }
                shouldThrow<IllegalArgumentException> { Group("   ") }
            }

            test("rejects values starting with a dot") {
                shouldThrow<IllegalArgumentException> { Group(".com.example") }
            }
        }

        context("ArtifactVersion.isSnapshot") {
            test("true for -SNAPSHOT suffix") {
                ArtifactVersion("1.0-SNAPSHOT").isSnapshot shouldBe true
                ArtifactVersion("2.4.1-SNAPSHOT").isSnapshot shouldBe true
            }

            test("false for release versions") {
                ArtifactVersion("1.0").isSnapshot shouldBe false
                ArtifactVersion("1.0-RC1").isSnapshot shouldBe false
                ArtifactVersion("1.0-snapshot").isSnapshot shouldBe false
            }
        }

        context("Filename") {
            test("extension returns the substring after the last dot") {
                Filename("foo.jar").extension shouldBe "jar"
                Filename("foo.tar.gz").extension shouldBe "gz"
                Filename("noextension").extension shouldBe "noextension"
            }

            test("isPom") {
                Filename("foo-1.0.pom").isPom shouldBe true
                Filename("foo-1.0.jar").isPom shouldBe false
            }

            test("isChecksum covers md5/sha1/sha256/sha512") {
                Filename("foo.jar.md5").isChecksum shouldBe true
                Filename("foo.jar.sha1").isChecksum shouldBe true
                Filename("foo.jar.sha256").isChecksum shouldBe true
                Filename("foo.jar.sha512").isChecksum shouldBe true
                Filename("foo.jar").isChecksum shouldBe false
                Filename("foo.asc").isChecksum shouldBe false
            }

            test("isSignature for .asc") {
                Filename("foo.jar.asc").isSignature shouldBe true
                Filename("foo.jar").isSignature shouldBe false
            }
        }

        context("Coordinates.toString") {
            test("formats as group:artifact:version") {
                Coordinates(Group("com.example"), Artifact("foo"), ArtifactVersion("1.0"))
                    .toString() shouldBe "com.example:foo:1.0"
            }
        }
    })
