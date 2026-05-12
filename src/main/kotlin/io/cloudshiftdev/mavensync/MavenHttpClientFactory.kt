package io.cloudshiftdev.mavensync

import io.ktor.client.*
import io.ktor.client.engine.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.charsets.*
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

internal object MavenHttpClientFactory {
    fun create(
        config: HttpClientConfig,
        credentials: BasicAuthCredentials?,
        engine: HttpClientEngineFactory<*>? = null,
    ): HttpClient {
        val resolvedEngine = engine ?: OkhttpEngineFactory(trustAllCerts = config.trustAllCerts)
        return HttpClient(resolvedEngine) {
            install(HttpTimeout) {
                requestTimeoutMillis = config.requestTimeoutMs
                connectTimeoutMillis = config.connectTimeoutMs
                socketTimeoutMillis = config.socketTimeoutMs
            }
            install(Logging) {
                this.logger = Logger.DEFAULT
                level =
                    when {
                        config.logHttpHeaders -> LogLevel.HEADERS
                        else -> LogLevel.NONE
                    }
                sanitizeHeader { header -> header == HttpHeaders.Authorization }
            }

            defaultRequest { headers { append("User-Agent", config.userAgent) } }

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

private class OkhttpEngineFactory(private val trustAllCerts: Boolean) :
    HttpClientEngineFactory<OkHttpConfig> {
    override fun create(block: OkHttpConfig.() -> Unit): HttpClientEngine {
        return OkHttp.create {
            config {
                followRedirects(false)
                if (trustAllCerts) {
                    val sslContext = SSLContext.getInstance("TLS")
                    val trustManager = TrustAllX509TrustManager()
                    sslContext.init(null, arrayOf(trustManager), SecureRandom())
                    sslSocketFactory(sslContext.socketFactory, trustManager)
                    hostnameVerifier { _, _ -> true }
                }
            }
        }
    }

    private class TrustAllX509TrustManager : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate?> = arrayOfNulls(0)

        override fun checkClientTrusted(certs: Array<X509Certificate?>?, authType: String?) {}

        override fun checkServerTrusted(certs: Array<X509Certificate?>?, authType: String?) {}
    }
}
