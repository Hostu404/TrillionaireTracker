package com.hostu404.trilliontracker

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.hostu404.trilliontracker.ui.screens.PersonDetailScreen
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * Registered in AndroidManifest.xml as the app's `android:name`, so Coil
 * picks up [newImageLoader] automatically for every `AsyncImage` in the app
 * — currently just the portrait on [com.hostu404.trilliontracker.ui.screens.PersonDetailScreen].
 *
 * The only reason this class exists: Wikimedia's servers apply their
 * published API etiquette policy to plain Commons file requests too, not
 * just the api.wikimedia.org endpoints, and that policy rejects or
 * deprioritizes requests carrying a generic HTTP-library User-Agent (a bare
 * "okhttp/4.x", which is exactly what Coil's default client sends). That
 * reads, from inside the app, as an image that silently never loads — no
 * crash, and nothing under a "Coil" Logcat tag either, since Coil doesn't
 * log anything by default without an explicit Logger attached. A browser
 * works because it sends an ordinary browser User-Agent instead.
 *
 * Fix is the same one the Python backend already uses for its own Wikipedia
 * calls (see USER_AGENT in snapshot_worker.py): identify the app and give
 * a contact point, on every request Coil makes, not just Wikimedia's.
 */
class TrillionaireTrackerApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()

        // WorldGeo.countries() parses a ~125KB bundled JSON file once and
        // caches it forever (see its own doc comment) — cheap over the
        // process's whole lifetime, but the first-ever call used to happen
        // synchronously inside `remember { }` on the main thread, right when
        // someone's first flight/vessel detail screen composes. Doing that
        // parse here instead, on a plain background thread before any screen
        // needs it, means that first detail screen open just hits the
        // already-warmed cache rather than paying for the parse itself. A
        // detail screen opened before this finishes still works correctly —
        // WorldGeo.countries() does its own synchronized/@Volatile caching,
        // so a caller that gets there first just does the parse itself, same
        // as before this existed.
        Thread({ com.hostu404.trilliontracker.data.WorldGeo.countries(this) }, "warm-world-geo").start()
    }

    override fun newImageLoader(): ImageLoader {
        val client = OkHttpClient.Builder()
            .addInterceptor(UserAgentInterceptor)
            .build()

        return ImageLoader.Builder(this)
            .okHttpClient(client)
            .build()
    }
}

private object UserAgentInterceptor : Interceptor {
    private const val USER_AGENT = "trillionaire-tracker/0.1 (+https://github.com/Hostu404)"

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header("User-Agent", USER_AGENT)
            .build()
        return chain.proceed(request)
    }
}
