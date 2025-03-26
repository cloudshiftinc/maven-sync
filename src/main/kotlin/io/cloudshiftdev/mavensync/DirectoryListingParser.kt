package io.cloudshiftdev.mavensync

import com.fleeksoft.ksoup.nodes.Document
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.nodes.TextNode
import io.ktor.http.Url

internal interface DirectoryListingParser {
    suspend fun parse(url: Url, document: Document): List<Url>

    companion object {
        fun create(): DirectoryListingParser {
            return CompositeDirectoryListingParser(
                listOf(
                    DefaultDirectoryListingParser()
                    // add other parsers here
                )
            )
        }
    }
}

private class CompositeDirectoryListingParser(private val parsers: List<DirectoryListingParser>) :
    DirectoryListingParser {

    override suspend fun parse(url: Url, document: Document): List<Url> {
        var lastException: Exception? = null
        for (parser in parsers) {
            try {
                return parser.parse(url, document)
            } catch (e: Exception) {
                lastException = e
                // ignore and try the next parser
            }
        }
        throw IllegalStateException(
            "No suitable directory listing parser found for $url",
            lastException,
        )
    }
}

private class DefaultDirectoryListingParser : DirectoryListingParser {
    override suspend fun parse(url: Url, document: Document): List<Url> {
        val baseUrl = url.toString()
        return document.select("pre").single().childNodes().groupPairs().mapNotNull {
            (linkElement, textElement) ->
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
            require(pieces.size == 3) { "Expected at least 3 pieces, got ${pieces.size} in $text" }
            val isDirectory =
                when {
                    pieces[2].isBlank() -> true
                    pieces[2] == "-" -> true
                    else -> false
                }

            // not all directory listings have '/' suffix for directories; infer from the
            // size
            when {
                isDirectory -> Url(linkUrl.normalizeUrlPath())
                else -> Url(linkUrl)
            }
        }
    }
}
