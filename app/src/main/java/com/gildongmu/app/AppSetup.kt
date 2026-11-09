package com.gildongmu.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/** 시작 설정: 서버 주소, 위치 공유·Pleos 위치 시작, 현재 위치.
 * MainActivity의 확장 함수 모음(상태는 MainActivity에 그대로 있음). */

/**
 * Where the server is. The emulator reaches the PC at 10.0.2.2; a real phone needs the PC's address:
 *   adb shell am start -n com.gildongmu.app/.MainActivity --es server http://192.168.0.10:8080
 * The address is remembered, so the app can be started normally afterwards.
 */
internal fun MainActivity.serverUrl(): String {
    val prefs = getSharedPreferences("gildongmu", Context.MODE_PRIVATE)
    (intent.getStringExtra("server") ?: intent.data?.getQueryParameter("url"))
        ?.let { prefs.edit().putString("server", it).apply() }
    return prefs.getString("server", null) ?: "http://10.0.2.2:8080"
}

/** Starts everything that talks to the server or reads the position. Only ever after the driver agreed. */
internal fun MainActivity.startSharing() {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 4)
    } else gps.start()
    startFused()   // the car's fused position, first choice (its own permission, asked after the agreement)
    pushProfile()   // the server learns the name and car other cars will see
    polling = true
    pollLoop()
}

internal fun MainActivity.startFused() {
    if (pleosFused != null) return
    if (ContextCompat.checkSelfPermission(this, FUSED_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
        ActivityCompat.requestPermissions(this, arrayOf(FUSED_PERMISSION), 5)
        return
    }
    pleosFused = PleosFusedClient(this) { android.util.Log.i(TAG, it) }.also { it.start() }
}

internal fun MainActivity.currentPlace(): Place? = fakePlace ?: pleosFused?.fresh() ?: navi?.fresh() ?: gps.fresh()
