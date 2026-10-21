package com.gildongmu.app

import android.content.res.Configuration
import android.graphics.Color

/**
 * Colours for the day and night versions of the screen.
 * Brand colours: Hyundai Blue (deep navy) as the base, Active Blue as the single accent.
 * Status colours keep one meaning everywhere: green = parked, amber = waiting in traffic, red = moving / alert.
 * The night version is the showpiece: deep navy glass cards on a soft gradient, easy on the eyes after dark.
 */
class Theme(val night: Boolean) {
    private fun c(day: String, nightHex: String) = Color.parseColor(if (night) nightHex else day)

    val bgTop = c("#F4F6F9", "#0A1426")
    val bgBottom = c("#E9EDF3", "#0D1B33")
    val surface = c("#FFFFFF", "#13233D")
    val surfaceAlt = c("#EDF0F5", "#1A2E4D")
    val border = c("#E1E5EB", "#223656")
    val ink = c("#0A1A33", "#F2F6FC")
    val muted = c("#5B6B82", "#97A8C2")
    val faint = c("#98A2B3", "#5C6E8C")

    val primary = c("#002C5F", "#8CC8FF")       // Hyundai Blue (on dark: a light tint so it stays readable)
    val accent = c("#00AAD2", "#3BB8F0")        // Active Blue
    val accentSoft = c("#E0F4FA", "#10304F")
    val navyFill = Color.parseColor("#002C5F")  // avatar / strong fills in both themes
    val strong = c("#002C5F", "#1E6FD9")        // filled buttons (selected chip, mic)

    val parked = Color.parseColor("#12B76A")
    val waiting = Color.parseColor("#F5A524")
    val moving = Color.parseColor("#F04438")
    val heartSoft = c("#FFEFF3", "#3A1624")
    val heart = c("#E5446D", "#FF7A9C")

    // road
    val roadTop = c("#2B3542", "#0B1424")
    val roadBottom = c("#1F2833", "#16253F")
    val roadEdge = c("#E6EAF0", "#6F86AA")
    val roadDash = c("#8A94A6", "#41577A")
    val carMe = c("#FFFFFF", "#F2F6FC")
    val carSame = c("#1CB4E8", "#2BA8F2")
    val carOther = c("#C3CBD6", "#6C7F9E")
    val glow = c("#3300AAD2", "#553BB8F0")

    companion object {
        fun of(config: Configuration) =
            Theme((config.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)
    }
}
