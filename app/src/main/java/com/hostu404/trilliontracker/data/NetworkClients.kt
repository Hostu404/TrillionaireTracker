package com.hostu404.trilliontracker.data

import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * One shared [OkHttpClient] — and, with it, one connection pool and one
 * background dispatcher thread pool — for every live client this app owns:
 * [LiveQuoteClient], [OpenSkyClient], [AdsbLolClient],
 * [GoogleNewsClient], and (via [OkHttpClient.newBuilder], which keeps the
 * same dispatcher/connection pool while overriding per-use settings like
 * timeouts) [RemoteSnapshotSource]. Each of those used to build its own
 * `OkHttpClient`, which meant several separate thread pools/connection pools
 * getting spun up the moment each object was first touched — real, if
 * modest, per-object setup cost, and pure waste since none of these need
 * isolation from each other (no per-host auth, no conflicting timeout
 * requirements). One shared client for all of them is OkHttp's own
 * documented recommendation for exactly this situation, and it's the
 * kind of thing worth fixing once here rather than several times over.
 *
 * [Dispatcher.maxRequestsPerHost] is raised from OkHttp's default of 5:
 * [LiveQuoteClient] alone fires one concurrent request per tracked ticker
 * (a dozen or so) at the *same* host (`query2.finance.yahoo.com`), since
 * Yahoo's chart endpoint has no batch form — at the default cap, only 5 of
 * those run at once and the rest queue behind them, turning one poll into
 * two or three sequential round-trips for no reason other than an unrelated
 * host's default. 20 comfortably covers every current per-host burst
 * (quotes, OpenSky/adsb.lol fallbacks, one snapshot fetch) with headroom,
 * while [Dispatcher.maxRequests] (the whole-client cap, left at its default
 * of 64) still bounds total concurrency across all hosts.
 */
object NetworkClients {
    val shared: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = 20 })
        .build()
}
