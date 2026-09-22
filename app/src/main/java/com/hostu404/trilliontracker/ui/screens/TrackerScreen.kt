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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hostu404.trilliontracker.data.Config
import com.hostu404.trilliontracker.data.Crossing
import com.hostu404.trilliontracker.data.FlightState
import com.hostu404.trilliontracker.data.NetWorthEngine
import com.hostu404.trilliontracker.data.Person
import com.hostu404.trilliontracker.ui.Format
import com.hostu404.trilliontracker.ui.TrackerUiState
import com.hostu404.trilliontracker.ui.components.DeltaChip
import com.hostu404.trilliontracker.ui.components.NoteChip
import com.hostu404.trilliontracker.ui.components.RollingNumber
import com.hostu404.trilliontracker.ui.components.Sparkline
import com.hostu404.trilliontracker.ui.components.StatusChip
import com.hostu404.trilliontracker.ui.components.ThresholdGauge
import com.hostu404.trilliontracker.ui.components.hudCorners
import com.hostu404.trilliontracker.ui.theme.TT

@Composable
fun TrackerScreen(
    state: TrackerUiState,
    onPersonClick: (String) -> Unit,
    onRefresh: () -> Unit
) {
    // No background here — App() already paints the shared Saturn
    // image + scrim + scanlines behind the NavHost; an opaque fill on
    // this screen's root would hide all of it.
    Box(
        Modifier
            .fillMaxSize()
    ) {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { Header(state, onRefresh) }
            item { StatusPanel(state) }
            biggestMover(state)?.let { mover ->
                item { BiggestMoverCard(mover) }
            }
            item { SectionLabel(text = "TOP GONKS") }
            itemsIndexed(state.rankedPeople, key = { _, p -> p.id }) { index, person ->
                PersonRow(
                    rank = index + 1,
                    person = person,
                    projected = state.projected(person),
                    isLive = state.isLive(person.id),
                    onClick = { onPersonClick(person.id) },
                    // Live wealth can reorder this list — see
                    // TrackerUiState.rankedPeople — and this is what turns
                    // that reorder into an actual on-screen flip instead of
                    // rows silently teleporting to their new rank.
                    modifier = Modifier.animateItem()
                )
            }
            state.snapshot?.crossingHistory?.takeIf { it.isNotEmpty() }?.let { history ->
                item { SectionLabel(text = "CROSSING HISTORY") }
                // Keyed on personId+crossedAtEpoch (the same identity pair
                // that makes a Crossing unique) rather than left on the
                // default index key — this list gets a new entry inserted
                // at the front every time someone crosses the line, and
                // without a stable key every row below that insertion point
                // reads as "changed content" at its old index instead of
                // "this row just moved," which is extra recomposition and
                // loses row-level animation state for no reason.
                items(
                    history.asReversed(),
                    key = { "${it.personId}-${it.crossedAtEpoch}" }
                ) { crossing ->
                    CrossingRow(crossing)
                }
            }
        }
    }
}

/** A HUD-bracket section header — "[ TOP GONKS ]" in the chrome accent, not the data one. */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = "[ $text ]",
        color = TT.accentCyan,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.6.sp,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
    )
}

/**
 * Refresh only shows up when there's actually something to retry — a failed
 * read. In steady state (seed mode, or a healthy backend) there's nothing
 * for the person to do here, so the control and the subtitle line
 * explaining data provenance are both gone; the one time this screen needs
 * to say anything is when the last read failed, and that error plus a way
 * to retry it now live together in one place instead of a chip that's
 * always present and a paragraph most people never read.
 */
@Composable
private fun Header(state: TrackerUiState, onRefresh: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "◆ TRILLIONAIRE TRACKER",
                color = TT.inkPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 2.sp,
                modifier = Modifier.weight(1f)
            )
            if (state.error != null) {
                NoteChip(
                    text = "retry",
                    color = TT.critical,
                    modifier = Modifier.clickable { onRefresh() }
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth(0.38f)
                .height(2.dp)
                .background(TT.accentCyan.copy(alpha = 0.5f))
        )
        state.error?.let { error ->
            Spacer(Modifier.height(8.dp))
            Text(text = "last read failed: $error", color = TT.critical, fontSize = 11.sp)
        }
    }
}

/**
 * "Is there a trillionaire right now" and "total wealth tracked" used to be
 * two separate full cards — two borders, two cut-corner shapes, two lots of
 * padding for two readouts that are really one status board. One outer
 * panel with an inner divider is the same information in roughly two-thirds
 * the height: boxes nested inside a box rather than stacked side by side,
 * which is also just the HUD idiom this app is already going for elsewhere
 * (see [hudCorners]) — a single instrument panel with multiple readouts,
 * not a stack of separate widgets.
 *
 * The poverty-free-world figure used to carry a couple of sentences of
 * methodology underneath it; that's gone in favor of the plain
 * label-over-value HUD readout style the rest of this panel already uses —
 * see [TrackerUiState.daysOfPovertyFreeWorld] and
 * [Config.ANNUAL_POVERTY_ELIMINATION_COST_USD] for what backs that number
 * and the source behind it. The years figure beside it is the same number,
 * just re-expressed at a more readable scale.
 */
@Composable
private fun StatusPanel(state: TrackerUiState) {
    val leader = state.leader
    val threshold = state.snapshot?.thresholdUsd ?: 1_000_000_000_000.0
    val projected = leader?.let { state.projected(it) } ?: 0.0
    val over = projected >= threshold
    val total = state.totalProjectedWealth

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
                text = "TRILLIONAIRE RIGHT NOW?",
                color = TT.inkMuted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.sp,
                modifier = Modifier.weight(1f)
            )
            if (over) {
                StatusChip(glyph = "●", label = "OVER THE LINE", color = TT.good)
            } else if (leader != null && state.isLive(leader.id)) {
                StatusChip(glyph = "●", label = "LIVE", color = TT.good)
            }
        }

        Spacer(Modifier.height(4.dp))

        Text(
            text = if (over) "YES" else "NO",
            color = if (over) TT.good else TT.inkPrimary,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold
        )

        if (leader != null) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = leader.name,
                    color = TT.inkSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f)
                )
                RollingNumber(
                    text = Format.exactUsd(projected),
                    style = androidx.compose.ui.text.TextStyle(
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace
                    ),
                    color = if (over) TT.good else TT.inkPrimary
                )
            }

            Spacer(Modifier.height(10.dp))

            ThresholdGauge(
                progress = NetWorthEngine.progressToThreshold(projected, threshold),
                leftLabel = "${Format.percentOf(projected, threshold)} of \$1T",
                rightLabel = if (over) "over" else
                    "${Format.compactUsd(NetWorthEngine.shortfall(projected, threshold))} to go",
                barColor = if (over) TT.good else TT.series
            )
        }

        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(TT.border))
        Spacer(Modifier.height(12.dp))

        Text(
            text = "TOTAL WEALTH TRACKED",
            color = TT.inkMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.sp
        )
        Spacer(Modifier.height(4.dp))
        RollingNumber(
            text = Format.exactUsd(total),
            style = androidx.compose.ui.text.TextStyle(
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace
            ),
            color = TT.inkPrimary
        )

        Spacer(Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "POVERTY-FREE WORLD FOR",
                    color = TT.inkMuted,
                    fontSize = 9.sp,
                    letterSpacing = 1.sp
                )
                RollingNumber(
                    text = "${Format.wholeCount(state.daysOfPovertyFreeWorld)} days",
                    style = androidx.compose.ui.text.TextStyle(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace
                    ),
                    color = TT.warning
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "≈ IN YEARS",
                    color = TT.inkMuted,
                    fontSize = 9.sp,
                    letterSpacing = 1.sp
                )
                Text(
                    text = String.format(java.util.Locale.UK, "%.1f yrs", state.daysOfPovertyFreeWorld / 365.25),
                    color = TT.inkSecondary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

/**
 * Whoever moved the most today, either direction — null when there's
 * nobody to point to yet (empty list) or nothing's moved at all.
 */
private fun biggestMover(state: TrackerUiState): Person? =
    state.people
        .filter { it.dayChangeUsd != 0.0 }
        .maxByOrNull { kotlin.math.abs(it.dayChangeUsd) }

@Composable
private fun BiggestMoverCard(person: Person) {
    val up = person.dayChangeUsd >= 0.0

    Row(
        Modifier
            .fillMaxWidth()
            .background(TT.surface, TT.panelShape(12.dp))
            .border(1.dp, TT.border, TT.panelShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "BIGGEST MOVER TODAY",
                color = TT.inkMuted,
                fontSize = 10.sp,
                letterSpacing = 1.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = person.name,
                color = TT.inkPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
        }
        DeltaChip(
            deltaUsd = person.dayChangeUsd,
            formatted = Format.signedCompactUsd(person.dayChangeUsd) + " today"
        )
    }
}

/** One entry in the crossing-history list — see [Crossing] for what "ongoing" means. */
@Composable
private fun CrossingRow(crossing: Crossing) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(TT.surface, TT.panelShape(12.dp))
            .border(1.dp, TT.border, TT.panelShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = crossing.personName,
                color = TT.inkPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = Format.dateOnly(crossing.crossedAtEpoch) +
                    " · held for " + Format.duration(crossing.heldForSeconds),
                color = TT.inkMuted,
                fontSize = 11.sp
            )
        }
        if (crossing.ongoing) {
            StatusChip(glyph = "●", label = "ONGOING", color = TT.good)
        }
    }
}

@Composable
private fun PersonRow(
    rank: Int,
    person: Person,
    projected: Double,
    isLive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(TT.surface, TT.panelShape(12.dp))
            .border(1.dp, TT.border, TT.panelShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = rank.toString().padStart(2, ' '),
            color = TT.inkMuted,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace
        )

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = person.name,
                    color = TT.inkPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (person.flight?.state == FlightState.AIRBORNE) {
                    Spacer(Modifier.width(6.dp))
                    Text(text = "✈", color = TT.warning, fontSize = 12.sp)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = person.company,
                    color = TT.inkMuted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (isLive) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "● live",
                        color = TT.good,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        Sparkline(
            values = person.history,
            color = TT.series,
            modifier = Modifier
                .width(56.dp)
                .height(28.dp)
        )

        Spacer(Modifier.width(12.dp))

        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = Format.compactUsd(projected),
                color = TT.inkPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.height(4.dp))
            DeltaChip(
                deltaUsd = person.dayChangeUsd,
                formatted = Format.signedCompactUsd(person.dayChangeUsd)
            )
        }
    }
}

