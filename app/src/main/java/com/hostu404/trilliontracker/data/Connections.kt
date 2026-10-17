package com.hostu404.trilliontracker.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One person's side of a [Connection], with its own source. */
@Serializable
data class ConnectionFact(
    val person: String,
    val text: String,
    val sourceLabel: String,
    val sourceUrl: String
)

/**
 * Something two or more tracked people share in their backgrounds —
 * "Apartheid-era southern Africa", "Stanford", "Tolkien" — with each
 * person's own sourced fact. Never business or politics: see
 * `connections.json`'s `_comment`.
 */
@Serializable
data class Connection(
    val title: String,
    val facts: List<ConnectionFact>,
    /** A source about the link as a whole, when there is one. */
    val note: SourcedNote? = null
) {
    val people: List<String> get() = facts.map { it.person }
}

@Serializable
private data class ConnectionsFile(val links: List<Connection> = emptyList())

/**
 * Loads `connections.json` from the app's assets once, the same way
 * [FamilyHistoryRepository] loads family history: static, sourced content
 * that ships with the app rather than the live snapshot.
 */
object ConnectionsRepository {
    private const val ASSET_PATH = "connections.json"

    @Volatile
    private var cached: List<Connection>? = null

    private val json = Json { ignoreUnknownKeys = true }

    fun all(context: Context): List<Connection> {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: load(context).also { cached = it }
        }
    }

    private fun load(context: Context): List<Connection> = try {
        val text = context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
        json.decodeFromString<ConnectionsFile>(text).links
    } catch (e: Exception) {
        emptyList()
    }
}
