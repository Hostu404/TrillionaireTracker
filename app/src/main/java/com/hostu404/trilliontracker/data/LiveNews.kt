package com.hostu404.trilliontracker.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.IOException
import java.io.StringReader
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Client-side port of `backend/snapshot_worker.py`'s `fetch_news()` — same
 * free, keyless source (Google News' public RSS search), same query shape,
 * same field mapping. Exists for the same reason [LiveQuoteClient] and
 * [LiveFlightTracker] do: this app runs entirely on [SeedData] with
 * [Config.SNAPSHOT_URL] blank, so there's no backend process polling RSS on
 * a schedule and baking the result into a [Snapshot] — a person's detail
 * screen has to ask directly, the same live-while-actually-being-looked-at
 * shape as the flight tracker, not something that runs for everyone in the
 * background.
 */
object GoogleNewsClient {
    private val client get() = NetworkClients.shared

    private const val USER_AGENT = "trillionaire-tracker/0.1 (+https://github.com/Hostu404)"

    /**
     * Mirrors `NEWS_FETCH_LIMIT` in snapshot_worker.py — kept at the same
     * number so this live, client-side poll and the backend's own tracked
     * window cover the same stories. They used to drift (this defaulted to
     * 4 while the backend also fetched 4, which sounds aligned but wasn't
     * the point — see [fetchNews]'s doc comment): a story could sit just
     * outside the backend's window at the moment it fetched, while this
     * live poll — running independently, whenever someone actually opens
     * this screen — caught it. That headline would show with no theme
     * chip, not because classification failed, but because the backend
     * never saw it to classify at all. Raising both windows to the same
     * larger number doesn't fully close that gap (the two fetches still
     * happen at different times), but it shrinks it a lot, for free — this
     * request is keyless RSS either way, so a larger limit costs nothing
     * extra here.
     */
    private const val DEFAULT_NEWS_LIMIT = 8

    /**
     * [query] is used exactly as `fetch_news()` uses it server-side — quoted
     * as an exact phrase, not split into keywords — so this should be
     * called with a person's actual name, same as
     * `backend/holdings.json`'s `newsQuery` field is for every person
     * currently in [Holdings]/[SeedData]. Never throws: a network failure
     * or a response that doesn't parse as RSS is just an empty list, same
     * "no current data, not an error" contract as every other live client
     * in this file.
     */
    suspend fun fetchNews(query: String, limit: Int = DEFAULT_NEWS_LIMIT): List<NewsItem> {
        if (query.isBlank()) return emptyList()
        val encoded = URLEncoder.encode("\"$query\"", "UTF-8")
        val request = Request.Builder()
            .url("https://news.google.com/rss/search?q=$encoded&hl=en-GB&gl=GB&ceid=GB:en")
            .header("User-Agent", USER_AGENT)
            .build()

        return withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use emptyList()
                    val body = response.body?.string() ?: return@use emptyList()
                    parseRss(body, limit)
                }
            } catch (_: IOException) {
                emptyList()
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    /** RFC 822-ish, e.g. "Mon, 08 Sep 2026 12:34:56 GMT" — Google News' own `pubDate` format. */
    private val pubDateFormat = ThreadLocal.withInitial {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
    }

    /**
     * Hardened against XXE: this app has no reason to ever resolve a DOCTYPE,
     * an external entity, or an external DTD from an RSS response, and a
     * default [DocumentBuilderFactory] will happily do all three. That's a
     * real (if narrow — this endpoint is HTTPS/cert-validated, so exploiting
     * it needs the response itself compromised, not just observed) attack
     * surface for free: a malicious/compromised response could otherwise
     * read local files or trigger outbound requests from the device via a
     * crafted `<!DOCTYPE>`/entity declaration. Every flag below is the
     * standard OWASP-recommended lockdown for parsing untrusted XML on the
     * JVM. Built once and reused rather than per-call — none of this
     * configuration ever changes.
     *
     * Each `setFeature` call is wrapped individually rather than relying on
     * one `.apply { }` block: two of these feature URIs
     * (`disallow-doctype-decl` and `nonvalidating/load-external-dtd`) are
     * Xerces-specific extensions that Android's built-in `DocumentBuilder`
     * implementation doesn't recognize, and an unrecognized feature name
     * throws `ParserConfigurationException` right away. Since this was a
     * top-level `val` evaluated eagerly the first time anything touched this
     * object, that exception surfaced as an `ExceptionInInitializerError`
     * (an `Error`, not an `Exception`) — invisible to every `catch (_:
     * Exception)` elsewhere in this file, and fatal to the whole process the
     * instant a person's detail screen asked for news. Falling through on an
     * unsupported feature just means that one specific hardening isn't
     * available on this platform's parser, not that parsing becomes unsafe:
     * whichever features DID apply still block external entities/DTDs, and
     * Android's own parser doesn't resolve external entities by default in
     * the first place.
     */
    private val safeDocumentBuilderFactory: DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            trySetFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            trySetFeature("http://xml.org/sax/features/external-general-entities", false)
            trySetFeature("http://xml.org/sax/features/external-parameter-entities", false)
            trySetFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            try {
                isXIncludeAware = false
            } catch (_: Exception) {
            }
            try {
                isExpandEntityReferences = false
            } catch (_: Exception) {
            }
        }

    private fun DocumentBuilderFactory.trySetFeature(name: String, value: Boolean) {
        try {
            setFeature(name, value)
        } catch (_: Exception) {
            // Unsupported on this platform's parser — see the doc comment above.
        }
    }

    private fun parseRss(body: String, limit: Int): List<NewsItem> {
        return try {
            val document = safeDocumentBuilderFactory
                .newDocumentBuilder()
                .parse(InputSource(StringReader(body)))
            val items = document.getElementsByTagName("item")
            val out = mutableListOf<NewsItem>()

            for (i in 0 until items.length) {
                if (out.size >= limit) break
                val node = items.item(i) as? Element ?: continue

                val title = node.textOf("title")
                val link = node.textOf("link")
                if (title.isBlank() || link.isBlank()) continue

                val source = node.textOf("source").takeIf { it.isNotBlank() } ?: "Google News"
                val published = node.textOf("pubDate").takeIf { it.isNotBlank() }?.let { raw ->
                    try {
                        (pubDateFormat.get()?.parse(raw)?.time ?: 0L) / 1000
                    } catch (_: Exception) {
                        0L
                    }
                } ?: 0L

                out.add(NewsItem(title = title, source = source, url = link, publishedEpoch = published))
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Collapses any run of whitespace — including a literal newline sitting
     * in the MIDDLE of the string, not just leading/trailing ones a plain
     * `.trim()` would catch — into a single space. Mirrors
     * snapshot_worker.py's `fetch_news()`'s identical fix: some sources' RSS
     * templates put literal blank lines inside `<title>` (seen in practice
     * from at least one syndicated fashion trade outlet), which otherwise
     * renders as a wall of blank vertical space inside an otherwise normal
     * headline card, since Compose's `Text` respects embedded newlines.
     */
    private fun Element.textOf(tag: String): String =
        getElementsByTagName(tag).item(0)?.textContent
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            .orEmpty()
}
