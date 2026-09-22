package com.hostu404.trilliontracker.data

class TrackerRepository(
    private val remote: SnapshotSource?,
    private val seed: SnapshotSource = SeedSnapshotSource()
) {

    val hasBackend: Boolean get() = remote != null

    /**
     * Never throws. A failed read falls back to the last good shape rather than
     * showing an empty screen, and the UI surfaces the staleness instead.
     */
    suspend fun load(): LoadResult = try {
        val source = remote ?: seed
        LoadResult(snapshot = source.fetch(), fromSeed = remote == null, error = null)
    } catch (t: Throwable) {
        LoadResult(snapshot = seed.fetch(), fromSeed = true, error = t.message ?: "network error")
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
