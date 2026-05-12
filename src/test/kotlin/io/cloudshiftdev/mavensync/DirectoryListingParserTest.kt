package io.cloudshiftdev.mavensync

import com.fleeksoft.ksoup.Ksoup
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.http.Url

private const val BASE = "https://repo.example.com/com/example/"

private fun listing(body: String) = Ksoup.parse("<html><body><pre>$body</pre></body></html>", BASE)

class DirectoryListingParserTest :
    FunSpec({
        val parser = DirectoryListingParser.create()
        val baseUrl = Url(BASE)

        test("parses files and directories anchored to the base URL") {
            val doc =
                listing(
                    "<a href=\"bar.jar\">bar.jar</a> 2024-01-01 12:00 1234\n" +
                        "<a href=\"foo/\">foo/</a> 2024-01-01 12:00 -\n"
                )

            parser.parse(baseUrl, doc) shouldContainExactlyInAnyOrder
                listOf(
                    Url("${BASE}bar.jar"),
                    Url("${BASE}foo/"),
                )
        }

        test("filters out links not anchored to the base URL") {
            val doc =
                listing(
                    "<a href=\"../\">parent/</a> 2024-01-01 12:00 -\n" +
                        "<a href=\"https://other.example.com/x.jar\">x.jar</a> 2024-01-01 12:00 999\n" +
                        "<a href=\"foo.jar\">foo.jar</a> 2024-01-01 12:00 999\n"
                )

            parser.parse(baseUrl, doc) shouldBe listOf(Url("${BASE}foo.jar"))
        }

        test("infers directory and adds trailing slash when href omits it") {
            val doc =
                listing("<a href=\"foo\">foo</a> 2024-01-01 12:00 -\n")

            parser.parse(baseUrl, doc) shouldBe listOf(Url("${BASE}foo/"))
        }

        test("throws when listing text has the wrong number of pieces") {
            val doc =
                listing("<a href=\"foo.jar\">foo.jar</a> 2024-01-01 12:00\n")

            val ex = shouldThrow<IllegalStateException> { parser.parse(baseUrl, doc) }
            ex.cause shouldNotBe null
            ex.cause.shouldBeInstanceOf<IllegalArgumentException>()
        }

        test("throws when there is no <pre> element") {
            val doc = Ksoup.parse("<html><body><p>nothing here</p></body></html>", BASE)

            shouldThrow<IllegalStateException> { parser.parse(baseUrl, doc) }
        }
    })
