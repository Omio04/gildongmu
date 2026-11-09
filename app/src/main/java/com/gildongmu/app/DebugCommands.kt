package com.gildongmu.app

import android.content.Intent
import android.view.View
import android.view.ViewGroup

/** 디버그 빌드 전용: adb broadcast로 말·상태·위치를 손으로 넣기.
 * MainActivity의 확장 함수 모음(상태는 MainActivity에 그대로 있음). */

/**
 * Debug builds only: the same test options as at start, sent to the running app without restarting it (used to record
 * the demo video):  adb shell am broadcast -a com.gildongmu.app.DEBUG --es say "a,b" --es force_state moving|idling|parked
 * --ef fake_lat 37.9 --ef fake_lon 127.5
 */
internal fun MainActivity.debugCommand(intent: android.content.Intent) {
    android.util.Log.i(TAG, "debug command: force_state=${intent.getStringExtra("force_state")} say=${intent.getStringExtra("say")}")
    intent.getStringExtra("force_state")?.let { st ->
        val info2 = when (st) {
            "moving" -> DriveInfo(DriveState.MOVING, true, 0x50, 32f)
            "idling" -> DriveInfo(DriveState.IDLING, true, 0x50, 0f)
            "parked" -> DriveInfo(DriveState.PARKED, false, 0, 0f)
            else -> null
        }
        if (info2 != null) { forcedInfo = info2; onDriveInfo(info2) }
    }
    if (intent.hasExtra("probe_x")) {   // log which views lie under a screen point (to find what swallows a tap)
        val px = intent.getIntExtra("probe_x", 0); val py = intent.getIntExtra("probe_y", 0)
        fun walk(v: View, depth: Int) {
            val loc = IntArray(2); v.getLocationOnScreen(loc)
            if (px >= loc[0] && px < loc[0] + v.width && py >= loc[1] && py < loc[1] + v.height && v.visibility == View.VISIBLE) {
                android.util.Log.i(TAG, "probe ${"  ".repeat(depth)}${v.javaClass.simpleName} clickable=${v.isClickable} [${loc[0]},${loc[1]} ${v.width}x${v.height}]")
                if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1)
            }
        }
        walk(window.decorView, 0)
    }
    if (intent.hasExtra("fake_lat") && intent.hasExtra("fake_lon"))
        fakePlace = Place(intent.getFloatExtra("fake_lat", 0f).toDouble(), intent.getFloatExtra("fake_lon", 0f).toDouble(), 0.0, "시험 위치")
    intent.getStringExtra("say")?.split(",")?.forEachIndexed { i, phrase ->
        ui.postDelayed({ handleHeard(phrase.trim()) }, intent.getIntExtra("say_delay", 0) * 1_000L + i * 4_000L)
    }
}
