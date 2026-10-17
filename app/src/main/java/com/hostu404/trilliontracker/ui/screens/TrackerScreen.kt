package com.hostu404.trilliontracker.ui.screens

import com.hostu404.trilliontracker.BuildConfig
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hostu404.trilliontracker.data.Config
import com.hostu404.trilliontracker.data.ConnectionsRepository
import com.hostu404.trilliontracker.data.FlightState
import com.hostu404.trilliontracker.data.VesselState
import com.hostu404.trilliontracker.data.Person
import com.hostu404.trilliontracker.ui.Format
import com.hostu404.trilliontracker.ui.TrackerUiState
import com.hostu404.trilliontracker.ui.components.ConnectionsSection
import com.hostu404.trilliontracker.ui.components.DayChange
import com.hostu404.trilliontracker.ui.components.PersonPhoto
import com.hostu404.trilliontracker.ui.components.SheetLine
import com.hostu404.trilliontracker.ui.components.ThemeLabel
import com.hostu404.trilliontracker.ui.theme.TT
import java.util.Locale
import kotlin.math.abs

/**
 * The one main page, as one bordered sheet: is anyone a trillionaire right
 * now and what everyone's money could fund, then each tracked person,
 * richest first, one compact row each. Tapping a row opens their page.
 */
@Composable
fun TrackerScreen(
    state: TrackerUiState,
    onPersonClick: (String) -> Unit,
    onRefresh: () -> Unit
) {
    val threshold = state.snapshot?.thresholdUsd ?: 1_000_000_000_000.0
    val context = LocalContext.current
    val connections = remember { ConnectionsRepository.all(context) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
    ) {
        Header(state, onRefresh)
        Spacer(Modifier.height(8.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .border(1.dp, TT.border, TT.panelShape)
        ) {
            StatusPanel(state, threshold)
            SheetLine()
            ConnectionsSection(
                people = Config.TRACKED_PEOPLE.mapNotNull { id -> state.people.find { it.id == id } },
                links = connections
            )
            SheetLine()
            Trending(state.trendingThemes)
            state.rankedPeople.forEachIndexed { index, person ->
                SheetLine()
                PersonRow(
                    rank = index + 1,
                    person = person,
                    projected = state.projected(person),
                    threshold = threshold,
                    onClick = { onPersonClick(person.id) }
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Header(state: TrackerUiState, onRefresh: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                // The link drops under the title on a phone too narrow for both.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "TRILLIONAIRE TRACKER",
                        color = TT.inkPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        modifier = Modifier.alignByBaseline()
                    )
                    Text(
                        text = "View source ↗",
                        color = TT.accent,
                        fontSize = 11.sp,
                        maxLines = 1,
                        modifier = Modifier
                            .alignByBaseline()
                            .clickable { uriHandler.openUri(Config.REPO_URL) }
                    )
                }
                Text(
                    text = statusLine(state),
                    color = TT.inkMuted,
                    fontSize = 11.sp
                )
            }
            Text(
                text = "↻",
                color = TT.inkPrimary,
                fontSize = 18.sp,
                modifier = Modifier
                    .border(1.dp, TT.border, TT.panelShape)
                    .clickable(onClickLabel = "Refresh", onClick = onRefresh)
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
        state.error?.let { error ->
            Spacer(Modifier.height(6.dp))
            Text(text = "! last read failed: $error", color = TT.critical, fontSize = 11.sp)
        }
    }
}

/**
 * The question the app is named for, then what everyone's money could
 * buy: is anyone a trillionaire right now (and for how long), then their
 * combined wealth as days of a poverty-free world — a comparison against a
 * published cost estimate (see [TrackerUiState.daysOfPovertyFreeWorld]),
 * labelled as such.
 */
@Composable
private fun StatusPanel(state: TrackerUiState, threshold: Double) {
    val leader = state.leader
    val projected = leader?.let { state.projected(it) } ?: 0.0
    val over = leader != null && projected >= threshold
    val nowSeconds = state.nowMillis / 1000
    val crossings = state.snapshot?.crossingHistory.orEmpty()
    val ongoing = leader?.let { l -> crossings.lastOrNull { it.ongoing && it.personId == l.id } }
    // Crossings that have ended, newest first.
    val past = crossings.filter { !it.ongoing }.asReversed().take(3)
    val days = state.daysOfPovertyFreeWorld

    Column(Modifier.padding(12.dp)) {
        PanelLabel("TRILLIONAIRE RIGHT NOW?")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (over) "YES" else "NO",
                color = TT.inkPrimary,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
            )
            if (leader != null) {
                Spacer(Modifier.width(10.dp))
                Text(
                    text = when {
                        over && ongoing != null ->
                            "${leader.name} · over \$1T for ${Format.duration(nowSeconds - ongoing.crossedAtEpoch)}"
                        over -> "${leader.name} · ${Format.compactUsd(projected - threshold)} past \$1T"
                        else -> "Closest: ${leader.name} · ${Format.compactUsd(threshold - projected)} to go"
                    },
                    color = TT.inkSecondary,
                    fontSize = 12.sp
                )
            }
        }
        past.forEach { crossing ->
            Text(
                text = "Before: ${crossing.personName} · ${Format.dateOnly(crossing.crossedAtEpoch)} · " +
                    "held ${Format.duration(crossing.heldForSeconds)}",
                color = TT.inkMuted,
                fontSize = 11.sp
            )
        }

        Spacer(Modifier.height(8.dp))
        PanelLabel("TOTAL WEALTH TRACKED")
        Text(
            text = Format.exactUsd(state.totalProjectedWealth),
            color = TT.inkPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "Could fund a poverty-free world for ${Format.wholeCount(days)} days " +
                "(≈ ${String.format(Locale.UK, "%.1f", days / 365.25)} years)",
            color = TT.inkSecondary,
            fontSize = 11.sp
        )
        Text(
            text = "at ${Format.compactUsd(Config.ANNUAL_POVERTY_ELIMINATION_COST_USD)} a year " +
                "to end extreme poverty (NBER, 2025)",
            color = TT.inkMuted,
            fontSize = 10.sp
        )
    }
}

/** The three most common themes in everyone's current headlines. */
@Composable
private fun Trending(themes: List<Pair<String, Int>>) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        PanelLabel("TRENDING TOPICS")
        if (themes.isEmpty()) {
            Text(text = "No tagged headlines right now", color = TT.inkMuted, fontSize = 11.sp)
        }
        themes.forEach { (theme, count) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                ThemeLabel(theme = theme, color = TT.inkPrimary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text(
                    text = if (count == 1) "1 headline" else "$count headlines",
                    color = TT.inkMuted,
                    fontSize = 11.sp
                )
            }
        }
    }
}

/** A small grey heading inside [StatusPanel]. */
@Composable
private fun PanelLabel(text: String) {
    Text(
        text = text,
        color = TT.inkMuted,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp
    )
}

/** Where the figures come from right now, and how fresh they are. */
private fun statusLine(state: TrackerUiState): String {
    val version = "v${BuildConfig.VERSION_NAME}"
    val snapshot = state.snapshot
    return when {
        snapshot == null -> "$version · loading"
        state.usingSeed -> "$version · built-in figures, not live"
        else -> "$version · updated ${Format.agoShort(snapshot.generatedAt, state.nowMillis / 1000)}"
    }
}

/**
 * One person in three lines: name and today's move, live net worth, then
 * company and the distance to (or past) $1T.
 */
@Composable
private fun PersonRow(
    rank: Int,
    person: Person,
    projected: Double,
    threshold: Double,
    onClick: () -> Unit
) {
    val gap = projected - threshold
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Open ${person.name}", onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PersonPhoto(name = person.name, photoUrl = person.photoUrl, size = 48.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            // Their plane or boat on the move, followed live (as on their page).
            val moving = listOfNotNull(
                "✈".takeIf { person.flight?.state == FlightState.AIRBORNE },
                "⚓".takeIf { person.vessel?.state == VesselState.UNDERWAY }
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The name and any vehicle icon share the space before today's change.
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "$rank ${person.name.uppercase()}",
                        color = TT.inkPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (moving.isNotEmpty()) {
                        Text(
                            text = " " + moving.joinToString(" "),
                            color = TT.inkPrimary,
                            fontSize = 12.sp,
                            modifier = Modifier.semantics {
                                contentDescription = listOfNotNull(
                                    "plane in the air".takeIf { "✈" in moving },
                                    "boat underway".takeIf { "⚓" in moving }
                                ).joinToString(" and ")
                            }
                        )
                    }
                }
                DayChange(deltaUsd = person.dayChangeUsd, suffix = "", fontSize = 11.sp)
            }
            Text(
                text = Format.exactUsd(projected),
                color = TT.inkPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "${person.company} · " +
                    if (gap >= 0) "${Format.compactUsd(gap)} past \$1T" else "${Format.compactUsd(abs(gap))} to \$1T",
                color = TT.inkMuted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
