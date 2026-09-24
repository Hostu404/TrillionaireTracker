package com.hostu404.trilliontracker.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hostu404.trilliontracker.data.FamilyGeneration
import com.hostu404.trilliontracker.data.FamilyHistoryRepository
import com.hostu404.trilliontracker.data.FamilyMember
import com.hostu404.trilliontracker.data.SourcedNote
import com.hostu404.trilliontracker.data.WorldSnapshot
import com.hostu404.trilliontracker.ui.components.NoteChip
import com.hostu404.trilliontracker.ui.components.StatusChip
import com.hostu404.trilliontracker.ui.components.hudCorners
import com.hostu404.trilliontracker.ui.components.hudTouchable
import com.hostu404.trilliontracker.ui.theme.TT

/**
 * "Where they came from" — a documented-backward river through a person's
 * family, one generation per stop, each fact carrying its own source. See
 * `family_history.json`'s own `_comment` for the sourcing bar this holds
 * itself to: a missing name or a generation that just stops is an honest
 * ending, not a gap to paper over with a guess.
 *
 * Reached from [PersonDetailScreen]'s entry card, which only ever shows for
 * a person [FamilyHistoryRepository] actually has an entry for — this
 * screen should never legitimately render its own "nothing documented yet"
 * fallback in practice, but it's there rather than crashing if it ever did.
 *
 * Oldest generation renders last (top-to-bottom = present-to-past), with a
 * thin connecting line between stops standing in for the "river" — a
 * continuous thread back through time, same idea the user described it
 * with, rather than a literal branching tree (siblings, multiple marriages)
 * this data was never trying to capture in the first place.
 */
@Composable
fun FamilyHistoryScreen(
    personId: String,
    personName: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val entry = remember(personId) { FamilyHistoryRepository.entryFor(personId, context) }

    Box(Modifier.fillMaxSize()) {
        if (entry == null || entry.generations.isEmpty()) {
            Column(Modifier.padding(24.dp)) {
                BackRow(onBack = onBack)
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Nothing documented yet for $personName.",
                    color = TT.inkMuted,
                    fontSize = 13.sp
                )
            }
            return@Box
        }

        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            item {
                BackRow(onBack = onBack)
                Spacer(Modifier.height(10.dp))
                // Same eyebrow-label + title shape as PersonDetailScreen's
                // "[ SUBJECT ]" header (added 2026-09-24, same pass) — the
                // bracket/monospace/letter-spaced chrome for the label, the
                // person's actual name left in the normal readable font
                // rather than monospace, same "a label is UI chrome, a name
                // is not" rule that header follows.
                Text(
                    text = "[ FAMILY HISTORY ]",
                    color = TT.accentCyan,
                    fontSize = 10.sp,
                    fontFamily = TT.monoNumeric,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 2.sp
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = personName,
                    color = TT.inkPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "As far back as the record — and a real source — actually goes.",
                    color = TT.inkMuted,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(12.dp))
            }

            items(entry.generations.size) { index ->
                val generation = entry.generations[index]
                if (index > 0) {
                    RiverConnector()
                }
                GenerationCard(generation)
                if (index == entry.generations.lastIndex && generation.trailEndsNote != null) {
                    RiverConnector()
                    TrailEndsMarker(generation.trailEndsNote)
                }
            }
        }
    }
}

/**
 * This screen's original back-button chip: a plain raised-surface panel
 * (10dp cut, "← Back" text at 13sp Medium, no shadow) sitting directly on
 * the screen's own dark background. Briefly changed on 2026-09-24 to match
 * PersonDetailScreen's photo-overlay button (14dp cut, icon-only "←", drop
 * shadow) in the name of coherence, then reverted the same day — this
 * simpler chip was the one worth keeping, so PersonDetailScreen's button
 * now matches THIS shape and content instead (see that button's own doc
 * comment). No shadow/glow here because there's no photo underneath this
 * one competing for contrast the way there is over there.
 */
@Composable
private fun BackRow(onBack: () -> Unit) {
    val shape = TT.panelShape(10.dp)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .clip(shape)
                .background(TT.surfaceRaised)
                .border(1.dp, TT.border, shape)
                .hudTouchable(cornerLength = 6.dp, cornerInset = 2.dp, onClick = onBack)
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Text(
                text = "← Back",
                color = TT.accentCyan,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/** A short vertical thread between two generation stops — the "river" between them. */
@Composable
private fun RiverConnector() {
    Box(
        Modifier
            .padding(start = 22.dp)
            .width(2.dp)
            .height(18.dp)
            .background(TT.border)
    )
}

/**
 * Every 4-digit year literally appearing in this generation's members'
 * [FamilyMember.years] strings — not an inference about when the
 * generation "was," just the raw years already present in already-sourced
 * text (a birth year, a birth-death span, whatever's on record for
 * whoever's named). Deliberately not interpreted into a claimed era: years
 * comes from free text like "b. c. 1946" or "1948–2024", entered per
 * source in whatever form the source itself uses, so this only ever
 * surfaces exact values already there, never fills gaps between them.
 */
private val yearPattern = Regex("""\b(1[6-9]\d{2}|20\d{2})\b""")

private fun yearsOnRecord(generation: FamilyGeneration): Pair<Int, Int>? {
    val years = generation.members.flatMap { member ->
        member.years?.let { yearPattern.findAll(it).map { m -> m.value.toInt() }.toList() } ?: emptyList()
    }
    if (years.isEmpty()) return null
    return years.min() to years.max()
}

@Composable
private fun GenerationCard(generation: FamilyGeneration) {
    val range = yearsOnRecord(generation)

    Column(
        Modifier
            .fillMaxWidth()
            .background(TT.surface, TT.panelShape(14.dp))
            .border(1.dp, TT.border, TT.panelShape(14.dp))
            .hudCorners()
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "[ ${generation.label.uppercase()} ]",
                color = TT.accentCyan,
                fontSize = 11.sp,
                fontFamily = TT.monoNumeric,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.5.sp
            )
            if (range != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (range.first == range.second) "${range.first}" else "${range.first}–${range.second}",
                    color = TT.inkMuted,
                    fontSize = 11.sp,
                    fontFamily = TT.monoNumeric,
                    letterSpacing = 0.5.sp
                )
            }
        }
        generation.region?.let { region ->
            Spacer(Modifier.height(4.dp))
            Text(
                text = region.uppercase(),
                color = TT.inkMuted,
                fontSize = 11.sp,
                fontFamily = TT.monoNumeric,
                letterSpacing = 0.8.sp
            )
        }

        Spacer(Modifier.height(10.dp))

        generation.members.forEachIndexed { index, member ->
            MemberBlock(member)
            if (index != generation.members.lastIndex) {
                Spacer(Modifier.height(10.dp))
            }
        }

        generation.globalContext?.let { note ->
            Spacer(Modifier.height(12.dp))
            SourcedNoteBlock(label = "WHY THEY LEFT", note = note)
        }

        generation.familyBusiness?.let { note ->
            Spacer(Modifier.height(12.dp))
            SourcedNoteBlock(label = "FAMILY BUSINESS", note = note)
        }

        generation.beliefs?.let { note ->
            Spacer(Modifier.height(12.dp))
            SourcedNoteBlock(label = "FAITH & BELIEFS", note = note)
        }

        generation.worldSnapshot?.let { snapshot ->
            WorldSnapshotBlock(snapshot)
        }
    }
}

/**
 * The optional "wider picture" facets — politics/music/technology of the
 * generation's era, independent of whether any of it touched the family
 * directly (that causal thread is [FamilyGeneration.globalContext] instead).
 * Each present field gets its own labeled, independently-sourced block;
 * nothing renders at all if every field is null.
 */
@Composable
private fun WorldSnapshotBlock(snapshot: WorldSnapshot) {
    val facets = listOfNotNull(
        snapshot.politics?.let { "POLITICS" to it },
        snapshot.music?.let { "MUSIC" to it },
        snapshot.technology?.let { "TECHNOLOGY" to it }
    )
    if (facets.isEmpty()) return

    Spacer(Modifier.height(12.dp))
    Text(
        text = "THE WIDER PICTURE",
        color = TT.inkMuted,
        fontSize = 10.sp,
        fontFamily = TT.monoNumeric,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.2.sp
    )
    Spacer(Modifier.height(8.dp))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        facets.forEach { (label, note) ->
            SourcedNoteBlock(label = label, note = note)
        }
    }
}

@Composable
private fun MemberBlock(member: FamilyMember) {
    val uriHandler = LocalUriHandler.current

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = member.name ?: "Unnamed",
                color = TT.inkPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.width(8.dp))
            NoteChip(text = member.relation)
        }
        member.years?.let { years ->
            Spacer(Modifier.height(2.dp))
            Text(
                text = years,
                color = TT.inkMuted,
                fontSize = 11.sp,
                fontFamily = TT.monoNumeric,
                letterSpacing = 0.3.sp
            )
        }
        if (member.facts.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                member.facts.forEach { fact ->
                    Row {
                        Text(text = "· ", color = TT.series, fontSize = 13.sp)
                        Text(
                            text = fact,
                            color = TT.inkSecondary,
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "[ ${member.sourceLabel.uppercase()} ↗ ]",
            color = TT.accentCyan,
            fontSize = 10.sp,
            fontFamily = TT.monoNumeric,
            letterSpacing = 0.4.sp,
            modifier = Modifier
                .padding(2.dp)
                .clickable { uriHandler.openUri(member.sourceUrl) }
        )
    }
}

/** One labeled, cited fact block — used for the causal note, family business, and each [WorldSnapshot] facet alike. */
@Composable
private fun SourcedNoteBlock(label: String, note: SourcedNote) {
    val uriHandler = LocalUriHandler.current
    val shape = TT.panelShape(10.dp)

    Column(
        Modifier
            .fillMaxWidth()
            .background(TT.surfaceRaised, shape)
            .border(1.dp, TT.accentYellow.copy(alpha = 0.35f), shape)
            .padding(14.dp)
    ) {
        Text(
            text = label,
            color = TT.accentYellow,
            fontSize = 10.sp,
            fontFamily = TT.monoNumeric,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.2.sp
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = note.text,
            color = TT.inkSecondary,
            fontSize = 12.sp,
            lineHeight = 17.sp
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "[ ${note.sourceLabel.uppercase()} ↗ ]",
            color = TT.accentCyan,
            fontSize = 10.sp,
            fontFamily = TT.monoNumeric,
            letterSpacing = 0.4.sp,
            modifier = Modifier
                .padding(2.dp)
                .clickable { uriHandler.openUri(note.sourceUrl) }
        )
    }
}

/** Renders once, at the deepest generation that has one — the river's honest end, not a dead link. */
@Composable
private fun TrailEndsMarker(note: String) {
    Column(Modifier.padding(top = 2.dp, bottom = 4.dp)) {
        StatusChip(glyph = "○", label = "TRAIL ENDS HERE", color = TT.inkMuted)
        Spacer(Modifier.height(6.dp))
        Text(
            text = note,
            color = TT.inkMuted,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            modifier = Modifier.padding(start = 2.dp)
        )
    }
}
