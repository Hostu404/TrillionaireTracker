package com.hostu404.trilliontracker.ui

import com.hostu404.trilliontracker.data.AirportInfo
import com.hostu404.trilliontracker.data.PortInfo
import java.util.Locale

/**
 * Place names in full. The backend labels places "Temple, TX" or
 * "Devonport, NZ"; the app spells out the state or country instead
 * ("Temple, Texas", "Devonport, New Zealand") and drops it when it would
 * just repeat the place ("Monaco", not "Monaco, Monaco").
 */
object PlaceNames {

    fun of(airport: AirportInfo): String = expand(airport.label, airport.country)

    fun of(port: PortInfo): String = expand(port.label, port.country)

    /** [label]'s trailing two-letter code, spelled out; [label] as-is when there's nothing to expand. */
    fun expand(label: String, country: String): String {
        val cut = label.lastIndexOf(", ")
        if (cut < 0) return label
        val place = label.substring(0, cut)
        val code = label.substring(cut + 2)
        if (code.length != 2 || !code.all { it in 'A'..'Z' }) return label
        // A US airport's label carries its state; anything else carries a country.
        val full = (if (country == "US") US_STATES[code] else null) ?: countryName(code) ?: return label
        return if (full.equals(place, ignoreCase = true)) place else "$place, $full"
    }

    private fun countryName(code: String): String? = try {
        Locale.Builder().setRegion(code).build()
            .getDisplayCountry(Locale.ENGLISH)
            .takeIf { it.isNotBlank() && it != code }
    } catch (_: Exception) {
        null
    }

    private val US_STATES = mapOf(
        "AL" to "Alabama", "AK" to "Alaska", "AZ" to "Arizona", "AR" to "Arkansas",
        "CA" to "California", "CO" to "Colorado", "CT" to "Connecticut", "DE" to "Delaware",
        "DC" to "Washington, D.C.", "FL" to "Florida", "GA" to "Georgia", "HI" to "Hawaii",
        "ID" to "Idaho", "IL" to "Illinois", "IN" to "Indiana", "IA" to "Iowa",
        "KS" to "Kansas", "KY" to "Kentucky", "LA" to "Louisiana", "ME" to "Maine",
        "MD" to "Maryland", "MA" to "Massachusetts", "MI" to "Michigan", "MN" to "Minnesota",
        "MS" to "Mississippi", "MO" to "Missouri", "MT" to "Montana", "NE" to "Nebraska",
        "NV" to "Nevada", "NH" to "New Hampshire", "NJ" to "New Jersey", "NM" to "New Mexico",
        "NY" to "New York", "NC" to "North Carolina", "ND" to "North Dakota", "OH" to "Ohio",
        "OK" to "Oklahoma", "OR" to "Oregon", "PA" to "Pennsylvania", "RI" to "Rhode Island",
        "SC" to "South Carolina", "SD" to "South Dakota", "TN" to "Tennessee", "TX" to "Texas",
        "UT" to "Utah", "VT" to "Vermont", "VA" to "Virginia", "WA" to "Washington",
        "WV" to "West Virginia", "WI" to "Wisconsin", "WY" to "Wyoming",
        "PR" to "Puerto Rico", "GU" to "Guam", "VI" to "U.S. Virgin Islands",
        "AS" to "American Samoa", "MP" to "Northern Mariana Islands"
    )
}
