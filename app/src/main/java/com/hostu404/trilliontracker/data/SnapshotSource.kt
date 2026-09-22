package com.hostu404.trilliontracker.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

interface SnapshotSource {
    suspend fun fetch(): Snapshot
}

/** Runs the app with no backend at all. */
class SeedSnapshotSource : SnapshotSource {
    override suspend fun fetch(): Snapshot = SeedData.snapshot()
}

/**
 * Reads the single published document.
 *
 * Note what is absent: no API key, no per-user quota, no upstream provider. This
 * is a plain cached GET, which is why the cost of an extra million users is the
 * CDN's problem and not ours.
 *
 * **Conditional GET.** The backend only publishes a new `snapshot.json` every
 * 5 minutes (see `.github/workflows/snapshot.yml`), but the client polls
 * every [Config.POLL_INTERVAL_SECONDS] (60s) — so on a healthy poll cycle,
 * 3 or 4 out of every 5 fetches would otherwise re-download a file that
 * hasn't actually changed. GitHub Pages (Fastly) serves static files with
 * real `ETag`/`Last-Modified` headers, so this remembers the last one it saw
 * and sends it back as `If-None-Match`/`If-Modified-Since`; a `304 Not
 * Modified` response (empty body) means "nothing changed" and the
 * previously-parsed [Snapshot] is reused as-is, with no re-parse and no
 * bandwidth spent on the body. A `200` is parsed and cached normally,
 * exactly as before. This is purely a bandwidth/battery optimization — a
 * server that ignores the conditional headers and always returns `200` still
 * works correctly, just without the savings.
 */
class RemoteSnapshotSource(
    private val url: String,
    private val client: OkHttpClient = defaultClient()
) : SnapshotSource {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Volatile private var cachedSnapshot: Snapshot? = null
    @Volatile private var cachedETag: String? = null
    @Volatile private var cachedLastModified: String? = null

    override suspend fun fetch(): Snapshot = withContext(Dispatchers.IO) {
        val requestBuilder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
        cachedETag?.let { requestBuilder.header("If-None-Match", it) }
        cachedLastModified?.let { requestBuilder.header("If-Modified-Since", it) }

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (response.code == 304) {
                // Only reachable when we sent a conditional header above,
                // which only happens once cachedSnapshot is already set.
                return@use cachedSnapshot
                    ?: throw IllegalStateException("snapshot HTTP 304 with nothing cached")
            }
            if (!response.isSuccessful) {
                throw IllegalStateException("snapshot HTTP ${response.code}")
            }
            val body = response.body?.string()
                ?: throw IllegalStateException("snapshot body was empty")
            val parsed = json.decodeFromString(Snapshot.serializer(), body)
            cachedSnapshot = parsed
            cachedETag = response.header("ETag")
            cachedLastModified = response.header("Last-Modified")
            parsed
        }
    }

    companion object {
        /**
         * A dedicated builder off the app-wide [NetworkClients.shared] client
         * (same dispatcher/connection pool, no second thread pool spun up)
         * with this source's own longer timeouts — a multi-hundred-KB JSON
         * document over a CDN warrants more slack than the ~8s used for a
         * single small quote/position request.
         */
        fun defaultClient(): OkHttpClient = NetworkClients.shared.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
