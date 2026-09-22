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
 */
class RemoteSnapshotSource(
    private val url: String,
    private val client: OkHttpClient = defaultClient()
) : SnapshotSource {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override suspend fun fetch(): Snapshot = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("snapshot HTTP ${response.code}")
            }
            val body = response.body?.string()
                ?: throw IllegalStateException("snapshot body was empty")
            json.decodeFromString(Snapshot.serializer(), body)
        }
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
