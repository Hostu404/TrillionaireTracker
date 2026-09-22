package com.hostu404.trilliontracker.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow

/**
 * Per-character odometer — but only for the characters that can actually
 * change. Only [Char.isDigit] characters get the animated
 * [AnimatedContent] treatment; punctuation, currency symbols, and any
 * literal letters riding along in [text] (a "$", a comma, a " people a
 * day" suffix) render as plain, unanimated [androidx.compose.material3.Text]
 * instead. Each [AnimatedContent] instance carries its own
 * [androidx.compose.animation.core.Transition] and gets re-evaluated on
 * every recomposition regardless of whether it actually animates that
 * frame — wrapping every character in one, including ones that never
 * change, was measurable overhead on a screen carrying several of these at
 * once (the hero number, the total, the per-day comparison line), all
 * re-evaluating on every tick. Restricting the wrapper to digits keeps the
 * odometer effect exactly where it reads as intentional — the number
 * itself — and removes it everywhere else.
 *
 * Tabular figures are not decoration here — without a fixed advance width the
 * whole row would jitter sideways every time a digit changes.
 */
@Composable
fun RollingNumber(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier
) {
    val monoStyle = style.copy(fontFamily = FontFamily.Monospace)

    Row(modifier = modifier) {
        text.forEach { ch ->
            if (ch.isDigit()) {
                AnimatedContent(
                    targetState = ch,
                    transitionSpec = {
                        val goingUp = targetState > initialState
                        val enter = slideInVertically(tween(220)) { h ->
                            if (goingUp) h else -h
                        } + fadeIn(tween(160))
                        val exit = slideOutVertically(tween(220)) { h ->
                            if (goingUp) -h else h
                        } + fadeOut(tween(160))
                        enter togetherWith exit
                    },
                    label = "rolling-char"
                ) { target ->
                    androidx.compose.material3.Text(
                        text = target.toString(),
                        style = monoStyle,
                        color = color,
                        maxLines = 1,
                        overflow = TextOverflow.Clip
                    )
                }
            } else {
                androidx.compose.material3.Text(
                    text = ch.toString(),
                    style = monoStyle,
                    color = color,
                    maxLines = 1,
                    overflow = TextOverflow.Clip
                )
            }
        }
    }
}
