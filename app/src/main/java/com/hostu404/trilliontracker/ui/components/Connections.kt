package com.hostu404.trilliontracker.ui.components

import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hostu404.trilliontracker.data.Connection
import com.hostu404.trilliontracker.data.Person
import com.hostu404.trilliontracker.ui.theme.TT
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val CIRCLE = 240.dp
private val PHOTO = 28.dp
/** The outer ring the names run round in. */
private val BAND = 13.dp
/** How long one slow turn of the circle takes. */
private const val TURN_SECONDS = 120f

/**
 * What the tracked people share in their backgrounds, as a magic circle of
 * faces joined by lines; a thicker line means more in common. Tap a face to light
 * up its lines and list what it shares; tap a row for each person's sourced
 * fact. [people] is the ring's order.
 */
@Composable
fun ConnectionsSection(people: List<Person>, links: List<Connection>) {
    val ids = people.map { it.id }.toSet()
    val shown = remember(links, ids) {
        links.map { link -> link.copy(facts = link.facts.filter { it.person in ids }) }
            .filter { it.facts.size >= 2 }
    }
    if (shown.isEmpty()) return

    // How many things each pair shares, keyed "a|b" with a < b.
    val shared = remember(shown) {
        buildMap<String, Int> {
            for (link in shown) for (a in link.people) for (b in link.people) {
                if (a < b) put("$a|$b", (get("$a|$b") ?: 0) + 1)
            }
        }
    }
    fun sharedBy(a: String, b: String) = shared[if (a < b) "$a|$b" else "$b|$a"] ?: 0
    val surname = people.associate { it.id to it.name.substringAfterLast(' ') }

    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var open by rememberSaveable { mutableStateOf<String?>(null) }

    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Ring(people, selected, ::sharedBy) { id ->
            selected = if (selected == id) null else id
            open = null
        }

        selected?.let { focus ->
            shown.filter { focus in it.people }.forEach { link ->
                LinkRow(
                    link = link,
                    others = link.people.filter { it != focus }.mapNotNull { surname[it] },
                    surname = surname,
                    expanded = open == link.title,
                    onClick = { open = if (open == link.title) null else link.title }
                )
            }
        }
    }
}

/**
 * The faces as a magic circle: the names running round the outside of a
 * ring, the faces in ringed medallions, and the lines between them
 * drawing the star. It turns slowly whenever no face is selected.
 */
@Composable
private fun Ring(
    people: List<Person>,
    selected: String?,
    sharedBy: (String, String) -> Int,
    onTap: (String) -> Unit
) {
    val density = LocalDensity.current
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { letterSpacing = 0.15f } }
    fun faded(id: String) = selected != null && id != selected && sharedBy(selected, id) == 0

    val turn = remember { mutableFloatStateOf(0f) }
    if (selected == null) LaunchedEffect(Unit) {
        var last = withInfiniteAnimationFrameNanos { it }
        while (true) withInfiniteAnimationFrameNanos { now ->
            turn.floatValue += (2 * PI).toFloat() * (now - last) / 1e9f / TURN_SECONDS
            last = now
        }
    }

    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, CIRCLE)
        val r = with(density) { side.toPx() } / 2f
        val band = with(density) { BAND.toPx() }
        val medallion = with(density) { (PHOTO / 2 + 5.dp).toPx() }
        // The faces sit on a ring just inside the band of names.
        val rFaces = r - band - with(density) { 3.dp.toPx() } - medallion
        // Only read while drawing and placing, so turning never recomposes.
        fun angle(i: Int) = -PI / 2 + turn.floatValue + 2 * PI * i / people.size
        fun centre(i: Int) = angle(i).let { a -> Offset(r + rFaces * cos(a).toFloat(), r + rFaces * sin(a).toFloat()) }

        Box(Modifier.size(side)) {
            Canvas(Modifier.matchParentSize()) {
                val centres = people.indices.map { centre(it) }
                val hair = 0.75.dp.toPx()
                drawCircle(TT.inkMuted, r - band, style = Stroke(hair))
                drawCircle(TT.grid, rFaces, style = Stroke(hair))

                for (i in people.indices) for (j in i + 1 until people.size) {
                    val n = sharedBy(people[i].id, people[j].id)
                    if (n == 0) continue
                    val lit = selected != null && (people[i].id == selected || people[j].id == selected)
                    drawLine(
                        color = when {
                            selected == null -> TT.inkMuted
                            lit -> TT.inkPrimary
                            else -> TT.grid
                        },
                        start = centres[i],
                        end = centres[j],
                        strokeWidth = (if (lit) 1f + 0.75f * n else 0.5f + 0.5f * n).dp.toPx()
                    )
                }

                people.forEachIndexed { i, person ->
                    val isSelected = person.id == selected
                    val ink = when {
                        isSelected -> TT.inkPrimary
                        faded(person.id) -> TT.grid
                        else -> TT.inkMuted
                    }
                    // A ringed medallion, black inside so the lines stop at its edge.
                    drawCircle(TT.surface, medallion, centres[i])
                    drawCircle(ink, medallion, centres[i], style = Stroke(hair))
                    drawCircle(ink, medallion - 2.5.dp.toPx(), centres[i], style = Stroke(hair))

                    paint.textSize = 8.sp.toPx()
                    paint.typeface = if (isSelected) BOLD_MONO else Typeface.MONOSPACE
                    paint.color = (if (ink == TT.inkMuted) TT.inkSecondary else ink).toArgb()
                    ringText(person.name.substringAfterLast(' ').uppercase(), angle(i), r - band, r, paint)

                    paint.typeface = Typeface.MONOSPACE
                    paint.color = TT.inkMuted.toArgb()
                    ringText("✦", angle(i) + PI / people.size, r - band, r, paint)
                }
            }

            people.forEachIndexed { i, person ->
                val half = with(density) { (PHOTO / 2).toPx() }
                PersonPhoto(
                    name = person.name,
                    photoUrl = person.photoUrl,
                    size = PHOTO,
                    shape = CircleShape,
                    modifier = Modifier
                        .offset { centre(i).let { c -> IntOffset((c.x - half).roundToInt(), (c.y - half).roundToInt()) } }
                        .alpha(if (faded(person.id)) 0.3f else 1f)
                        .clip(CircleShape)
                        .clickable(onClickLabel = "Show what ${person.name} shares") { onTap(person.id) }
                )
            }
        }
    }
}

private val BOLD_MONO = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

/**
 * [text] centred at [angle] in the band between [rIn] and [rOut]. The top
 * half reads clockwise and the bottom half anticlockwise, so it's never
 * upside down.
 */
private fun DrawScope.ringText(text: String, angle: Double, rIn: Float, rOut: Float, paint: Paint) {
    val top = sin(angle) <= 0.0
    val capHalf = paint.textSize * 0.35f
    val radius = (rIn + rOut) / 2f + if (top) -capHalf else capHalf
    val degrees = Math.toDegrees(angle).toFloat()
    val path = Path().apply {
        addArc(
            RectF(center.x - radius, center.y - radius, center.x + radius, center.y + radius),
            if (top) degrees - 90f else degrees + 90f,
            if (top) 180f else -180f
        )
    }
    val offset = (PI.toFloat() * radius - paint.measureText(text)) / 2f
    drawIntoCanvas { it.nativeCanvas.drawTextOnPath(text, path, offset, 0f, paint) }
}

/** One shared thing: its name and who else shares it; tap for each fact and its source. */
@Composable
private fun LinkRow(
    link: Connection,
    others: List<String>,
    surname: Map<String, String>,
    expanded: Boolean,
    onClick: () -> Unit
) {
    val uriHandler = LocalUriHandler.current
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = link.title + (if (expanded) "" else " →"),
            color = TT.inkPrimary,
            fontSize = 12.sp
        )
        Text(text = "with " + others.joinToString(", "), color = TT.inkMuted, fontSize = 11.sp)
        if (expanded) {
            link.facts.forEach { fact ->
                Text(
                    text = "${surname[fact.person]?.uppercase()} · ${fact.text}",
                    color = TT.inkSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Text(
                    text = "${fact.sourceLabel} ↗",
                    color = TT.accent,
                    fontSize = 10.sp,
                    modifier = Modifier.clickable { uriHandler.openUri(fact.sourceUrl) }
                )
            }
            link.note?.let { note ->
                Text(text = note.text, color = TT.inkSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                Text(
                    text = "${note.sourceLabel} ↗",
                    color = TT.accent,
                    fontSize = 10.sp,
                    modifier = Modifier.clickable { uriHandler.openUri(note.sourceUrl) }
                )
            }
        }
    }
}
