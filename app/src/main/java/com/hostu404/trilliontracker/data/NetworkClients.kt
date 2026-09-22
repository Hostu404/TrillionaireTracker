package com.hostu404.trilliontracker.data

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * One shared [OkHttpClient] — and, with it, one connection pool and one
 * background dispatcher thread pool — for every live client this app owns:
 * [StooqClient], [OpenSkyClient], [AdsbLolClient], [AirplanesLiveClient],
 * [GoogleNewsClient]. Each of those used to build its own `OkHttpClient`,
 * which meant up to five separate thread pools/connection pools getting
 * spun up the moment each object was first touched — real, if modest,
 * per-object setup cost, and pure waste since none of these five need
 * isolation from each other (no per-host auth, no conflicting timeout
 * requirements). One shared client for all of them is OkHttp's own
 * documented recommendation for exactly this situation, and it's the
 * kind of thing worth fixing once here rather than five times over.
 */
object NetworkClients {
    val shared: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
}
