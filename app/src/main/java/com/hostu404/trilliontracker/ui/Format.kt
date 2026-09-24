package com.hostu404.trilliontracker.ui

import java.text.NumberFormat
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

object Format {

    private val grouped: NumberFormat = NumberFormat.getIntegerInstance(Locale.UK)

    private const val DOLLAR = "$"

    /** "$892,431,002,847" — the hero figure, every digit visible. */
    fun exactUsd(value: Double): String = DOLLAR + grouped.format(value.roundToLong())

    /** "$892.4B" / "$1.02T" — for dense rows. */
    fun compactUsd(value: Double): String {
        val v = abs(value)
        val sign = if (value < 0) "-" else ""
        return when {
            v >= 1_000_000_000_000.0 ->
                sign + DOLLAR + String.format(Locale.UK, "%.3fT", v / 1_000_000_000_000.0)
            v >= 1_000_000_000.0 ->
                sign + DOLLAR + String.format(Locale.UK, "%.1fB", v / 1_000_000_000.0)
            v >= 1_000_000.0 ->
                sign + DOLLAR + String.format(Locale.UK, "%.1fM", v / 1_000_000.0)
            else -> sign + DOLLAR + grouped.format(v.roundToLong())
        }
    }

    /** "3,237" — a plain grouped whole number, no scale suffix (for counts that read best unscaled, like a day count). */
    fun wholeCount(value: Double): String = grouped.format(value.roundToLong())

    /** "4,213 nm" — a great-circle distance total, grouped for readability. */
    fun nauticalMiles(value: Double): String = grouped.format(value.roundToLong()) + " nm"

    /** "241.3 billion" / "892.4 million" — a plain (non-dollar) compact count. */
    fun compactCount(value: Double): String {
        val v = abs(value)
        return when {
            v >= 1_000_000_000_000.0 -> String.format(Locale.UK, "%.2f trillion", v / 1_000_000_000_000.0)
            v >= 1_000_000_000.0 -> String.format(Locale.UK, "%.1f billion", v / 1_000_000_000.0)
            v >= 1_000_000.0 -> String.format(Locale.UK, "%.1f million", v / 1_000_000.0)
            v >= 1_000.0 -> String.format(Locale.UK, "%.1f thousand", v / 1_000.0)
            else -> grouped.format(v.roundToLong())
        }
    }

    /** Signed, always paired with a glyph at the call site. */
    fun signedCompactUsd(value: Double): String {
        val body = compactUsd(abs(value))
        return if (value < 0) "-$body" else "+$body"
    }

    fun percentOf(value: Double, target: Double): String =
        String.format(Locale.UK, "%.2f%%", (value / target) * 100.0)

    /** For an already-computed percentage figure (e.g. a true-tax-rate value of 3.27) — "3.3%". */
    fun percentOneDecimal(pct: Double): String =
        String.format(Locale.UK, "%.1f%%", pct)

    /** "2h 14m" */
    fun elapsedSince(epochSeconds: Long, nowSeconds: Long): String {
        val d = Duration.ofSeconds((nowSeconds - epochSeconds).coerceAtLeast(0))
        val h = d.toHours()
        val m = d.toMinutes() % 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m"
            else -> "${d.seconds}s"
        }
    }

    fun agoShort(epochSeconds: Long, nowSeconds: Long): String =
        elapsedSince(epochSeconds, nowSeconds) + " ago"

    fun dateOnly(epochSeconds: Long): String {
        val i = Instant.ofEpochSecond(epochSeconds)
        return i.toString().substring(0, 10)
    }

    /** "3d 4h" / "6h 12m" / "42m" — a span, not a point in time. */
    fun duration(seconds: Long): String {
        val d = Duration.ofSeconds(seconds.coerceAtLeast(0))
        val days = d.toDays()
        val hours = d.toHours() % 24
        val minutes = d.toMinutes() % 60
        return when {
            days > 0 -> "${days}d ${hours}h"
            hours > 0 -> "${hours}h ${minutes}m"
            else -> "${minutes}m"
        }
    }

    fun percent(fraction: Double): String =
        String.format(Locale.UK, "%.0f%%", fraction * 100.0)

    /**
     * "18d" / "7mo" / "3y 4mo" / "40+ years" — coarse on purpose, for a
     * long-range "at this rate" projection where the input is already just
     * a straight-line guess. No point pretending to hours/minutes precision
     * on a number that's honestly good to the nearest month at best.
     */
    fun roughDuration(seconds: Long): String {
        if (seconds < 86_400L) return "less than a day"
        val days = seconds / 86_400L
        return when {
            days < 60L -> "${days}d"
            days < 365L * 2 -> "${days / 30}mo"
            days < 365L * 40 -> {
                val years = days / 365
                val months = (days % 365) / 30
                if (months > 0) "${years}y ${months}mo" else "${years}y"
            }
            else -> "40+ years"
        }
    }

    /**
     * "39.5000°N, 2.9000°E" — plain decimal-degree text for the one place
     * this app ever surfaces a raw coordinate: FlightStatus/VesselStatus's
     * currentLat/currentLon fallback (a real fix that matched no known
     * airport/port), always shown paired with generalLocation's coarse
     * place name, never on its own. 4 decimals (~11m) matches the actual
     * resolution ADS-B/AIS fixes are good to — more digits would just be
     * floating-point noise dressed up as precision, not real accuracy.
     */
    fun coordinate(lat: Double, lon: Double): String {
        val latHem = if (lat < 0) "S" else "N"
        val lonHem = if (lon < 0) "W" else "E"
        return String.format(Locale.UK, "%.4f°%s, %.4f°%s", abs(lat), latHem, abs(lon), lonHem)
    }

    private val ISO_DATE: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    private val LONG_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.UK)

    /**
     * Computed fresh every time this is called, from the device's current
     * date — not cached anywhere in the snapshot. That's the whole point:
     * a birth date is static, but the age shown next to it should tick over
     * on its own the next time someone opens the app after a birthday, with
     * no new snapshot required.
     */
    fun ageFrom(birthDateIso: String, today: LocalDate = LocalDate.now()): Int? =
        parseIsoDate(birthDateIso)?.let { birth -> Period.between(birth, today).years }

    /** "June 28, 1971" from a stored "1971-06-28". */
    fun birthDateLabel(birthDateIso: String): String? =
        parseIsoDate(birthDateIso)?.format(LONG_DATE)

    private fun parseIsoDate(value: String): LocalDate? =
        try {
            LocalDate.parse(value, ISO_DATE)
        } catch (e: DateTimeParseException) {
            null
        }

}
