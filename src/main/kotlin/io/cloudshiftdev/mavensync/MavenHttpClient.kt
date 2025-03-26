package io.cloudshiftdev.mavensync

import com.fleeksoft.ksoup.nodes.Document
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.nodes.TextNode
import com.fleeksoft.ksoup.parser.Parser
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.content.*
import io.ktor.client.engine.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.util.cio.*
import io.ktor.utils.io.*
import io.ktor.utils.io.charsets.*
import java.io.File
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

private val logger = KotlinLogging.logger {}

internal class MavenHttpClient(logHttpHeaders: Boolean, credentials: BasicAuthCredentials?) :
    AutoCloseable {
    private val httpClient = HttpClientFactory.create(logHttpHeaders, credentials)

    override fun close() {
        httpClient.close()
    }

    internal suspend fun upload(url: Url, file: File) {
        val resp =
            httpClient.put(url) {
                contentType(ContentType.Application.OctetStream)
                setBody(LocalFileContent(file))
            }
        logger.info { "Uploaded $url: status=${resp.status} size=${file.length()}" }
    }

    internal suspend fun upload(url: Url, content: String) {
        val resp = httpClient.put(url) { setBody(content) }
        logger.info { "Uploaded $url: ${resp.status}" }
    }

    fun <T> List<T>.groupPairs(): List<Pair<T, T>> {
        return this.windowed(size = 2, step = 2, partialWindows = false) { it[0] to it[1] }
    }

    internal suspend fun parseChildLinks(url: Url): List<Url> {
        val baseUrl = url.toString()
        return try {
            parseContent(url)
                .body()
                .select("pre#contents")
                .single()
                .childNodes()
                .groupPairs()
                .mapNotNull { (linkElement, textElement) ->
                    require(linkElement is Element) {
                        "Expected link element, got ${linkElement::class.simpleName}"
                    }
                    require(textElement is TextNode) {
                        "Expected text element, got ${textElement::class.simpleName}"
                    }

                    val linkUrl = linkElement.attr("abs:href")
                    // keep everything anchored to the base - don't wander elsewhere
                    if (!linkUrl.startsWith(baseUrl)) return@mapNotNull null

                    val text = textElement.text().trim()
                    val pieces = text.split(" ")
                    require(pieces.size == 3) {
                        "Expected at least 3 pieces, got ${pieces.size} in $text"
                    }
                    val size = pieces[2].toLongOrNull()

                    // not all directory listings have '/' suffix for directories; infer from the
                    // size
                    when {
                        size == null -> Url(linkUrl.normalizeUrlPath())
                        else -> Url(linkUrl)
                    }
                }

            //            val x = parseContent(url)
            //                .body()
            //                .select("pre#contents")
            //                .single()
            //                .childNodes()
            //                .asSequence()
            //                .zipWithNext()
            //                .mapNotNull { (linkElement, textElement) ->
            //                    require(linkElement is Element) {
            //                        "Expected link element, got ${linkElement::class.simpleName}"
            //                    }
            //                    require(textElement is TextNode) {
            //                        "Expected text element, got ${textElement::class.simpleName}"
            //                    }
            //
            //                    val url = linkElement.attr("abs:href")
            //                    // keep everything anchored to the base - don't wander elsewhere
            //                    if(!url.startsWith(baseUrl)) return@mapNotNull null
            //
            //                    val text = textElement.text()
            //                    println("$url $text")
            //                }.toList()
            //
            //            println(x)
            //            parseContent(url)
            //                .body()
            //                .select("pre#contents a")
            //                .asSequence()
            //                .mapNotNull {element ->
            //                    val url = element.attr("abs:href")
            //
            //                    // keep everything anchored to the base - don't wander elsewhere
            //                    if(!url.startsWith(baseUrl)) return@mapNotNull null
            //
            //                    Url(url)
            //                }
        } catch (e: MissingContentException) {
            // this happens if the maven metadata lists a version but that version isn't present
            logger.warn { "Unable to download $url: ${e.message}; skipping" }
            emptyList()
        } catch (e: ClientRequestException) {
            logger.warn { "Unable to download $url: ${e.message}; skipping" }
            emptyList()
        }
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

private class OkhttpEngineFactory : HttpClientEngineFactory<OkHttpConfig> {
    override fun create(block: OkHttpConfig.() -> Unit): HttpClientEngine {
        return OkHttp.create {
            config {
                followRedirects(false)
                val sslContext = SSLContext.getInstance("TLS")
                val trustAllCerts = TrustAllX509TrustManager()
                sslContext.init(null, arrayOf(trustAllCerts), SecureRandom())
                sslSocketFactory(sslContext.socketFactory, trustAllCerts)
                hostnameVerifier { _, _ -> true }
            }
        }
    }

    private class TrustAllX509TrustManager : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate?> = arrayOfNulls(0)

        override fun checkClientTrusted(certs: Array<X509Certificate?>?, authType: String?) {}

        override fun checkServerTrusted(certs: Array<X509Certificate?>?, authType: String?) {}
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

private object HttpClientFactory {
    fun create(logHttpHeaders: Boolean, credentials: BasicAuthCredentials?): HttpClient {
        return HttpClient(OkhttpEngineFactory()) {
            install(HttpTimeout) {
                requestTimeoutMillis = 45000
                connectTimeoutMillis = 3000
                socketTimeoutMillis = 45000
            }
            install(Logging) {
                this.logger = Logger.DEFAULT
                level =
                    when {
                        logHttpHeaders -> LogLevel.HEADERS
                        else -> LogLevel.NONE
                    }
                sanitizeHeader { header -> header == HttpHeaders.Authorization }
            }

            defaultRequest {
                headers {
                    append(
                        "User-Agent",
                        "Gradle/8.7 (Mac OS X;15.1.1;aarch64) (Eclipse Adoptium;21.0.2;21.0.2+13-LTS)",
                    )
                }
            }

            if (credentials != null) {
                install(Auth) {
                    basic {
                        credentials {
                            BasicAuthCredentials(
                                username = credentials.username,
                                password = credentials.password,
                            )
                        }
                        realm = "Access to the maven repository"
                        sendWithoutRequest { true }
                    }
                }
            }

            expectSuccess = true

            HttpResponseValidator {
                validateResponse { response ->
                    suspend fun HttpResponse.exceptionText(): String {
                        return try {
                            bodyAsText()
                        } catch (_: MalformedInputException) {
                            "<body failed decoding>"
                        }
                    }

                    when (response.status.value) {
                        in 202..299 -> ResponseException(response, response.exceptionText())
                    }
                }

                handleResponseExceptionWithRequest { exception, _ ->
                    when (exception) {
                        is ClientRequestException -> {
                            when (exception.response.status.value) {
                                404 ->
                                    throw MissingContentException(
                                        exception.response,
                                        exception.message,
                                    )
                                409 ->
                                    throw ConflictException(exception.response, exception.message)
                            }
                        }
                    }
                }
            }
        }
    }
}
