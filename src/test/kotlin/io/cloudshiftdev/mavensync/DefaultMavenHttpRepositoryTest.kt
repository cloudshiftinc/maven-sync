package io.cloudshiftdev.mavensync

import com.fleeksoft.ksoup.nodes.Document
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.time.Clock
import kotlin.time.Instant

private val fixedClock =
    object : Clock {
        override fun now(): Instant = Instant.parse("2024-03-15T08:30:45Z")
    }

private const val REPO = "https://repo.example.com/maven2/"
private const val METADATA_URL = "${REPO}com/example/foo/${MavenSpec.MavenMetadataXmlFile}"

private class CannedListingParser(private val byUrl: Map<String, List<Url>>) :
    DirectoryListingParser {
    override suspend fun parse(url: Url, document: Document): List<Url> =
        byUrl[url.toString()] ?: emptyList()
}

private fun MockRequestHandleScope.respondXml(body: String) =
    respond(
        body,
        HttpStatusCode.OK,
        headersOf(HttpHeaders.ContentType, ContentType.Application.Xml.toString()),
    )

private fun MockRequestHandleScope.respondHtml(
    body: String = "<html><body><pre></pre></body></html>"
) =
    respond(
        body,
        HttpStatusCode.OK,
        headersOf(HttpHeaders.ContentType, ContentType.Text.Html.toString()),
    )

private fun MockRequestHandleScope.respondNotFound() =
    respond(
        "not found",
        HttpStatusCode.NotFound,
        headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString()),
    )

private fun buildHttpClient(
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData
): HttpClient {
    return HttpClient(MockEngine) {
        engine { addHandler { request -> handler(request) } }
        expectSuccess = true
        HttpResponseValidator {
            handleResponseExceptionWithRequest { exception, _ ->
                if (
                    exception is ClientRequestException &&
                        exception.response.status == HttpStatusCode.NotFound
                ) {
                    throw MissingContentException(exception.response, exception.message)
                }
            }
        }
    }
}

private fun metadataXml(versions: List<String>): String = buildString {
    append("<metadata><groupId>com.example</groupId><artifactId>foo</artifactId>")
    append("<versioning><versions>")
    versions.forEach { append("<version>$it</version>") }
    append("</versions></versioning></metadata>")
}

private fun HttpRequestData.bodyText(): String = (body as TextContent).text

class DefaultMavenHttpRepositoryTest :
    FunSpec({
        val group = Group("com.example")
        val artifact = Artifact("foo")

        context("listArtifactVersionAssets") {
            val coords = Coordinates(group, artifact, ArtifactVersion("1.0"))
            val listingUrl = "${REPO}com/example/foo/1.0/"

            val assets =
                listOf(
                    Url("${listingUrl}foo-1.0.jar"),
                    Url("${listingUrl}foo-1.0.pom"),
                    Url("${listingUrl}foo-1.0.jar.md5"),
                    Url("${listingUrl}foo-1.0.jar.sha1"),
                    Url("${listingUrl}foo-1.0.jar.asc"),
                    Url("${listingUrl}other-1.0.jar"),
                    Url("${listingUrl}subdir/"),
                )

            val mavenClient =
                autoClose(
                    MavenHttpClient(
                        httpClient = buildHttpClient { _ -> respondHtml() },
                        directoryListingParser = CannedListingParser(mapOf(listingUrl to assets)),
                    )
                )
            val repo = DefaultMavenHttpRepository(Url(REPO), mavenClient, fixedClock)

            test("excludes checksums and signatures by default") {
                val result =
                    repo.listArtifactVersionAssets(
                        coords,
                        includeChecksums = false,
                        includeSignatures = false,
                    )

                result.map { it.name.value } shouldContainExactlyInAnyOrder
                    listOf("foo-1.0.jar", "foo-1.0.pom")
            }

            test("includes signatures when includeSignatures is true") {
                val result =
                    repo.listArtifactVersionAssets(
                        coords,
                        includeChecksums = false,
                        includeSignatures = true,
                    )

                result.map { it.name.value } shouldContainExactlyInAnyOrder
                    listOf("foo-1.0.jar", "foo-1.0.pom", "foo-1.0.jar.asc")
            }

            test("includes checksums when includeChecksums is true") {
                val result =
                    repo.listArtifactVersionAssets(
                        coords,
                        includeChecksums = true,
                        includeSignatures = false,
                    )

                result.map { it.name.value } shouldContainExactlyInAnyOrder
                    listOf("foo-1.0.jar", "foo-1.0.pom", "foo-1.0.jar.md5", "foo-1.0.jar.sha1")
            }
        }

        context("queryArtifactMetadata") {
            test("returns empty metadata on 404") {
                val mavenClient =
                    autoClose(MavenHttpClient(buildHttpClient { _ -> respondNotFound() }))
                val repo = DefaultMavenHttpRepository(Url(REPO), mavenClient, fixedClock)

                val md = repo.queryArtifactMetadata(group, artifact)
                md.group shouldBe group
                md.artifact shouldBe artifact
                md.artifactVersions shouldBe emptyList()
            }

            test("parses returned XML on 200") {
                val mavenClient =
                    autoClose(
                        MavenHttpClient(
                            buildHttpClient { _ -> respondXml(metadataXml(listOf("1.0", "1.1"))) }
                        )
                    )
                val repo = DefaultMavenHttpRepository(Url(REPO), mavenClient, fixedClock)

                val md = repo.queryArtifactMetadata(group, artifact)
                md.artifactVersions shouldBe listOf(ArtifactVersion("1.0"), ArtifactVersion("1.1"))
            }
        }

        context("releaseVersion") {
            test(
                "uploads metadata XML containing existing + new version with deterministic timestamp"
            ) {
                val coords = Coordinates(group, artifact, ArtifactVersion("2.0"))
                val requests = mutableListOf<HttpRequestData>()

                val mavenClient =
                    autoClose(
                        MavenHttpClient(
                            buildHttpClient { request ->
                                requests += request
                                when (request.method) {
                                    HttpMethod.Get -> respondXml(metadataXml(listOf("1.0")))
                                    HttpMethod.Put -> respond("", HttpStatusCode.OK)
                                    else ->
                                        respond("unexpected", HttpStatusCode.InternalServerError)
                                }
                            }
                        )
                    )
                val repo = DefaultMavenHttpRepository(Url(REPO), mavenClient, fixedClock)

                repo.releaseVersion(coords)

                val put = requests.single { it.method == HttpMethod.Put }
                put.url.toString() shouldBe METADATA_URL
                put.bodyText() shouldBe
                    MavenMetadataXml(
                            group = "com.example",
                            artifactId = "foo",
                            versions = listOf("1.0", "2.0"),
                            lastUpdated = "20240315083045",
                            latest = "2.0",
                            release = "2.0",
                        )
                        .toXml()
            }

            test("emits a version-only metadata when target has none yet") {
                val coords = Coordinates(group, artifact, ArtifactVersion("1.0"))
                val puts = mutableListOf<HttpRequestData>()

                val mavenClient =
                    autoClose(
                        MavenHttpClient(
                            buildHttpClient { request ->
                                when (request.method) {
                                    HttpMethod.Get -> respondNotFound()
                                    HttpMethod.Put -> {
                                        puts += request
                                        respond("", HttpStatusCode.OK)
                                    }
                                    else ->
                                        respond("unexpected", HttpStatusCode.InternalServerError)
                                }
                            }
                        )
                    )
                val repo = DefaultMavenHttpRepository(Url(REPO), mavenClient, fixedClock)

                repo.releaseVersion(coords)

                puts shouldHaveSize 1
                puts.single().url.toString() shouldBe METADATA_URL
                puts.single().bodyText() shouldBe
                    MavenMetadataXml(
                            group = "com.example",
                            artifactId = "foo",
                            versions = listOf("1.0"),
                            lastUpdated = "20240315083045",
                            latest = "1.0",
                            release = "1.0",
                        )
                        .toXml()
            }
        }

        context("URL construction") {
            test("turns group dots into path segments") {
                val deepGroup = Group("com.example.deep.module")
                val coords = Coordinates(deepGroup, artifact, ArtifactVersion("1.0"))
                val captured = mutableListOf<Url>()

                val mavenClient =
                    autoClose(
                        MavenHttpClient(
                            httpClient =
                                buildHttpClient { request ->
                                    captured += request.url
                                    respondHtml()
                                },
                            directoryListingParser = CannedListingParser(emptyMap()),
                        )
                    )
                val repo = DefaultMavenHttpRepository(Url(REPO), mavenClient, fixedClock)

                repo.listArtifactVersionAssets(
                    coords,
                    includeChecksums = false,
                    includeSignatures = false,
                )

                captured.single().toString() shouldBe "${REPO}com/example/deep/module/foo/1.0/"
            }
        }
    })
