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
     * [query] is used exactly as `fetch_news()` uses it server-side — quoted
     * as an exact phrase, not split into keywords — so this should be
     * called with a person's actual name, same as
     * `backend/holdings.json`'s `newsQuery` field is for every person
     * currently in [Holdings]/[SeedData]. Never throws: a network failure
     * or a response that doesn't parse as RSS is just an empty list, same
     * "no current data, not an error" contract as every other live client
     * in this file.
     */
    suspend fun fetchNews(query: String, limit: Int = 4): List<NewsItem> {
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

    private fun parseRss(body: String, limit: Int): List<NewsItem> {
        return try {
            val document = DocumentBuilderFactory.newInstance()
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

    private fun Element.textOf(tag: String): String =
        getElementsByTagName(tag).item(0)?.textContent?.trim().orEmpty()
}
