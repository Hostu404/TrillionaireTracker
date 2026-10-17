package com.hostu404.trilliontracker.data

class TrackerRepository(
    private val remote: SnapshotSource?,
    private val seed: SnapshotSource = SeedSnapshotSource()
) {

    /**
     * Never throws. A failed read falls back to the last good shape rather than
     * showing an empty screen, and the UI surfaces the staleness instead.
     */
    suspend fun load(): LoadResult = try {
        val source = remote ?: seed
        LoadResult(snapshot = source.fetch().onlyTracked(), fromSeed = remote == null, error = null)
    } catch (t: Throwable) {
        LoadResult(snapshot = seed.fetch().onlyTracked(), fromSeed = true, error = t.message ?: "network error")
    }

    /**
     * Keeps [Config.TRACKED_PEOPLE] and drops everyone else, crossings
     * included, and keeps only [Config.TRACKED_NEWS_THEMES] on headlines and
     * in the all-time theme tally.
     */
    private fun Snapshot.onlyTracked(): Snapshot {
        val ids = Config.TRACKED_PEOPLE
        val themes = Config.TRACKED_NEWS_THEMES
        return copy(
            people = people.filter { it.id in ids }.map { person ->
                person.copy(
                    news = person.news.map { if (it.theme in themes) it else it.copy(theme = null) },
                    lifetimeNewsThemes = person.lifetimeNewsThemes.filter { it.theme in themes }
                )
            },
            crossingHistory = crossingHistory.filter { it.personId in ids }
        )
    }

    data class LoadResult(
        val snapshot: Snapshot,
        val fromSeed: Boolean,
        val error: String?
    )

    companion object {
        fun default(): TrackerRepository {
            val url = Config.SNAPSHOT_URL
            return TrackerRepository(
                remote = if (url.isBlank()) null else RemoteSnapshotSource(url)
            )
        }
    }
}
