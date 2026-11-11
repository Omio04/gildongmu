package com.drivepet.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PetEngineTest {
    private var now = 1_000_000L
    private val seen = mutableListOf<Reaction>()
    private lateinit var e: PetEngine

    @Before fun setUp() {
        now = 1_000_000L
        seen.clear()
        e = PetEngine({ now }) { seen += it }
        e.setDriving(true)
    }

    private fun advance(ms: Long) { now += ms }

    /** Wheel from 0 to [to] degrees over [ms], in 100 ms steps. */
    private fun turn(to: Float, ms: Long) {
        e.onSteeringAngle(0f)
        val steps = (ms / 100).toInt().coerceAtLeast(1)
        repeat(steps) { i ->
            advance(ms / steps)
            e.onSteeringAngle(to * (i + 1) / steps)
        }
    }

    // ---------------------------------------------------------------- driving events

    @Test fun smoothTurnWithSignalMakesPetHappyAndEarnsPoints() {
        e.onTurnSignal(Signal.LEFT)
        turn(90f, 2_000)
        assertEquals(1, e.goodSignals)
        assertEquals(0, e.missedSignals)
        assertEquals(76, e.mood)
        assertEquals(10, e.xp)
        assertEquals(10, e.points)
        assertTrue(seen.any { it.kind == Kind.GOOD_SIGNAL })
    }

    @Test fun turnWithoutSignalOnlyUpsetsThePet() {
        turn(90f, 2_000)
        assertEquals(1, e.missedSignals)
        assertEquals(60, e.mood)
        assertEquals(0, e.xp)
        assertEquals(0, e.points)
    }

    @Test fun signalSwitchedOffJustBeforeStillCounts() {
        e.onTurnSignal(Signal.RIGHT)
        advance(500)
        e.onTurnSignal(Signal.OFF)
        advance(500)
        turn(90f, 1_000)
        assertEquals(1, e.goodSignals)
    }

    @Test fun signalOffLongAgoDoesNotCount() {
        e.onTurnSignal(Signal.RIGHT)
        e.onTurnSignal(Signal.OFF)
        advance(10_000)
        turn(90f, 1_000)
        assertEquals(1, e.missedSignals)
    }

    @Test fun smallWheelMovementsAreIgnored() {
        turn(30f, 1_000)
        assertEquals(0, e.goodSignals + e.missedSignals + e.harshTurns)
        assertEquals(70, e.mood)
    }

    @Test fun fastWheelIsHarsh() {
        e.onTurnSignal(Signal.LEFT)
        turn(150f, 250)   // 600 deg/s
        assertEquals(1, e.harshTurns)
        assertTrue(e.mood < 70)
        assertTrue(seen.any { it.kind == Kind.HARSH_TURN })
    }

    @Test fun threeBrakeTapsInTenSecondsIsJerky() {
        repeat(3) { e.onBrake(true); advance(300); e.onBrake(false); advance(1_000) }
        assertEquals(1, e.jerkyBrakes)
        assertEquals(58, e.mood)
    }

    @Test fun oneLongBrakeIsFine() {
        e.onBrake(true); advance(4_000); e.onBrake(false)
        assertEquals(0, e.jerkyBrakes)
        assertEquals(70, e.mood)
    }

    @Test fun brakeTapsSpreadOutAreFine() {
        repeat(3) { e.onBrake(true); advance(300); e.onBrake(false); advance(8_000) }
        assertEquals(0, e.jerkyBrakes)
    }

    @Test fun nothingCountsWhileParked() {
        e.setDriving(false)
        turn(150f, 250)
        repeat(3) { e.onBrake(true); advance(200); e.onBrake(false); advance(500) }
        assertEquals(0, e.goodSignals + e.missedSignals + e.harshTurns + e.jerkyBrakes)
        assertEquals(70, e.mood)
    }

    @Test fun calmDrivingSlowlyRaisesMoodAndEarnsPoints() {
        advance(PetEngine.CALM_AFTER_BAD_MS)
        repeat(PetEngine.CALM_TICKS_PER_STEP * 3) { advance(1_000); e.tick() }
        assertEquals(76, e.mood)
        assertEquals(9, e.xp)
        assertEquals(9, e.points)
    }

    @Test fun noCalmRewardRightAfterABadMoment() {
        turn(90f, 1_000)   // missed signal
        val mood = e.mood
        repeat(10) { advance(1_000); e.tick() }
        assertEquals(mood, e.mood)
        assertEquals(0, e.points)
    }

    @Test fun moodNeverLeavesZeroToHundred() {
        repeat(30) { advance(5_000); turn(150f, 200); e.onSteeringAngle(0f) }
        assertEquals(0, e.mood)
        repeat(60) { advance(5_000); e.onTurnSignal(Signal.LEFT); turn(90f, 1_000); e.onSteeringAngle(0f) }
        assertEquals(100, e.mood)
    }

    @Test fun levelUpEveryHundredXp() {
        repeat(10) { advance(5_000); e.onTurnSignal(Signal.LEFT); turn(90f, 1_000); e.onSteeringAngle(0f) }
        assertEquals(2, e.level)
        assertTrue(seen.any { it.kind == Kind.LEVEL_UP })
    }

    // ---------------------------------------------------------------- points never go down

    @Test fun roughDrivingNeverTakesPointsAway() {
        e.onTurnSignal(Signal.LEFT); turn(90f, 1_000); e.onSteeringAngle(0f); e.onTurnSignal(Signal.OFF)   // +10
        val before = e.points
        repeat(5) { advance(5_000); turn(150f, 200); e.onSteeringAngle(0f) }                              // missed + harsh x5
        repeat(3) { e.onBrake(true); advance(300); e.onBrake(false); advance(1_000) }                       // jerky
        assertEquals(before, e.points)
        assertEquals(before, e.xp)
        assertTrue(e.missedSignals > 0 && e.harshTurns > 0 && e.jerkyBrakes > 0)
    }

    @Test fun cleanDriveEarnsDistanceAndBonusOnTop() {
        e.onTurnSignal(Signal.LEFT); turn(90f, 1_000); e.onSteeringAngle(0f); e.onTurnSignal(Signal.OFF)   // +10 on the way
        e.addDistance(3f)
        e.setDriving(false)
        val t = e.lastTrip!!
        assertTrue(t.clean)
        assertEquals(10 + 6 + 10, t.points)          // signal + 3 km x 2 + clean bonus
        assertEquals(26, e.points)
        assertEquals(26, e.xp)
        assertEquals(1, e.tripCount)
        assertEquals(26, e.lastPoints)
        assertTrue(seen.any { it.kind == Kind.TRIP_END })
    }

    @Test fun roughDriveGetsNoCleanBonusButStillEarnsDistance() {
        turn(90f, 1_000)    // missed signal
        e.addDistance(2f)
        e.setDriving(false)
        val t = e.lastTrip!!
        assertFalse(t.clean)
        assertEquals(4, t.points)                    // 2 km x 2, nothing else
        assertEquals(4, e.points)
    }

    @Test fun distancePointsAreCapped() {
        e.addDistance(100f)
        e.setDriving(false)
        assertEquals(PetEngine.MAX_DISTANCE_POINTS + PetEngine.CLEAN_BONUS, e.lastTrip!!.points)
    }

    @Test fun veryShortDriveIsNotCounted() {
        e.addDistance(0.05f)
        e.setDriving(false)
        assertNull(e.lastTrip)
        assertEquals(0, e.tripCount)
        assertEquals(0, e.points)
    }

    @Test fun distanceCountsOnlyWhileDriving() {
        e.addDistance(2f)
        e.setDriving(false)
        e.addDistance(5f)
        assertEquals(2f, e.totalKm, 0.001f)
    }

    @Test fun todaysDistanceStartsOverEachDayButTheTotalKeepsGrowing() {
        var day = 20261110
        e.dayKey = { day }
        e.addDistance(2f)
        assertEquals(2f, e.snapshot().todayKm, 0.001f)
        day = 20261111                                    // the next day
        assertEquals(0f, e.snapshot().todayKm, 0.001f)
        e.addDistance(1.5f)
        assertEquals(1.5f, e.snapshot().todayKm, 0.001f)
        assertEquals(3.5f, e.snapshot().totalKm, 0.001f)
    }

    @Test fun eachDriveStartsFromScratch() {
        e.addDistance(2f); e.setDriving(false)
        e.setDriving(true)
        assertEquals(0f, e.tripKm, 0.001f)
        assertEquals(0, e.tripEarned)
        assertEquals(2f, e.totalKm, 0.001f)    // the total keeps growing
    }

    // ---------------------------------------------------------------- spending

    @Test fun spendingTakesPointsButNotLevel() {
        e.onTurnSignal(Signal.LEFT); turn(90f, 1_000)
        val xp = e.xp
        assertTrue(e.spend(4))
        assertEquals(6, e.points)
        assertEquals(xp, e.xp)
    }

    @Test fun totalEarnedPointsDoNotGoDownWhenYouSpend() {
        e.onTurnSignal(Signal.LEFT); turn(90f, 1_000)              // +10
        e.addDistance(3f); e.setDriving(false)                      // +6 distance +10 clean bonus
        assertEquals(26, e.earnedPoints)
        assertTrue(e.spend(20))
        assertEquals(6, e.points)
        assertEquals(26, e.earnedPoints)                            // what was ever earned stays
        e.addPoints(100)
        assertEquals(126, e.earnedPoints)
    }

    @Test fun cannotSpendMoreThanYouHave() {
        assertFalse(e.spend(1))
        assertEquals(0, e.points)
    }

    // ---------------------------------------------------------------- shop and inventory

    private fun wallet(start: Int): Pair<(Int) -> Boolean, () -> Int> {
        var p = start
        return Pair({ c: Int -> if (c <= p) { p -= c; true } else false }, { p })
    }

    @Test fun buyingPaysTheListedPriceOnce() {
        val (spend, left) = wallet(100)
        val w = Wardrobe()
        assertTrue(w.buy("sun", spend))
        assertEquals(60, left())
        assertTrue(w.has("sun"))
        assertFalse(w.buy("sun", spend))     // already owned: not charged again
        assertEquals(60, left())
    }

    @Test fun cannotBuyWithoutEnoughPoints() {
        val (spend, left) = wallet(10)
        val w = Wardrobe()
        assertFalse(w.buy("crown", spend))
        assertFalse(w.has("crown"))
        assertEquals(10, left())
    }

    @Test fun onlyOwnedItemsCanBeWorn() {
        val w = Wardrobe()
        assertFalse(w.equip("sun"))
        assertTrue(w.equipped.isEmpty())
    }

    @Test fun oneItemPerSlotAndUnequip() {
        val (spend, _) = wallet(500)
        val w = Wardrobe()
        w.buy("straw", spend); w.buy("crown", spend); w.buy("sun", spend)
        w.equip("straw"); w.equip("sun")
        w.equip("crown")                      // replaces the straw hat
        assertEquals("crown", w.equipped[Slot.HAT])
        assertFalse(w.isEquipped("straw"))
        assertEquals("sun", w.equipped[Slot.GLASSES])
        w.unequip(Slot.HAT)
        assertNull(w.equipped[Slot.HAT])
        assertTrue(w.has("crown"))            // still owned
    }

    @Test fun wardrobeSurvivesSaveAndLoad() {
        val (spend, _) = wallet(500)
        val w = Wardrobe()
        w.buy("beret", spend); w.buy("jeans", spend); w.buy("lamp", spend)
        w.equip("beret"); w.equip("lamp")
        val back = Wardrobe.from(w.ownedCsv(), w.equippedCsv())
        assertEquals(w.owned, back.owned)
        assertEquals(w.equipped, back.equipped)
    }

    @Test fun loadingIgnoresUnknownOrNotOwnedItems() {
        val w = Wardrobe.from("sun,ghost", "HAT=crown,GLASSES=sun,PANTS=nothing")
        assertEquals(setOf("sun"), w.owned)
        assertEquals(mapOf(Slot.GLASSES to "sun"), w.equipped)
    }

    // ---------------------------------------------------------------- the pet's look

    @Test fun moodDecidesAngryAndHurt() {
        assertEquals(PetState.NORMAL, PetState.from(70, false))
        assertEquals(PetState.NORMAL, PetState.from(60, false))
        assertEquals(PetState.ANGRY, PetState.from(59, false))
        assertEquals(PetState.ANGRY, PetState.from(30, false))
        assertEquals(PetState.HURT, PetState.from(29, false))
        assertEquals(PetState.HURT, PetState.from(0, false))
    }

    @Test fun hungerShowsOnlyWhenNothingWorseApplies() {
        assertEquals(PetState.HUNGRY, PetState.from(90, true))
        assertEquals(PetState.ANGRY, PetState.from(50, true))    // angry first
        assertEquals(PetState.HURT, PetState.from(10, true))     // hurt first of all
    }

    @Test fun lowBatteryOrFuelMakesItHungry() {
        assertTrue(PetState.energyLow(null, 15))
        assertFalse(PetState.energyLow(null, 16))
        assertTrue(PetState.energyLow(10, null))                 // no battery reported: the fuel counts
        assertFalse(PetState.energyLow(40, null))
        assertFalse(PetState.energyLow(null, null))              // unknown is not low
    }

    @Test fun anElectricCarIgnoresAFuelValueOfZero() {
        // an electric car may report 0 for a fuel tank it does not have: the battery decides
        assertFalse(PetState.energyLow(0, 80))
        assertTrue(PetState.energyLow(80, 10))
    }

    @Test fun roughDrivingMakesItAngryThenHurtAndCalmDrivingHealsIt() {
        turn(90f, 1_000)                                         // missed signal: 70 -> 60, still normal
        assertEquals(PetState.NORMAL, e.snapshot().state())
        advance(5_000); e.onSteeringAngle(0f)
        turn(90f, 1_000)                                         // another: 50
        assertEquals(PetState.ANGRY, e.snapshot().state())
        advance(5_000); e.onSteeringAngle(0f)
        turn(150f, 250)                                          // missed + harsh: well below 30
        assertEquals(PetState.HURT, e.snapshot().state())
        advance(PetEngine.CALM_AFTER_BAD_MS)
        repeat(PetEngine.CALM_TICKS_PER_STEP * 20) { advance(1_000); e.tick() }   // a long calm stretch
        assertEquals(PetState.NORMAL, e.snapshot().state())
    }

    // ---------------------------------------------------------------- daily missions

    @Test fun signalMissionCountsGoodTurnsAndPaysOnceADay() {
        val m = Missions.all.first { it.id == 0 }
        repeat(3) { e.onTurnSignal(Signal.LEFT); turn(90f, 1_000); e.onSteeringAngle(0f); e.onTurnSignal(Signal.OFF); advance(5_000) }
        assertTrue(Missions.done(m, e.snapshot()))
        val before = e.points
        assertTrue(e.claimMission(m))
        assertEquals(before + m.reward, e.points)
        assertTrue(Missions.claimed(m, e.snapshot()))
        assertFalse(e.claimMission(m))                              // only once
        assertEquals(before + m.reward, e.points)
    }

    @Test fun anUnfinishedMissionCannotBeCollected() {
        val m = Missions.all.first { it.id == 1 }                   // 5 km
        e.addDistance(2f)
        assertFalse(Missions.done(m, e.snapshot()))
        assertFalse(e.claimMission(m))
        assertEquals(0, e.points)
    }

    @Test fun tripMissionsCountFinishedDrives() {
        e.addDistance(1f); e.setDriving(false)                      // a clean drive
        e.setDriving(true); turn(90f, 1_000); e.addDistance(1f); e.setDriving(false)   // a rough one
        val s = e.snapshot()
        assertEquals(2, s.tripsToday)
        assertEquals(1, s.cleanTripsToday)
        assertTrue(Missions.done(Missions.all.first { it.id == 3 }, s))    // 2 drives
        assertTrue(Missions.done(Missions.all.first { it.id == 2 }, s))    // one clean drive
    }

    @Test fun missionsStartOverTheNextDayAndCanBeCollectedAgain() {
        var day = 20261110
        e.dayKey = { day }
        val m = Missions.all.first { it.id == 3 }
        e.addDistance(1f); e.setDriving(false); e.setDriving(true); e.addDistance(1f); e.setDriving(false)
        assertTrue(e.claimMission(m))
        day = 20261111
        val s = e.snapshot()
        assertEquals(0, s.tripsToday)
        assertFalse(Missions.claimed(m, s))
        assertEquals(0f, s.todayKm, 0.001f)
    }

    @Test fun progressLabelsAreReadable() {
        val s = PetSnapshot(70, 0, 1, 0, 0, 0, 0, todayKm = 2.34f, signalsToday = 1)
        assertEquals("1 / 3번", Missions.label(Missions.all[0], s))
        assertEquals("2.3 / 5km", Missions.label(Missions.all[1], s))
    }
}