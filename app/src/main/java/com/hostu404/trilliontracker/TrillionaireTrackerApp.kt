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
