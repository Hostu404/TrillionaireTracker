package com.hostu404.trilliontracker.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException

/**
 * Thin client for Stooq's public quote endpoint
 * (https://stooq.com/q/l/?s=...) — free, keyless, and batch-capable, the
 * same source `backend/snapshot_worker.py`'s `fetch_quotes()` already uses
 * server-side. This is the client-side twin of that function, for the exact
 * reason [OpenSkyClient] exists on the flight side: this app runs entirely
 * on [SeedData] with [Config.SNAPSHOT_URL] blank, so there's no backend
 * process to poll Stooq on everyone's behalf — the request has to come from
 * the device itself.
 *
 * Every call is one request for every tracked ticker at once ([Holdings.allTickers],
 * currently 8 symbols across 8 people) — never a per-person request — so
 * polling this stays a single small GET per interval no matter how many
 * billionaires are being tracked. See `TrackerViewModel.startLiveWealthPolling`
 * for the interval and for how a still-empty result (Stooq down, rate-limited,
 * or simply offline) just means nobody gets a fresher anchor that tick,
 * never a crash or a blanked-out number.
 */
object StooqClient {
    private val client get() = NetworkClients.shared

    private const val USER_AGENT = "trillionaire-tracker/0.1 (+https://github.com/Hostu404)"

    /**
     * Looks up the latest close for each of [tickers] in one batched
     * request. Returns only the symbols Stooq actually priced this call,
     * keyed by uppercase ticker — a symbol missing from the result has no
     * current quote (market closed with a stale/blank row, an unlisted or
     * misspelled symbol, etc.), not necessarily an error. Never throws: a
     * network failure or unexpected response shape is just an empty map, so
     * a caller polling in a loop needs no try/catch of its own.
     */
    suspend fun fetchQuotes(tickers: List<String>): Map<String, Double> {
        if (tickers.isEmpty()) return emptyMap()
        val symbols = tickers.joinToString(",") { "${it.lowercase()}.us" }
        val request = Request.Builder()
            .url("https://stooq.com/q/l/?s=$symbols&f=sd2t2ohlcv&h&e=csv")
            .header("User-Agent", USER_AGENT)
            .build()

        return withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use emptyMap()
                    val body = response.body?.string() ?: return@use emptyMap()
                    parseCsv(body)
                }
            } catch (_: IOException) {
                emptyMap()
            } catch (_: Exception) {
                emptyMap()
            }
        }
    }

    /**
     * Stooq's `e=csv` response is a header row plus one row per symbol —
     * `Symbol,Date,Time,Open,High,Low,Close,Volume` for the `f=sd2t2ohlcv`
     * field list this client requests. Only `Symbol`/`Close` are read; a
     * missing/blank/"N/D" close (holiday, delisted, unknown symbol) just
     * drops that row rather than failing the whole batch.
     */
    private fun parseCsv(body: String): Map<String, Double> {
        val lines = body.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.size < 2) return emptyMap()

        val header = lines[0].split(",")
        val symbolIdx = header.indexOf("Symbol")
        val closeIdx = header.indexOf("Close")
        if (symbolIdx < 0 || closeIdx < 0) return emptyMap()

        val out = mutableMapOf<String, Double>()
        for (line in lines.drop(1)) {
            val cols = line.split(",")
            if (cols.size <= maxOf(symbolIdx, closeIdx)) continue
            val symbol = cols[symbolIdx].substringBefore(".").uppercase()
            val close = cols[closeIdx].trim()
            if (symbol.isEmpty() || close.isEmpty() || close.equals("N/D", ignoreCase = true)) continue
            val price = close.toDoubleOrNull() ?: continue
            out[symbol] = price
        }
        return out
    }
}
