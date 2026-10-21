package com.gildongmu.app

import android.content.res.Configuration
import android.graphics.Color

/**
 * Colours for the day and night versions of the screen.
 * Brand colours: Hyundai Blue (deep navy) as the base, Active Blue as the single accent.
 * Status colours keep one meaning everywhere: green = parked, amber = waiting in traffic, red = moving / alert.
 */
class Theme(val night: Boolean) {
    private fun c(day: String, nightHex: String) = Color.parseColor(if (night) nightHex else day)

    val bg = c("#F1F3F6", "#0A1220")
    val surface = c("#FFFFFF", "#141E31")
    val surfaceAlt = c("#E8EBF0", "#1D293F")
    val ink = c("#0A1A33", "#F2F5FA")
    val muted = c("#5B6B82", "#93A1B8")
    val faint = c("#98A2B3", "#5D6B82")
    val line = c("#E1E5EB", "#26344D")

    val primary = c("#002C5F", "#8CC8FF")       // Hyundai Blue (on dark: a light tint so it stays readable)
    val onPrimary = Color.WHITE
    val accent = c("#00AAD2", "#35C2E8")        // Active Blue
    val accentSoft = c("#E0F4FA", "#0F2F42")
    val navyFill = Color.parseColor("#002C5F")  // avatar / strong fills in both themes

    val parked = Color.parseColor("#12B76A")
    val waiting = Color.parseColor("#F5A524")
    val moving = Color.parseColor("#F04438")
    val warnSoft = c("#FFF3D6", "#3A2D0C")
    val warnInk = c("#8A4B05", "#FFD27A")
    val alertSoft = c("#FDECEA", "#3C1714")
    val heartSoft = c("#FFEFF3", "#3A1624")
    val heart = c("#E5446D", "#FF7A9C")

    // road
    val roadBg = c("#2A3441", "#0E1726")
    val roadEdge = c("#E6EAF0", "#7C8BA3")
    val roadDash = c("#8A94A6", "#4A5A75")
    val carMe = c("#FFFFFF", "#F2F5FA")
    val carSame = c("#00AAD2", "#35C2E8")
    val carOther = c("#C3CBD6", "#52627C")

    companion object {
        fun of(config: Configuration) =
            Theme((config.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)
    }
}
