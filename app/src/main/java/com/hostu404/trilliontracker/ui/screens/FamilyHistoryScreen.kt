package com.hostu404.trilliontracker.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hostu404.trilliontracker.data.FamilyGeneration
import com.hostu404.trilliontracker.data.FamilyHistoryRepository
import com.hostu404.trilliontracker.data.FamilyMember
import com.hostu404.trilliontracker.data.SourcedNote
import com.hostu404.trilliontracker.ui.components.SheetLine
import com.hostu404.trilliontracker.ui.theme.TT

/**
 * "Where they came from": one generation per section, present to past,
 * every fact carrying its own source. See `family_history.json`'s own
 * `_comment` for the sourcing bar: a missing name or a generation that just
 * stops is an honest ending, not a gap to paper over with a guess.
 *
 * One bordered sheet like every other screen, generations split by a line.
 * Reached from a person's page only when [FamilyHistoryRepository] has an
 * entry; the "nothing documented" fallback is there rather than crashing.
 */
@Composable
fun FamilyHistoryScreen(
    personId: String,
    personName: String
) {
    val context = LocalContext.current
    val entry = remember(personId) { FamilyHistoryRepository.entryFor(personId, context) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .border(1.dp, TT.border, TT.panelShape)
        ) {
            Column(Modifier.padding(12.dp)) {
                Label("FAMILY HISTORY")
                Text(
                    text = personName.uppercase(),
                    color = TT.inkPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (entry == null || entry.generations.isEmpty()) {
                        "Nothing documented yet."
                    } else {
                        "As far back as the record — and a real source — actually goes."
                    },
                    color = TT.inkMuted,
                    fontSize = 11.sp
                )
            }
            entry?.generations?.forEach { generation ->
                SheetLine()
                Generation(generation)
            }
        }
    }
}

/**
 * The span of 4-digit years literally present in this generation's
 * members' [FamilyMember.years] text, never an inferred era.
 */
private val yearPattern = Regex("""\b(1[6-9]\d{2}|20\d{2})\b""")

private fun yearsOnRecord(generation: FamilyGeneration): String? {
    val years = generation.members.flatMap { member ->
        member.years?.let { yearPattern.findAll(it).map { m -> m.value.toInt() }.toList() }.orEmpty()
    }
    if (years.isEmpty()) return null
    return if (years.min() == years.max()) "${years.min()}" else "${years.min()}–${years.max()}"
}

@Composable
private fun Generation(generation: FamilyGeneration) {
    Column(Modifier.padding(12.dp)) {
        Label(listOfNotNull(generation.label.uppercase(), yearsOnRecord(generation)).joinToString("  "))
        generation.region?.let { Text(text = it, color = TT.inkMuted, fontSize = 11.sp) }

        generation.members.forEach { Member(it) }

        generation.globalContext?.let { Note("CONTEXT", it) }
        generation.familyBusiness?.let { Note("FAMILY BUSINESS", it) }
        generation.beliefs?.let { Note("FAITH & BELIEFS", it) }
        generation.worldSnapshot?.let { snapshot ->
            snapshot.politics?.let { Note("THE WIDER PICTURE · POLITICS", it) }
            snapshot.music?.let { Note("THE WIDER PICTURE · MUSIC", it) }
            snapshot.technology?.let { Note("THE WIDER PICTURE · TECHNOLOGY", it) }
        }

        // The record's honest end, where the sourcing runs out.
        generation.trailEndsNote?.let { note ->
            Label("○ TRAIL ENDS HERE", Modifier.padding(top = 10.dp))
            Text(text = note, color = TT.inkMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun Member(member: FamilyMember) {
    Column(Modifier.padding(top = 8.dp)) {
        Text(
            text = listOfNotNull(member.name ?: "Unnamed", member.relation, member.years).joinToString(" · "),
            color = TT.inkPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
        member.facts.forEach { fact ->
            Row {
                Text(text = "· ", color = TT.inkSecondary, fontSize = 12.sp)
                Text(text = fact, color = TT.inkSecondary, fontSize = 12.sp)
            }
        }
        Source(member.sourceLabel, member.sourceUrl)
    }
}

/** One labelled, cited note: the causal context, family business, beliefs or a wider-picture fact. */
@Composable
private fun Note(label: String, note: SourcedNote) {
    Column(Modifier.padding(top = 10.dp)) {
        Label(label)
        Text(text = note.text, color = TT.inkSecondary, fontSize = 12.sp)
        Source(note.sourceLabel, note.sourceUrl)
    }
}

@Composable
private fun Source(label: String, url: String) {
    val uriHandler = LocalUriHandler.current
    Text(
        text = "$label ↗",
        color = TT.accent,
        fontSize = 11.sp,
        modifier = Modifier
            .clickable { uriHandler.openUri(url) }
            .padding(vertical = 2.dp)
    )
}

@Composable
private fun Label(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = TT.inkMuted,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = modifier
    )
}
