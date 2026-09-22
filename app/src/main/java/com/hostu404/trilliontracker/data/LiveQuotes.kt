package com.hostu404.trilliontracker.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder

/**
 * Thin client for Yahoo Finance's unofficial chart endpoint
 * (https://query2.finance.yahoo.com/v8/finance/chart/{ticker}) — free and
 * keyless, requiring only a browser-like User-Agent. This is the
 * client-side twin of `backend/snapshot_worker.py`'s `fetch_quotes()`, for
 * the exact reason [OpenSkyClient] exists on the flight side: this app runs
 * entirely on [SeedData] with [Config.SNAPSHOT_URL] blank until a live
 * backend is configured, so there's no server to fetch quotes on everyone's
 * behalf — the request has to come from the device itself.
 *
 * This used to be `StooqClient`, hitting Stooq's free batch-CSV quote
 * endpoint (`https://stooq.com/q/l/?s=...`) in a single request for every
 * tracked ticker at once. Stooq started requiring a registered API key for
 * that endpoint in March 2026 (see
 * github.com/pydata/pandas-datareader/issues/1012), breaking it with no
 * free/keyless replacement on Stooq's own side — hence the switch to
 * Yahoo, mirroring the same fix already made in `snapshot_worker.py`.
 * Yahoo's chart endpoint has no batch form, so unlike the old Stooq call
 * this fires one concurrent request per ticker rather than a single batched
 * one — still cheap at the 8 symbols ([Holdings.allTickers]) this app
 * currently tracks.
 *
 * See `TrackerViewModel.startLiveWealthPolling` for the polling interval
 * and for how a still-empty result (a ticker Yahoo has nothing for this
 * tick, a rate limit, or simply being offline) just means nobody gets a
 * fresher anchor that tick, never a crash or a blanked-out number.
 */
object LiveQuoteClient {
    private val client get() = NetworkClients.shared

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * A real browser UA, not this app's own custom one — Yahoo's anti-bot
     * layer blocks a bot-looking User-Agent on this endpoint (confirmed the
     * hard way while fixing `snapshot_worker.py`'s identical server-side
     * call; the client-side request needs the same header for the same
     * reason).
     */
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /**
     * Looks up the latest price for each of [tickers], one concurrent
     * request per symbol (Yahoo's chart endpoint has no batch form). Returns
     * only the symbols Yahoo actually priced this call, keyed by uppercase
     * ticker — a symbol missing from the result has no current quote (market
     * closed, an unlisted/misspelled symbol, that one request failing), not
     * necessarily a whole-batch error. Never throws: any single ticker's
     * failure just leaves it out of the map, so a caller polling in a loop
     * needs no try/catch of its own.
     */
    suspend fun fetchQuotes(tickers: List<String>): Map<String, Double> {
        if (tickers.isEmpty()) return emptyMap()
        return withContext(Dispatchers.IO) {
            coroutineScope {
                tickers.map { ticker ->
                    async { ticker.uppercase() to fetchOne(ticker) }
                }.awaitAll()
            }.mapNotNull { (ticker, price) -> price?.let { ticker to it } }.toMap()
        }
    }

    private fun fetchOne(ticker: String): Double? {
        val encoded = URLEncoder.encode(ticker, "UTF-8")
        val request = Request.Builder()
            .url("https://query2.finance.yahoo.com/v8/finance/chart/$encoded?range=1d&interval=1d")
            .header("User-Agent", USER_AGENT)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                parsePrice(body)
            }
        } catch (_: IOException) {
            null
        } catch (_: Exception) {
            // Malformed/unexpected JSON shape — treat exactly like "no
            // current quote" rather than crashing the poll loop.
            null
        }
    }

    /**
     * `chart.result[0].meta.regularMarketPrice` is the stable field this
     * app reads — the same path `backend/snapshot_worker.py`'s Yahoo fix
     * reads server-side. Everything else in the payload is ignored.
     */
    private fun parsePrice(body: String): Double? {
        val result = json.parseToJsonElement(body).jsonObject["chart"]
            ?.jsonObject?.get("result")
            ?.jsonArray?.getOrNull(0)
            ?.jsonObject
            ?: return null
        return result["meta"]?.jsonObject?.get("regularMarketPrice")?.jsonPrimitive?.doubleOrNull
    }
}
