package com.hostu404.trilliontracker.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One country's outline, simplified for a small on-device map.
 *
 * [rings] holds one flat `[lon, lat, lon, lat, ...]` array per exterior
 * boundary (a country like Indonesia or the US has several disjoint pieces).
 * Interior holes (an enclave like Lesotho inside South Africa) are dropped —
 * this is a "which country am I looking at" map, not a survey-grade atlas,
 * and the user asked for "pretty basic."
 */
@Serializable
data class CountryShape(
    val name: String,
    val rings: List<List<Double>>
)

/**
 * Loads the bundled world boundary dataset once and caches it in memory —
 * it never changes at runtime, so there's no reason to re-parse a ~125KB
 * JSON file on every recomposition.
 *
 * Source: Natural Earth's public-domain Admin-0 country boundaries
 * (naturalearthdata.com), 1:110m scale, redistributed as TopoJSON by the
 * `world-atlas` npm package (Copyright 2013-2019 Michael Bostock, permissive
 * "use/copy/modify/distribute for any purpose" license) and converted to
 * this flat format at build time — see app/src/main/assets/world_countries.json
 * and the README's Map section for the exact conversion.
 */
object WorldGeo {
    private const val ASSET_PATH = "world_countries.json"

    @Volatile
    private var cached: List<CountryShape>? = null

    private val json = Json { ignoreUnknownKeys = true }

    fun countries(context: Context): List<CountryShape> {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: loadFromAssets(context).also { cached = it }
        }
    }

    private fun loadFromAssets(context: Context): List<CountryShape> = try {
        val text = context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
        json.decodeFromString<List<CountryShape>>(text)
    } catch (e: Exception) {
        emptyList()
    }
}

/**
 * A body of water's label and where to center it — there's no boundary
 * dataset for oceans/seas the way there is for countries (they're not
 * polygons with a clean authoritative outline at this scale), so this is a
 * short, hand-picked list of the major ones, good enough for "which ocean
 * is this" at a glance. Coordinates are the conventional label point used
 * on most reference atlases, not a computed centroid.
 */
data class OceanLabel(val name: String, val lat: Double, val lon: Double)

val OCEAN_LABELS: List<OceanLabel> = listOf(
    OceanLabel("Pacific Ocean", 0.0, -155.0),
    OceanLabel("Atlantic Ocean", 5.0, -35.0),
    OceanLabel("Indian Ocean", -25.0, 75.0),
    OceanLabel("Arctic Ocean", 82.0, 0.0),
    OceanLabel("Southern Ocean", -63.0, 10.0),
    OceanLabel("Mediterranean Sea", 34.5, 18.0),
    OceanLabel("Caribbean Sea", 15.0, -75.0),
    OceanLabel("Gulf of Mexico", 25.0, -90.0),
    OceanLabel("South China Sea", 12.0, 114.0),
    OceanLabel("Bering Sea", 58.0, -178.0)
)
