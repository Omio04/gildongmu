package com.drivepet.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The one place that owns the pet while the app lives: the engine, the Pleos Vehicle and Navi connections, the voice
 * and the inventory. It is started by [PetService] (a foreground service), so it keeps running when the screen is not
 * on top (another app, the navigation). The activity and the floating overlay only watch it.
 * Everything runs on the main thread.
 *
 * The pet's own screen is only allowed while the gear is in P ([screenAllowed]); in any other gear it closes and only the
 * small pet stays over the other screens.
 */
object PetHub {
    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null

    lateinit var engine: PetEngine
        private set
    lateinit var wardrobe: Wardrobe
        private set
    lateinit var dex: Dex
        private set
    /** the driving record, one line per driven day (the calendar) */
    lateinit var driveLog: DriveLog
        private set
    /** the badges earned so far (they stay when the record is deleted) */
    lateinit var badges: BadgeBook
        private set
    /** goes up whenever the record or the badges change, so the screens know when to redraw them */
    var recordVersion = 0
        private set

    var vehicle: VehicleSource? = null
        private set
    var navi: NaviSource? = null
        private set
    var voice: PetVoice? = null
        private set

    var gear = "P"
        private set
    /** is the pet's own screen on top? the floating pet shows only when it is not */
    var appVisible = true
        private set

    /** the route in progress, as the car's navigation reports it (null when there is none) */
    var navTrip: NavTrip? = null
        private set
    var turn: Turn? = null
        private set

    /** true while the demo drives the pet at a constant speed (no real car signal) */
    var demoDriving = false
        private set

    // ---------------------------------------------------------------- who the pet is

    /** the name the driver gave the pet in the first-run introduction ("" until then) */
    var petName = ""
        private set
    /** has the first-run introduction (explanation + naming) been finished? */
    var onboarded = false
        private set

    fun completeOnboarding(name: String) {
        petName = name; onboarded = true
        app?.let { Store.saveProfile(it, petName, true) }
        tickListeners.forEach { it() }
    }

    /** demo only: show the first-run introduction again */
    fun demoResetOnboarding() {
        petName = ""; onboarded = false
        app?.let { Store.saveProfile(it, "", false) }
        tickListeners.forEach { it() }
    }

    // ---------------------------------------------------------------- energy: the pet is hungry when the car is low

    private var fuelPercent: Int? = null
    private var batteryPercent: Int? = null
    /** demo only: force hungry on or off regardless of the car (null = follow the car) */
    private var hungryOverride: Boolean? = null

    val hungry: Boolean get() = hungryOverride ?: PetState.energyLow(fuelPercent, batteryPercent)

    /** how the pet looks right now */
    val petState: PetState get() = if (app == null) PetState.NORMAL else engine.snapshot().state(hungry)

    fun demoHungry(on: Boolean?) { hungryOverride = on; tickListeners.forEach { it() } }

    // ---------------------------------------------------------------- settings

    /** with the navigation on and the car in gear, the small pet stands/walks next to the car on the map */
    var followCar = true
        private set

    fun setFollowCar(on: Boolean) { followCar = on; app?.let { Store.saveSettings(it, followCar) }; tickListeners.forEach { it() } }

    // ---------------------------------------------------------------- is the car stopped?

    private var brakeSince = -1L
    private var lastStopped = true

    /** the car is standing: in P or N, or in D/R with the brake held for a moment (a traffic light) */
    var stopped = true
        private set

    /** in a driving gear (D or R), standing or not */
    val inDrive get() = gear != "P" && gear != "N"

    /** in gear and not standing still: the small pet walks */
    val moving get() = inDrive && !stopped

    /** called when the gear has just been put in P and the screen is not showing: the service opens it */
    val autoOpenListeners = CopyOnWriteArrayList<() -> Unit>()

    /** may the pet's screen be on screen right now? Only while parked (it is closed in every other gear). */
    fun screenAllowed(): Boolean = gear == "P"

    private fun computeStopped(): Boolean {
        if (gear == "P" || gear == "N") return true
        return brakeSince >= 0 && SystemClock.elapsedRealtime() - brakeSince >= STOP_BRAKE_MS
    }

    /** one place decides when the screen opens: only at the moment the car is put in P */
    private fun evaluateStop() {
        stopped = computeStopped()
        if (stopped == lastStopped) return
        lastStopped = stopped
        if (stopped && gear == "P" && !appVisible) autoOpenListeners.forEach { it() }
    }

    /** the brake pedal, from the car or from the demo */
    fun onBrake(pressed: Boolean) {
        brakeSince = if (pressed) (if (brakeSince < 0) SystemClock.elapsedRealtime() else brakeSince) else -1L
        engine.onBrake(pressed)
        evaluateStop()
    }

    val reactionListeners = CopyOnWriteArrayList<(Reaction) -> Unit>()
    val tickListeners = CopyOnWriteArrayList<() -> Unit>()
    val visibilityListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    val started get() = app != null

    /** what the pet wears and which furniture stands in its room, for the drawing */
    val outfit: Map<Slot, String> get() = if (app == null) emptyMap() else wardrobe.equipped
    /** the furniture in the room (several at once) */
    val furniture: Set<String> get() = if (app == null) emptySet() else wardrobe.furniture

    private var lastOdometer: Float? = null

    fun start(ctx: Context) {
        if (app != null) return
        val c = ctx.applicationContext
        app = c
        engine = PetEngine(SystemClock::elapsedRealtime, Store.load(c), ::onReaction)
        wardrobe = Store.loadWardrobe(c)
        dex = Store.loadDex(c)
        driveLog = Store.loadDriveLog(c)
        badges = Store.loadBadges(c)
        followCar = Store.loadSettings(c)
        Store.loadProfile(c).let { petName = it.first; onboarded = it.second }
        voice = PetVoice(c)
        vehicle = VehicleSource(
            c,
            onGear = { setGear(it) },
            onSignal = engine::onTurnSignal,
            onBrake = ::onBrake,
            onAngle = engine::onSteeringAngle,
            onDistance = ::onOdometer,
            onFuel = { fuelPercent = it },
            onBattery = { batteryPercent = it },
            onLog = { },
        ).also { it.start() }
        navi = NaviSource(c, onTrip = { navTrip = it }, onTurn = { turn = it }, onLog = { }).also { it.start() }
        location = LocationSource(c, ::onLocation).also { it.start() }
        main.postDelayed(loop, 1000)
    }

    fun stop() {
        main.removeCallbacksAndMessages(null)
        save()
        vehicle?.stop(); navi?.stop(); voice?.shutdown(); location?.stop()
        vehicle = null; navi = null; voice = null; location = null; app = null
    }

    private fun save() {
        val c = app ?: return
        Store.save(c, engine.snapshot())
        Store.saveWardrobe(c, wardrobe)
        Store.saveDex(c, dex)
    }

    /** [demo] = the gear came from the demo, not from the car. */
    fun setGear(g: String, demo: Boolean = false) {
        if (g == "D" && gear != "D") tripFinds.clear()   // a new drive: the finds of the last one were already handed to the trip card
        gear = g
        demoDriving = demo && g == "D"
        engine.setDriving(g == "D")
        evaluateStop()
        tickListeners.forEach { it() }
    }

    /**
     * The odometer reading. The difference between two readings is the distance driven; a jump backwards or a
     * suspiciously large one (a different unit, a reset) is ignored instead of being counted.
     */
    private fun onOdometer(v: Float) {
        val prev = lastOdometer
        lastOdometer = v
        if (prev == null || demoDriving) return
        val delta = v - prev
        if (delta > 0f && delta < MAX_ODOMETER_STEP_KM) { engine.addDistance(delta); dexDrive(delta) }
    }

    fun setAppVisible(v: Boolean) {
        appVisible = v
        visibilityListeners.forEach { it(v) }
    }

    // ---------------------------------------------------------------- shop and inventory

    fun buy(id: String): Boolean {
        if (!wardrobe.buy(id, engine::spend, engine.level)) return false
        save(); tickListeners.forEach { it() }
        return true
    }

    fun equip(id: String) {
        // an egg and a baby cannot wear trousers
        val st = engine.snapshot().stage
        if (Catalog.get(id)?.slot == Slot.PANTS && (st == GrowthStage.EGG || st == GrowthStage.INFANT)) return
        if (wardrobe.equip(id)) { save(); tickListeners.forEach { it() } }
    }
    fun unequipItem(id: String) { wardrobe.unequipItem(id); save(); tickListeners.forEach { it() } }

    /** mini game: points for the coins of one run (capped per day). Returns what was given. */
    fun grantGamePoints(coins: Int): Int {
        val given = engine.grantGamePoints(coins)
        save(); tickListeners.forEach { it() }
        return given
    }

    fun demoTotalKm(km: Float) { engine.demoSetTotalKm(km); save(); tickListeners.forEach { it() } }

    /** demo only: buy every item through the real purchase path (needs enough points) */
    fun demoBuyAll(): Int = Catalog.items.count { buy(it.id) }

    // ---------------------------------------------------------------- demo (the same entry points the car uses)

    fun startDemoRoute() = navi?.startDemoRoute() ?: false

    fun demoDrive() { if (gear != "D") setGear("D", demo = true) }
    fun demoStop() { if (gear == "D") setGear("P", demo = true) }

    fun demoTurn(withSignal: Boolean, harsh: Boolean) {
        demoDrive()
        val steps = if (harsh) 3 else 20
        val total = if (harsh) 300L else 2000L
        val target = if (harsh) 150f else 90f
        if (withSignal) engine.onTurnSignal(Signal.LEFT)
        engine.onSteeringAngle(0f)
        for (i in 1..steps) main.postDelayed({ engine.onSteeringAngle(target * i / steps) }, total * i / steps)
        main.postDelayed({ engine.onSteeringAngle(0f) }, total + 400)
        if (withSignal) main.postDelayed({ engine.onTurnSignal(Signal.OFF) }, total + 800)
    }

    fun demoBrake(jerky: Boolean) {
        demoDrive()
        if (jerky) {
            for (i in 0 until 3) {
                main.postDelayed({ onBrake(true) }, i * 1200L)
                main.postDelayed({ onBrake(false) }, i * 1200L + 300)
            }
        } else {
            // held long enough to count as a stop (a traffic light), then released: the car moves off again
            onBrake(true)
            main.postDelayed({ onBrake(false) }, 6000)
        }
    }

    /** demo only (for the design captures): own every item, then wear exactly [ids] */
    fun demoOutfit(ids: List<String>, unlockAll: Boolean = true) {
        if (unlockAll) Catalog.items.forEach { wardrobe.buy(it.id, { true }) }
        wardrobe.clearWorn()
        ids.forEach { wardrobe.equip(it) }
        save(); tickListeners.forEach { it() }
    }

    /** demo only: give points so the shop can be tried without driving */
    fun demoGivePoints(n: Int) {
        engine.addPoints(n)
        save(); tickListeners.forEach { it() }
    }

    fun claimMission(m: Mission): Boolean {
        if (!engine.claimMission(m)) return false
        save(); tickListeners.forEach { it() }
        return true
    }

    fun demoMission(finished: Boolean) { engine.demoMissionProgress(finished); save(); tickListeners.forEach { it() } }

    /** demo only: set the pet's mood value directly, to show the angry / hurt looks */
    fun demoMood(value: Int) {
        engine.demoSetMood(value)
        save(); tickListeners.forEach { it() }
    }

    // ---------------------------------------------------------------- the map collection

    private var location: LocationSource? = null
    private var fixRegion: String? = null
    private var fixAt = 0L
    /** demo only: pretend the car is in this region (null = use the GPS) */
    private var demoRegion: String? = null

    /** what the pet fetched during the current drive / during the last drive (for the trip card) */
    private val tripFinds = mutableListOf<Find>()
    var pendingFinds: List<Find> = emptyList()
        private set
    private var lastFind: Find? = null
    private var lastFindAt = 0L
    val findListeners = CopyOnWriteArrayList<(Find) -> Unit>()

    /** the region the car is in right now, or null (no GPS, no permission, an old position, outside Korea) */
    val regionId: String? get() = demoRegion ?: if (SystemClock.elapsedRealtime() - fixAt < FIX_STALE_MS) fixRegion else null
    val region: Region? get() = regionId?.let { Korea.region(it) }
    val locationAllowed: Boolean get() = location?.hasPermission() ?: false

    /** only the region is kept from a position */
    private fun onLocation(lat: Double, lon: Double) {
        fixRegion = Korea.locate(lat, lon)?.id
        fixAt = SystemClock.elapsedRealtime()
        dex.enter(fixRegion)
        tickListeners.forEach { it() }
    }

    /** call after the location permission was granted */
    fun startLocation() { location?.start() }

    /**
     * The pet fetches things only while it is out following the car on a navigation route: following switched on, a route
     * in progress, the car moving, and the region known.
     */
    private fun dexDrive(km: Float) {
        if (!followCar || navTrip == null || !moving) return
        val id = regionId ?: return
        for (f in dex.onDrive(id, km, currentDayKey())) handleFind(f)
    }

    private fun handleFind(f: Find) {
        engine.addPoints(f.points)
        tripFinds += f
        lastFind = f; lastFindAt = SystemClock.elapsedRealtime()
        save()
        findListeners.forEach { it(f) }
        tickListeners.forEach { it() }
    }

    /** the find the small pet is still showing and how many ms ago it was fetched, or null when the show is over */
    fun fetchNow(): Pair<Find, Long>? {
        val f = lastFind ?: return null
        val age = SystemClock.elapsedRealtime() - lastFindAt
        return if (age in 0..FETCH_TOTAL_MS) f to age else null
    }

    fun demoRegion(id: String?) { demoRegion = id?.takeIf { Korea.region(it) != null }; dex.enter(demoRegion); tickListeners.forEach { it() } }
    fun demoGeo(lat: Double, lon: Double) { demoRegion(Korea.locate(lat, lon)?.id) }
    fun demoFind() { val id = regionId ?: return; dex.forceFind(id, currentDayKey())?.let { handleFind(it) } }
    fun demoDexFill(ids: List<String>) {
        if (ids.any { it == "all" }) dex.grantAll() else ids.forEach { dex.grantRegion(it) }
        save(); tickListeners.forEach { it() }
    }
    fun demoDexClear() { dex.clear(); save(); tickListeners.forEach { it() } }

    // ---------------------------------------------------------------- the driving record and the badges

    /** the badges the last drive earned (for the trip card) */
    var pendingBadges: List<Badge> = emptyList()
        private set
    /** was the last drive long enough to go into the driving record? */
    var pendingRecorded = false
        private set

    /** a finished drive goes into today's line of the record; then the badges are checked and new ones paid once */
    private fun recordTrip(t: TripResult?) {
        pendingBadges = emptyList()
        pendingRecorded = false
        if (t == null) return
        val today = currentDayKey()
        if (!driveLog.addTrip(today, t)) return
        pendingRecorded = true
        pendingBadges = badges.evaluate(driveLog, today) { engine.addPoints(it) }
        saveRecord()
    }

    private fun saveRecord() {
        recordVersion++
        app?.let { Store.saveDriveLog(it, driveLog); Store.saveBadges(it, badges) }
    }

    /** settings: delete every day of the driving record. The badges and the points already earned stay. */
    fun clearDriveLog() {
        driveLog.clear()
        saveRecord(); tickListeners.forEach { it() }
    }

    /**
     * demo only: a made-up record of the last [days] days (about two in three of them driven, every colour of the calendar,
     * the last few days green), then the badges are checked as after a drive.
     */
    fun demoDriveLog(days: Int = 45) {
        val rnd = java.util.Random(7)
        val today = currentDayKey()
        for (ago in days downTo 1) {
            val near = ago <= 4                                  // the last few days: a green run
            if (!near && rnd.nextInt(3) == 0) continue
            val turns = 4 + rnd.nextInt(14)
            val rate = if (near) 0.9f + rnd.nextFloat() * 0.1f else listOf(0.55f, 0.75f, 0.85f, 0.95f, 1f)[rnd.nextInt(5)]
            val good = (if (near) kotlin.math.ceil(turns * rate.toDouble()).toInt() else (turns * rate).toInt()).coerceIn(0, turns)   // the green run really is 90+
            val trips = 1 + rnd.nextInt(3)
            val km = 2f + rnd.nextInt(380) / 10f
            val jerky = if (rnd.nextInt(4) == 0) 1 + rnd.nextInt(2) else 0
            val harsh = if (rnd.nextInt(5) == 0) 1 else 0
            val points = good * PetEngine.SIGNAL_POINTS + minOf(PetEngine.MAX_DISTANCE_POINTS * trips, (km * 2).toInt()) + rnd.nextInt(5) * PetEngine.CALM_POINTS
            driveLog.put(DayRecord(Days.plus(today, -ago), km, trips, good, turns - good, jerky, harsh, points))
        }
        if (driveLog.size > 0) pendingBadges = badges.evaluate(driveLog, today) { engine.addPoints(it) }
        saveRecord(); save(); tickListeners.forEach { it() }
    }

    /** demo only: one day of the record, [daysAgo] days back (0 = today), then the badges are checked as after a drive */
    fun demoRecordDay(daysAgo: Int, km: Float, good: Int, missed: Int, jerky: Int = 0, harsh: Int = 0) {
        val today = currentDayKey()
        driveLog.put(DayRecord(Days.plus(today, -daysAgo), km, 1, good, missed, jerky, harsh, good * PetEngine.SIGNAL_POINTS + (km * 2).toInt()))
        pendingBadges = badges.evaluate(driveLog, today) { engine.addPoints(it) }
        saveRecord(); save(); tickListeners.forEach { it() }
    }

    /** demo only: no record and no badges at all (to try the first-record badge again) */
    fun demoResetRecord() {
        driveLog.clear(); badges.clear(); pendingBadges = emptyList()
        saveRecord(); tickListeners.forEach { it() }
    }

    // ---------------------------------------------------------------- reactions and the clock

    /** the result of a drive that ended while the pet's screen was not open: shown for 7 s the next time it opens */
    private var pendingTrip: TripResult? = null
    private var pendingShownAt = 0L

    /**
     * The trip card to show, and for how many more milliseconds. The same card is handed out for 7 seconds from the first
     * time it is asked for, even to a screen that has just been re-created (the car's launcher re-creates the app's screen
     * when it puts it on the panel, so a card consumed by the first instance would never be seen).
     */
    fun tripCardNow(): Pair<TripResult, Long>? {
        val t = pendingTrip ?: return null
        val now = SystemClock.elapsedRealtime()
        if (pendingShownAt == 0L) pendingShownAt = now
        val left = TRIP_CARD_MS - (now - pendingShownAt)
        if (left <= 0) { pendingTrip = null; pendingShownAt = 0L; return null }
        return t to left
    }

    private fun onReaction(r: Reaction) {
        if (engine.driving || r.kind == Kind.TRIP_END) {
            // the spoken line follows the growth stage too (see Voice); an egg cannot talk, so it stays quiet
            val stage = engine.snapshot().stage
            if (stage != GrowthStage.EGG) Voice.react(stage, r)?.let { voice?.sayShort(it) }
        }
        if (r.kind == Kind.TRIP_END) {
            pendingTrip = engine.lastTrip; pendingShownAt = 0L; pendingFinds = tripFinds.toList(); tripFinds.clear()
            recordTrip(engine.lastTrip)
        }
        save()
        reactionListeners.forEach { it(r) }
    }

    private var n = 0
    private val loop = object : Runnable {
        override fun run() {
            engine.tick()
            evaluateStop()
            if (demoDriving && engine.driving) { engine.addDistance(DEMO_KMH / 3600f); dexDrive(DEMO_KMH / 3600f) }   // constant speed, one second
            if (++n % 3 == 0) navi?.refresh()
            if (n % 10 == 0) save()
            tickListeners.forEach { it() }
            main.postDelayed(this, 1000)
        }
    }

    const val DEMO_KMH = 60f
    private const val TRIP_CARD_MS = 7_000L
    /** the brake held this long in D means the car is standing (a traffic light), not just slowing */
    private const val STOP_BRAKE_MS = 2_500L
    private const val MAX_ODOMETER_STEP_KM = 5f
    /** a position older than this is not trusted to say which region the car is in */
    private const val FIX_STALE_MS = 3 * 60_000L
    /** the small pet goes off to fetch (1.2 s), is away (1.4 s), comes back (1.2 s) and shows what it brought (5 s) */
    const val FETCH_TOTAL_MS = 8_800L
}

/** The pet's state and the inventory survive restarts. */
object Store {
    private const val FILE = "pet"
    fun load(c: Context): PetSnapshot {
        val p = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val xp = p.getInt("xp", 0)
        return PetSnapshot(p.getInt("mood", PetEngine.START_MOOD), xp, 1 + xp / PetEngine.XP_PER_LEVEL,
            p.getInt("good", 0), p.getInt("missed", 0), p.getInt("jerky", 0), p.getInt("harsh", 0),
            p.getFloat("km", 0f), p.getInt("trips", 0), p.getInt("lastPoints", 0), p.getFloat("lastKm", 0f), p.getInt("points", 0),
            p.getFloat("todayKm", 0f), p.getInt("todayKey", 0),
            p.getInt("earned", maxOf(p.getInt("points", 0), xp)),
            p.getInt("sigToday", 0), p.getInt("tripsToday", 0), p.getInt("cleanToday", 0), p.getInt("claimed", 0), p.getInt("gameToday", 0))
    }
    fun save(c: Context, s: PetSnapshot) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putInt("mood", s.mood).putInt("xp", s.xp).putInt("good", s.goodSignals).putInt("missed", s.missedSignals)
            .putInt("jerky", s.jerkyBrakes).putInt("harsh", s.harshTurns)
            .putFloat("km", s.totalKm).putInt("trips", s.tripCount).putInt("lastPoints", s.lastPoints).putFloat("lastKm", s.lastKm)
            .putInt("points", s.points)
            .putFloat("todayKm", s.todayKm).putInt("todayKey", s.todayKey).putInt("earned", s.earnedPoints)
            .putInt("sigToday", s.signalsToday).putInt("tripsToday", s.tripsToday).putInt("cleanToday", s.cleanTripsToday).putInt("claimed", s.claimedMask).putInt("gameToday", s.gameToday)
            .apply()
    }
    fun loadProfile(c: Context): Pair<String, Boolean> {
        val p = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return p.getString("petName", "").orEmpty() to p.getBoolean("onboarded", false)
    }
    fun saveProfile(c: Context, name: String, done: Boolean) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString("petName", name).putBoolean("onboarded", done).apply()
    }
    fun loadSettings(c: Context): Boolean = c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("followCar", true)
    fun saveSettings(c: Context, follow: Boolean) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean("followCar", follow).apply()
    }
    /** where the user dragged the pet beside the car, as fractions of the screen (null = the default spot) */
    fun loadFollowSpot(c: Context): Pair<Float, Float>? {
        val p = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return if (p.contains("followX")) p.getFloat("followX", 0f) to p.getFloat("followY", 0f) else null
    }
    fun saveFollowSpot(c: Context, x: Float, y: Float) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putFloat("followX", x).putFloat("followY", y).apply()
    }
    fun loadDex(c: Context): Dex = Dex(Dex.decode(c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("dex", "")))
    fun saveDex(c: Context, d: Dex) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString("dex", Dex.encode(d.state())).apply()
    }
    /** the driving record and the badges: written only when they change (after a drive, a deletion), not every 10 s */
    fun loadDriveLog(c: Context): DriveLog = DriveLog.decode(c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("drivelog", ""))
    fun saveDriveLog(c: Context, log: DriveLog) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString("drivelog", DriveLog.encode(log)).apply()
    }
    fun loadBadges(c: Context): BadgeBook = BadgeBook.decode(c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("badges", ""))
    fun saveBadges(c: Context, b: BadgeBook) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString("badges", BadgeBook.encode(b)).apply()
    }
    fun loadWardrobe(c: Context): Wardrobe {
        val p = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return Wardrobe.from(p.getString("owned", ""), p.getString("worn", ""))
    }
    fun saveWardrobe(c: Context, w: Wardrobe) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString("owned", w.ownedCsv()).putString("worn", w.equippedCsv()).apply()
    }
}
