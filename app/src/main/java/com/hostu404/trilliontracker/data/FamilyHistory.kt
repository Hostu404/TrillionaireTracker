package com.hostu404.trilliontracker.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One sourced fact-bearer within a [FamilyGeneration] — a specific
 * relative, or an honestly-unnamed placeholder for one ([name] null) when
 * the source identifies the relation and what they did but not their name.
 * Every member carries its own [sourceUrl]/[sourceLabel], not just the
 * generation as a whole, in case different facts about the same generation
 * end up sourced from different places later.
 */
@Serializable
data class FamilyMember(
    val name: String? = null,
    val relation: String,
    val years: String? = null,
    val facts: List<String> = emptyList(),
    val sourceUrl: String,
    val sourceLabel: String
)

/**
 * One cited fact, detached from any particular member — reused for
 * [FamilyGeneration.globalContext], [FamilyGeneration.familyBusiness], and
 * every field of [WorldSnapshot]. Same shape as a source on a [FamilyMember]
 * fact, just not tied to one person.
 */
@Serializable
data class SourcedNote(
    val text: String,
    val sourceUrl: String,
    val sourceLabel: String
)

/**
 * The wider backdrop a generation lived through — deliberately separate
 * from [FamilyGeneration.globalContext], which stays reserved for *why the
 * family itself moved, stayed, or the record goes quiet* (the causal,
 * personal thread). This is ambient period color instead: what else was
 * true of the place and moment, whether or not it touched the family
 * directly. Every field is independently optional and independently
 * sourced — a generation with a knowable politics angle but no traceable
 * "what was on the radio" fact just leaves [music] null rather than
 * reaching for a generic potted-history line to fill the slot. A
 * generation with no fixable time window at all (see Brin's Grandparents
 * entry) skips [WorldSnapshot] entirely instead of guessing an era.
 */
@Serializable
data class WorldSnapshot(
    val politics: SourcedNote? = null,
    val music: SourcedNote? = null,
    val technology: SourcedNote? = null
)

/**
 * One step back in a person's documented family history — "Parents",
 * "Grandparents", and so on. [members] holds whoever sourcing actually
 * named for this generation (never forced to a fixed count); [globalContext]
 * is the causal note ("why they left"); [familyBusiness] is null for most
 * generations in practice — this roster is almost entirely first-generation
 * wealth (see the app's own "Known gaps"/design notes on that), so an absent
 * family business is the honest, expected answer, not a gap; [beliefs] covers
 * two related things under one sourced note — a generation's documented
 * religious/denominational background, and any ideological or spiritual
 * movement (Technocracy, Social Credit, and the like) actually documented as
 * shaping the family, not just unremarkable membership in a mainstream faith;
 * [worldSnapshot] is the optional wider-picture add-on described on that
 * type; and
 * [trailEndsNote] — set only on the generation where sourcing actually runs
 * out — explains why the tree stops rather than just stopping silently.
 */
@Serializable
data class FamilyGeneration(
    val label: String,
    val region: String? = null,
    val members: List<FamilyMember> = emptyList(),
    val globalContext: SourcedNote? = null,
    val familyBusiness: SourcedNote? = null,
    val beliefs: SourcedNote? = null,
    val worldSnapshot: WorldSnapshot? = null,
    val trailEndsNote: String? = null
)

/** One person's documented family history, oldest generation last. */
@Serializable
data class FamilyHistoryEntry(
    val generations: List<FamilyGeneration>
)

@Serializable
private data class FamilyHistoryFile(
    val people: Map<String, FamilyHistoryEntry> = emptyMap()
)

/**
 * Loads `family_history.json` from the app's bundled assets once and caches
 * it in memory — same pattern as [WorldGeo], and for the same reason: this
 * is static, hand-researched narrative content, not something that changes
 * between polls, so it ships with the app itself rather than riding the
 * live snapshot pipeline that `holdings.json`/`snapshot_worker.py` feed.
 *
 * A person absent from the map simply has no Family History entry point
 * shown — see `PersonDetailScreen`'s use of [entryFor] — rather than the
 * screen ever showing an empty or guessed one. `brin` was the original
 * proof-of-concept entry (chosen first because his family's story happens
 * to be unusually well documented on the record); the roster has since
 * grown to cover all 10 currently-tracked people (see
 * `family_history.json`'s own `_comment`), each stopping wherever its own
 * sourcing genuinely runs out rather than all reaching the same depth.
 */
object FamilyHistoryRepository {
    private const val ASSET_PATH = "family_history.json"

    @Volatile
    private var cached: Map<String, FamilyHistoryEntry>? = null

    private val json = Json { ignoreUnknownKeys = true }

    fun entryFor(personId: String, context: Context): FamilyHistoryEntry? =
        allEntries(context)[personId]

    private fun allEntries(context: Context): Map<String, FamilyHistoryEntry> {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: loadFromAssets(context).also { cached = it }
        }
    }

    private fun loadFromAssets(context: Context): Map<String, FamilyHistoryEntry> = try {
        val text = context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
        json.decodeFromString<FamilyHistoryFile>(text).people
    } catch (e: Exception) {
        emptyMap()
    }
}
