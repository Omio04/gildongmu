package com.gildongmu.app

import android.content.res.Configuration
import android.graphics.Color

/**
 * Two real themes, not one theme with the lights turned down.
 *
 *  - Day ("Daylight"): a light, quiet workspace. White cards on cool grey, Hyundai navy for text and the main
 *    action, a daylight asphalt road. Everything is high-contrast for sun glare.
 *  - Night ("Midnight"): near-black, with surfaces that rise by lightness instead of by shadow. Text is soft
 *    white (never pure), the accent is a lit cyan, and the road is almost black so the cars carry the light.
 *
 * Status colours keep one meaning in both: red = alert, amber = caution, green = live / parked.
 * Brand: Hyundai Blue (#002C5F) as the base, Active Blue as the single accent.
 */
class Theme(val night: Boolean) {
    private fun c(day: String, nightHex: String) = Color.parseColor(if (night) nightHex else day)

    // surfaces
    val bgTop = c("#F4F6F9", "#06090F")
    val bgBottom = c("#EBEEF3", "#0A0F18")
    val surface = c("#FFFFFF", "#111722")
    val surfaceAlt = c("#EEF1F5", "#192130")
    val border = c("#DCE1E8", "#222C3C")

    // text
    val ink = c("#0B1B33", "#E6ECF5")
    val muted = c("#56667D", "#8696AD")
    val faint = c("#9AA5B5", "#516078")

    // brand
    val primary = c("#002C5F", "#A8D2FF")       // headings and chips (on dark: a light tint so it stays readable)
    val accent = c("#0092BE", "#35C4F3")        // Active Blue: the one accent
    val accentSoft = c("#E3F3F9", "#0F2A3D")
    val navyFill = c("#002C5F", "#14243F")      // the main action: navy by day, a lifted navy by night
    val strong = c("#002C5F", "#1D6FD9")

    // status
    val parked = c("#0E9F5C", "#2BD17E")
    val waiting = c("#E09200", "#F5B03A")
    val moving = c("#D92D20", "#FF5A4D")
    val heartSoft = c("#FFEFF3", "#3A1624")
    val heart = c("#E5446D", "#FF7A9C")

    // road: lighter asphalt with white lanes by day, almost black with dim lanes by night
    val roadTop = c("#737C8A", "#04070C")
    val roadBottom = c("#5E6674", "#0D141F")
    val roadEdge = c("#FFFFFF", "#5A6E8D")
    val roadDash = c("#E9EEF5", "#33445F")
    val carMe = c("#E3E8EF", "#DCE3EE")          // silver: our own car
    val carSame = c("#00A8E0", "#2DB4F5")
    val carOther = c("#262D38", "#1A212C")     // dark graphite, glossy: the quiet default
    val carLive = c("#1480C4", "#2796E0")      // a real person: a deep Active Blue, the brand colour, at all times
    val glow = c("#3300A8E0", "#6635C4F3")

    companion object {
        /** [pref]: "auto" follows the vehicle/system setting, "day" and "night" force one. */
        fun of(config: Configuration, pref: String = "auto") = Theme(
            when (pref) {
                "day" -> false
                "night" -> true
                else -> (config.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            }
        )
    }
}
