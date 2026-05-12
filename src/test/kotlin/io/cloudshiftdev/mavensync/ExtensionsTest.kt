package io.cloudshiftdev.mavensync

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.http.Url

class ExtensionsTest :
    FunSpec({
        context("Url.filename") {
            test("returns last path segment") {
                Url("https://repo.example.com/a/b/foo-1.0.jar").filename shouldBe
                    Filename("foo-1.0.jar")
            }

            test("returns empty Filename for directory-style URL") {
                Url("https://repo.example.com/a/b/").filename shouldBe Filename("")
            }
        }

        context("Url.isDirectory") {
            test("true when path ends with /") {
                Url("https://repo.example.com/a/b/").isDirectory shouldBe true
            }

            test("false when path does not end with /") {
                Url("https://repo.example.com/a/b/foo.jar").isDirectory shouldBe false
            }
        }

        context("String.normalizeUrlPath") {
            test("appends trailing slash when missing") {
                "https://repo.example.com/a/b".normalizeUrlPath() shouldBe
                    "https://repo.example.com/a/b/"
            }

            test("leaves trailing slash alone when present") {
                "https://repo.example.com/a/b/".normalizeUrlPath() shouldBe
                    "https://repo.example.com/a/b/"
            }
        }

        context("List.groupPairs") {
            test("pairs adjacent elements") {
                listOf(1, 2, 3, 4).groupPairs() shouldBe listOf(1 to 2, 3 to 4)
            }

            test("drops trailing odd element") {
                listOf(1, 2, 3).groupPairs() shouldBe listOf(1 to 2)
            }

            test("empty list yields empty list") {
                emptyList<Int>().groupPairs() shouldBe emptyList()
            }
        }
    })
