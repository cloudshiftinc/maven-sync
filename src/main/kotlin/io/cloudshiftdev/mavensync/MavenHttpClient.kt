package io.cloudshiftdev.mavensync

import com.fleeksoft.ksoup.nodes.Document
import com.fleeksoft.ksoup.parser.Parser
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.content.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.util.cio.*
import io.ktor.utils.io.*
import java.io.File

private val logger = KotlinLogging.logger {}

internal class MavenHttpClient(
    private val httpClient: HttpClient,
    private val directoryListingParser: DirectoryListingParser = DirectoryListingParser.create(),
) : AutoCloseable {

    constructor(
        config: HttpClientConfig,
        credentials: BasicAuthCredentials?,
    ) : this(MavenHttpClientFactory.create(config, credentials))

    override fun close() {
        httpClient.close()
    }

    internal suspend fun upload(url: Url, file: File): Long {
        val resp =
            httpClient.put(url) {
                contentType(ContentType.Application.OctetStream)
                setBody(LocalFileContent(file))
            }
        val size = file.length()
        logger.debug { "Uploaded $url: status=${resp.status} size=$size" }
        return size
    }

    internal suspend fun upload(url: Url, content: String) {
        val resp = httpClient.put(url) { setBody(content) }
        logger.debug { "Uploaded $url: ${resp.status}" }
    }

    internal suspend fun parseDirectoryListing(url: Url): List<Url> {
        val document =
            try {
                parseContent(url)
            } catch (e: MissingContentException) {
                // this happens if the maven metadata lists a version but that version isn't present
                logger.warn { "Unable to download $url: ${e.message}; skipping" }
                return emptyList()
            } catch (e: ClientRequestException) {
                logger.warn { "Unable to download $url: ${e.message}; skipping" }
                return emptyList()
            }

        return directoryListingParser.parse(url, document)
    }

    private val parserMap =
        mapOf(
            ContentType.Application.Xml to Parser.xmlParser(),
            ContentType.Text.Html to Parser.htmlParser(),
            ContentType.Text.Xml to Parser.xmlParser(),
        )

    internal suspend fun parseContent(url: Url): Document {
        return download(url) { response, file ->
            val responseContentType =
                response.contentType()?.withoutParameters()
                    ?: throw MissingContentTypeException(response)

            val parser =
                parserMap[responseContentType]?.newInstance()
                    ?: throw ResponseException(
                        response,
                        "No parser for content type: $responseContentType",
                    )
            parser.parseInput(file.reader().buffered(), url.toString())
        }
    }

    internal suspend fun <T> download(url: Url, block: suspend (HttpResponse, File) -> T): T {
        return httpClient.prepareGet(url).execute { response ->
            val tempFile = File.createTempFile("download", null)
            try {
                response.bodyAsChannel().copyAndClose(tempFile.writeChannel())
                block(response, tempFile)
            } finally {
                tempFile.delete()
            }
        }
    }
}

internal class MissingContentException(response: HttpResponse, cachedResponseText: String) :
    ResponseException(response, cachedResponseText) {

    override val message: String =
        "Missing content(${response.call.request.method.value} ${response.call.request.url}: " +
            "${response.status}. Text: \"$cachedResponseText\""
}

internal class ConflictException(response: HttpResponse, cachedResponseText: String) :
    ResponseException(response, cachedResponseText) {

    override val message: String =
        "Conflict(${response.call.request.method.value} ${response.call.request.url}: " +
            "${response.status}. Text: \"$cachedResponseText\""
}

internal class MissingContentTypeException(response: HttpResponse) :
    ResponseException(response, "") {

    override val message: String =
        "Missing content type(${response.call.request.method.value} ${response.call.request.url}"
}
