package com.drivepet.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.IBinder

/**
 * Keeps the pet alive in the background: it owns [PetHub] (engine + Vehicle/Navi SDK + voice) and the floating [PetOverlay].
 * Started by the activity. Runs as a foreground service with a small notification, as Android requires.
 *
 * Opens the pet's screen by itself when the gear is put in P. In a debug build it also listens for demo commands (notification buttons, or
 * `adb shell am broadcast -a com.drivepet.DEMO --es cmd drive -p com.drivepet.app`), because while the car is "driving"
 * the pet's own screen is closed and the demo buttons in it cannot be reached. The release build has none of this.
 */
class PetService : Service() {
    private var overlay: PetOverlay? = null
    private val debuggable get() = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    // the small pet shows no text and no effects: it only follows the pet's look and pose
    private val onReaction: (Reaction) -> Unit = { overlay?.refresh() }
    private val onTick: () -> Unit = { overlay?.refresh() }
    private val onFind: (Find) -> Unit = { overlay?.playFetch() }
    private val onVisible: (Boolean) -> Unit = { visible -> if (visible) overlay?.hide() else overlay?.show() }

    /** the gear has just been put in P: bring the pet's screen back */
    private val onAutoOpen: () -> Unit = {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    private val demo = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.getStringExtra("cmd")) {
                "drive" -> PetHub.demoDrive()
                "stop" -> PetHub.demoStop()
                "turn_ok" -> PetHub.demoTurn(withSignal = true, harsh = false)
                "turn_bad" -> PetHub.demoTurn(withSignal = false, harsh = false)
                "harsh" -> PetHub.demoTurn(withSignal = true, harsh = true)
                "brake_bad" -> PetHub.demoBrake(jerky = true)
                "brake_ok" -> PetHub.demoBrake(jerky = false)
                "km" -> PetHub.demoTotalKm(i.getStringExtra("ids")?.toFloatOrNull() ?: 0f)
                "buyall" -> PetHub.demoBuyAll()
                "mission_part" -> PetHub.demoMission(false)
                "mission_done" -> PetHub.demoMission(true)
                "hungry_on" -> PetHub.demoHungry(true)
                "hungry_off" -> PetHub.demoHungry(null)
                "mood" -> PetHub.demoMood(i.getStringExtra("ids")?.toIntOrNull() ?: 70)
                "outfit" -> PetHub.demoOutfit(i.getStringExtra("ids").orEmpty().split(",").filter { it.isNotBlank() })
                "route" -> PetHub.startDemoRoute()
                "follow_on" -> PetHub.setFollowCar(true)
                "follow_off" -> PetHub.setFollowCar(false)
                "points" -> PetHub.demoGivePoints(200)
                "geo" -> i.getStringExtra("ids").orEmpty().split(",").mapNotNull { it.trim().toDoubleOrNull() }.let { if (it.size == 2) PetHub.demoGeo(it[0], it[1]) }
                "region" -> PetHub.demoRegion(i.getStringExtra("ids")?.takeIf { it.isNotBlank() })
                "find" -> PetHub.demoFind()
                "dex_fill" -> PetHub.demoDexFill(i.getStringExtra("ids").orEmpty().split(",").filter { it.isNotBlank() })
                "dex_clear" -> PetHub.demoDexClear()
                "art_sheet" -> ItemArt.sheet(java.io.File(filesDir, "items.png"))
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        PetHub.start(this)
        overlay = PetOverlay(this)
        PetHub.reactionListeners += onReaction
        PetHub.tickListeners += onTick
        PetHub.visibilityListeners += onVisible
        PetHub.autoOpenListeners += onAutoOpen
        PetHub.findListeners += onFind
        if (debuggable) {
            registerReceiver(demo, IntentFilter(ACTION_DEMO), RECEIVER_EXPORTED)
        }
        if (!PetHub.appVisible) overlay?.show()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // when the permission was just granted while the pet's screen was in the background
        if (!PetHub.appVisible) overlay?.show()
        // the location permission may have just been granted: take the location service type and start the position updates
        if (PetHub.locationAllowed || checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) startAsForeground()
        PetHub.startLocation()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (debuggable) try { unregisterReceiver(demo) } catch (_: Exception) {}
        PetHub.reactionListeners -= onReaction
        PetHub.tickListeners -= onTick
        PetHub.visibilityListeners -= onVisible
        PetHub.autoOpenListeners -= onAutoOpen
        PetHub.findListeners -= onFind
        overlay?.hide()
        PetHub.stop()
        super.onDestroy()
    }

    private fun action(title: String, cmd: String, code: Int): Notification.Action {
        val pi = PendingIntent.getBroadcast(
            this, code, Intent(ACTION_DEMO).setPackage(packageName).putExtra("cmd", cmd),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_media_play), title, pi).build()
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("pet", "운전펫", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(this, "pet")
            .setSmallIcon(R.drawable.ic_stat_pet).setColor(Look.HONEY)
            .setContentTitle("운전펫이 함께 달리는 중")
            .setContentText("눌러서 펫 화면 열기")
            .setContentIntent(open)
            .setOngoing(true)
        if (debuggable) b.addAction(action("데모 주행", "drive", 1)).addAction(action("데모 정차", "stop", 2)).addAction(action("데모 배고픔", "hungry_on", 3))
        val located = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        try {
            startForeground(1, b.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or (if (located) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0))
        } catch (e: Exception) {
            // the location type is refused when the service is started from the background: run without it (no region, no finds)
            startForeground(1, b.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        }
    }

    companion object { const val ACTION_DEMO = "com.drivepet.DEMO" }
}
