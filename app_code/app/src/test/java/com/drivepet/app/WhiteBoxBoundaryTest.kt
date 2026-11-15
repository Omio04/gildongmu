package com.drivepet.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * White-box tests: they know the thresholds in the code ([PetEngine] constants, the `>=` / `>` / `<=` that decide each branch)
 * and test the value just below, exactly on, and just above each one. The existing tests check the rules with typical values;
 * these check that the rules do not shift by one when somebody changes a comparison.
 */
class WhiteBoxBoundaryTest {
    private var now = 1_000_000L
    private lateinit var e: PetEngine

    @Before fun setUp() {
        now = 1_000_000L
        e = PetEngine({ now })
        e.setDriving(true)
    }

    private fun advance(ms: Long) { now += ms }

    // ================================================================ turn start (45 degrees)

    @Test fun aTurnStartsExactlyAtFortyFiveDegrees() {
        e.onSteeringAngle(0f)
        advance(100); e.onSteeringAngle(44.9f)
        assertEquals("just below 45: no turn yet", 0, e.missedSignals + e.goodSignals)
        advance(100); e.onSteeringAngle(45f)
        assertEquals("exactly 45: the turn starts", 1, e.missedSignals)
    }

    @Test fun aTurnWithTheWheelAlreadyBeyondFortyFiveIsNotCountedAgain() {
        e.onSteeringAngle(0f)
        advance(100); e.onSteeringAngle(50f)
        advance(500); e.onSteeringAngle(80f)    // still turning: the start is only the crossing
        assertEquals(1, e.missedSignals)
    }

    // ================================================================ signal grace (1.5 s after the signal went off)

    @Test fun aSignalSwitchedOffExactlyOnePointFiveSecondsAgoStillCounts() {
        e.onSteeringAngle(0f)
        e.onTurnSignal(Signal.LEFT)
        e.onTurnSignal(Signal.OFF)
        advance(1_400); e.onSteeringAngle(0f)
        advance(100); e.onSteeringAngle(45f)      // 1500 ms after the signal went off
        assertEquals(1, e.goodSignals)
        assertEquals(0, e.missedSignals)
    }

    @Test fun aSignalSwitchedOffOneMillisecondTooLongAgoDoesNotCount() {
        e.onSteeringAngle(0f)
        e.onTurnSignal(Signal.LEFT)
        e.onTurnSignal(Signal.OFF)
        advance(1_401); e.onSteeringAngle(0f)
        advance(100); e.onSteeringAngle(45f)      // 1501 ms
        assertEquals(0, e.goodSignals)
        assertEquals(1, e.missedSignals)
    }

    @Test fun aSignalStillOnCountsHoweverLongItHasBeenOn() {
        e.onSteeringAngle(0f)
        e.onTurnSignal(Signal.RIGHT)
        advance(60_000); e.onSteeringAngle(0f)
        advance(100); e.onSteeringAngle(45f)
        assertEquals(1, e.goodSignals)
    }

    // ================================================================ turn cooldown (4 s)

    private fun crossFortyFive() {
        // from 0 to 45 degrees in one 100 ms step (fast enough to cross, too short an angle to be harsh)
        advance(100); e.onSteeringAngle(45f)
    }

    private fun backToZero() { advance(100); e.onSteeringAngle(0f) }

    @Test fun aSecondTurnExactlyFourSecondsAfterTheFirstIsIgnored() {
        e.onSteeringAngle(0f)
        crossFortyFive()                           // turn 1 at T
        backToZero()                               // T + 100
        advance(3_800); e.onSteeringAngle(0f)      // T + 3900
        crossFortyFive()                           // T + 4000: cooldown is "more than 4000"
        assertEquals(1, e.missedSignals)
    }

    @Test fun aSecondTurnOneMillisecondAfterTheCooldownIsCounted() {
        e.onSteeringAngle(0f)
        crossFortyFive()
        backToZero()
        advance(3_801); e.onSteeringAngle(0f)
        crossFortyFive()                           // T + 4001
        assertEquals(2, e.missedSignals)
    }

    // ================================================================ harsh wheel (360 deg/s, at least 60 deg, within 1 s)

    @Test fun theWheelMovingExactlyAtTheHarshRateAndAngleIsHarsh() {
        e.onSteeringAngle(24f)
        advance(100); e.onSteeringAngle(60f)       // 36 deg in 100 ms = 360 deg/s, 60 deg
        assertEquals(1, e.harshTurns)
    }

    @Test fun justUnderTheHarshAngleIsNotHarsh() {
        e.onSteeringAngle(23f)
        advance(100); e.onSteeringAngle(59f)       // 360 deg/s but only 59 deg
        assertEquals(0, e.harshTurns)
    }

    @Test fun justUnderTheHarshRateIsNotHarsh() {
        e.onSteeringAngle(25f)
        advance(100); e.onSteeringAngle(60f)       // 350 deg/s
        assertEquals(0, e.harshTurns)
    }

    @Test fun twoWheelSamplesOneSecondApartAreStillCompared() {
        e.onSteeringAngle(0f)
        advance(1_000); e.onSteeringAngle(400f)    // 400 deg/s over exactly the 1 s window
        assertEquals(1, e.harshTurns)
    }

    @Test fun twoWheelSamplesMoreThanOneSecondApartAreNotCompared() {
        e.onSteeringAngle(0f)
        advance(1_001); e.onSteeringAngle(900f)    // too far apart to say how fast it moved
        assertEquals(0, e.harshTurns)
    }

    @Test fun aSecondHarshTurnWithinThreeSecondsIsIgnoredAfterwardsCounted() {
        e.onSteeringAngle(0f)
        advance(100); e.onSteeringAngle(100f)      // harsh (1000 deg/s)
        advance(100); e.onSteeringAngle(0f)        // fast back, inside the 3 s cooldown
        assertEquals(1, e.harshTurns)
        advance(3_000); e.onSteeringAngle(0f)
        advance(100); e.onSteeringAngle(100f)
        assertEquals(2, e.harshTurns)
    }

    // ================================================================ jerky brake (3 presses within 10 s)

    private fun tap() { e.onBrake(true); advance(100); e.onBrake(false) }

    @Test fun threePressesExactlyTenSecondsApartIsJerky() {
        tap()                                       // press at T
        advance(4_900); tap()                       // T + 5000
        advance(4_900); tap()                       // T + 10000: the first is not "more than 10 s" old
        assertEquals(1, e.jerkyBrakes)
    }

    @Test fun threePressesOneMillisecondOverTheWindowIsNotJerky() {
        tap()
        advance(4_900); tap()
        advance(4_901); tap()                       // T + 10001: the first press has dropped out
        assertEquals(0, e.jerkyBrakes)
    }

    @Test fun twoPressesAreNeverJerky() {
        tap(); advance(500); tap()
        assertEquals(0, e.jerkyBrakes)
    }

    @Test fun holdingThePedalDownIsOnePressNoMatterHowManyReports() {
        repeat(10) { e.onBrake(true); advance(100) }   // the same "pressed" over and over
        e.onBrake(false)
        assertEquals(0, e.jerkyBrakes)
    }

    // ================================================================ distance points and the trip rules

    private fun drive(km: Float) { e.addDistance(km); e.setDriving(false) }

    @Test fun aDriveJustUnderOneTenthOfAKilometreIsNotATrip() {
        drive(0.099f)
        assertEquals(0, e.tripCount)
    }

    @Test fun aDriveOfExactlyOneTenthOfAKilometreIsATripAndEarnsOnlyTheCleanBonus() {
        drive(0.1f)
        assertEquals(1, e.tripCount)
        assertEquals("0 km points + clean bonus", PetEngine.CLEAN_BONUS, e.lastPoints)
    }

    @Test fun distancePointsAreTwoPerKilometreRounded() {
        drive(10f)
        assertEquals(10 * 2 + PetEngine.CLEAN_BONUS, e.lastPoints)
    }

    @Test fun distancePointsStopAtTheCap() {
        drive(20f)
        assertEquals(PetEngine.MAX_DISTANCE_POINTS + PetEngine.CLEAN_BONUS, e.lastPoints)
        e.setDriving(true)
        drive(500f)
        assertEquals("a long drive earns no more than the cap", PetEngine.MAX_DISTANCE_POINTS + PetEngine.CLEAN_BONUS, e.lastPoints)
    }

    @Test fun nothingIsAddedToTheDistanceWhileNotDriving() {
        e.setDriving(false)
        e.addDistance(5f)
        assertEquals(0f, e.totalKm, 0f)
        assertEquals(0f, e.todayKm, 0f)
    }

    @Test fun aNegativeOrZeroDistanceIsIgnored() {
        e.addDistance(-3f)
        e.addDistance(0f)
        assertEquals(0f, e.totalKm, 0f)
    }

    @Test fun aRoughMomentTakesAwayTheCleanBonusButNotTheDistancePoints() {
        e.onSteeringAngle(0f)
        advance(100); e.onSteeringAngle(100f)       // harsh (and a missed signal)
        e.addDistance(5f)
        e.setDriving(false)
        assertEquals(10, e.lastPoints)              // 5 km * 2, no bonus
    }

    @Test fun levelUpGivesTheBonusPointsButNotExperience() {
        val xpBefore = e.xp
        // each good turn is +10 xp: ten turns reach level 2
        repeat(10) {
            e.onSteeringAngle(0f)
            e.onTurnSignal(Signal.LEFT)
            advance(5_000); e.onSteeringAngle(0f)
            advance(100); e.onSteeringAngle(45f)
        }
        assertEquals(2, e.level)
        assertEquals(xpBefore + 100, e.xp)
        // 10 turns * 10 points + the level-up bonus
        assertTrue(e.points >= 100 + PetEngine.LEVEL_REWARD)
    }

    // ================================================================ pet state and energy

    @Test fun moodThirtyIsAngryAndTwentyNineIsHurt() {
        assertEquals(PetState.ANGRY, PetState.from(30, false))
        assertEquals(PetState.HURT, PetState.from(29, false))
    }

    @Test fun moodSixtyIsNormalAndFiftyNineIsAngry() {
        assertEquals(PetState.NORMAL, PetState.from(60, false))
        assertEquals(PetState.ANGRY, PetState.from(59, false))
    }

    @Test fun hungerShowsOnlyWhenTheMoodIsAtLeastSixty() {
        assertEquals(PetState.HUNGRY, PetState.from(60, true))
        assertEquals(PetState.ANGRY, PetState.from(59, true))
        assertEquals(PetState.HURT, PetState.from(29, true))
    }

    @Test fun energyIsLowAtFifteenPercentAndNotAtSixteen() {
        assertTrue(PetState.energyLow(null, 15))
        assertFalse(PetState.energyLow(null, 16))
        assertTrue(PetState.energyLow(15, null))
        assertFalse(PetState.energyLow(16, null))
    }

    @Test fun anUnknownEnergyIsNotLow() {
        assertFalse(PetState.energyLow(null, null))
    }

    @Test fun theBatteryDecidesWhenTheCarReportsOneEvenIfTheFuelIsEmpty() {
        assertFalse(PetState.energyLow(0, 80))
        assertTrue(PetState.energyLow(90, 5))
    }

    // ================================================================ growth stages

    @Test fun stagesChangeExactlyAtTheirDistance() {
        assertEquals(GrowthStage.EGG, GrowthStage.of(999.9f))
        assertEquals(GrowthStage.INFANT, GrowthStage.of(1000f))
        assertEquals(GrowthStage.INFANT, GrowthStage.of(2999.9f))
        assertEquals(GrowthStage.REBEL, GrowthStage.of(3000f))
        assertEquals(GrowthStage.REBEL, GrowthStage.of(4999.9f))
        assertEquals(GrowthStage.ADULT, GrowthStage.of(5000f))
        assertEquals(GrowthStage.ADULT, GrowthStage.of(100_000f))
    }

    @Test fun theDistanceToTheNextStageEndsWithTheAdult() {
        assertEquals(1000f, GrowthStage.kmToNext(0f)!!, 0.001f)
        assertEquals(0.5f, GrowthStage.kmToNext(999.5f)!!, 0.001f)
        assertNull(GrowthStage.kmToNext(5000f))
    }

    // ================================================================ daily missions

    @Test fun everyMissionStartsUnfinishedAndUnclaimed() {
        val s = e.snapshot()
        for (m in Missions.all) {
            assertFalse("${m.title} finished at the start", Missions.done(m, s))
            assertFalse("${m.title} claimed at the start", Missions.claimed(m, s))
        }
    }

    @Test fun theSignalMissionNeedsThreeGoodTurns() {
        fun goodTurn() {
            e.onSteeringAngle(0f); e.onTurnSignal(Signal.LEFT)
            advance(5_000); e.onSteeringAngle(0f)
            advance(100); e.onSteeringAngle(45f)
        }
        val signalMission = Missions.all.first { it.id == 0 }
        goodTurn(); goodTurn()
        assertFalse(Missions.done(signalMission, e.snapshot()))
        goodTurn()
        assertTrue(Missions.done(signalMission, e.snapshot()))
    }

    @Test fun aMissionRewardCanBeCollectedOnlyOnce() {
        e.demoMissionProgress(true)
        val m = Missions.all.first()
        assertTrue(e.claimMission(m))
        assertFalse(e.claimMission(m))
    }

    @Test fun theMissionLabelNeverShowsMoreThanTheGoal() {
        e.demoMissionProgress(true)
        for (m in Missions.all) {
            val label = Missions.label(m, e.snapshot())
            assertTrue("label '$label' for ${m.title}", label.contains("/"))
        }
    }

    // ================================================================ navigation turn words and distances

    @Test fun everyKnownTurnTypeHasAWordAndAnArrow() {
        val types = listOf(
            "TURN_LEFT" to "좌회전", "TURN_RIGHT" to "우회전", "TURN_STRAIGHT" to "직진",
            "TURN_SHARP_L" to "급좌회전", "TURN_SHARP_R" to "급우회전",
            "TURN_KEEP_L" to "왼쪽 방향", "TURN_KEEP_R" to "오른쪽 방향", "TURN_UTURN" to "U턴",
            "TURN_ARRIVE" to "목적지 도착", "TURN_TUNNEL" to "터널", "TURN_TOLL" to "톨게이트",
            "TURN_ENTER_SERVICEAREA" to "휴게소", "TURN_RESTAREA" to "졸음쉼터", "TURN_DEVIDE_MERGE" to "분기·합류",
        )
        for ((type, word) in types) {
            val t = Turns.from(type, 100.0, null)
            assertEquals(type, word, t.word)
            assertTrue("$type has an arrow", t.arrow.isNotEmpty())
        }
    }

    @Test fun interchangeAndRoundaboutTypesAreRecognisedByTheirPrefix() {
        assertEquals("진입", Turns.from("TURN_ENTER_IC_L", 0.0, null).word)
        assertEquals("출구", Turns.from("TURN_EXIT_IC_R", 0.0, null).word)
        assertEquals("회전교차로", Turns.from("TURN_ENTER_ROUNDABOUT_1", 0.0, null).word)
        assertEquals("회전교차로", Turns.from("TURN_EXIT_ROUNDABOUT", 0.0, null).word)
    }

    @Test fun anUnknownTurnTypeFallsBackToAGenericWord() {
        assertEquals("안내", Turns.from("TURN_SOMETHING_NEW", 50.0, null).word)
    }

    @Test fun aNegativeDistanceIsShownAsZeroAndTheDescriptionIsTrimmed() {
        val t = Turns.from("TURN_LEFT", -20.0, "  기업지원허브 방면  ")
        assertEquals(0, t.meters)
        assertEquals("기업지원허브 방면", t.description)
    }

    @Test fun distancesUnderAKilometreAreRoundedDownToTenMetres() {
        assertEquals("0m", Turns.distance(0))
        assertEquals("80m", Turns.distance(85))
        assertEquals("990m", Turns.distance(999))
    }

    @Test fun distancesFromAKilometreAreShownInKilometres() {
        assertEquals("1.0km", Turns.distance(1000))
        assertEquals("2.4km", Turns.distance(2400))
    }

    // ================================================================ positions from the navigation

    @Test fun positionsOnTheEdgeOfTheGlobeAreUsableAndJustOutsideAreNot() {
        assertTrue(NaviPositions.usable(90.0, 180.0))
        assertTrue(NaviPositions.usable(-90.0, -180.0))
        assertFalse(NaviPositions.usable(90.0001, 0.5))
        assertFalse(NaviPositions.usable(0.5, -180.0001))
    }

    @Test fun aPositionWithOnlyOneZeroIsStillUsable() {
        assertTrue(NaviPositions.usable(0.0, 127.0))
        assertTrue(NaviPositions.usable(37.0, 0.0))
    }

    @Test fun anInfinityIsNeverUsable() {
        assertFalse(NaviPositions.usable(Double.NEGATIVE_INFINITY, 127.0))
        assertFalse(NaviPositions.usable(37.0, Double.NaN))
    }

    // ================================================================ place lookup edges

    @Test fun positionsOutsideTheKoreanBoxAreNowhere() {
        assertNull(Korea.locate(31.9, 127.0))
        assertNull(Korea.locate(39.1, 127.0))
        assertNull(Korea.locate(37.0, 123.9))
        assertNull(Korea.locate(37.0, 132.1))
    }

    @Test fun notANumberIsNowhere() {
        assertNull(Korea.locate(Double.NaN, 127.0))
        assertNull(Korea.locate(37.0, Double.NaN))
    }

    @Test fun theEmulatorsDefaultPositionIsInGyeonggi() {
        assertEquals("gyeonggi", Korea.locate(37.4467, 127.1386)?.id)
    }

    // ================================================================ the pet's words

    @Test fun thePetNeverSaysAnEmptyLineAtAnyGrowthStage() {
        for (stage in GrowthStage.values()) {
            for (kind in Kind.values()) {
                val line = Voice.react(stage, Reaction(kind, "기본 문장", 0, 0))
                if (line != null) assertTrue("$stage/$kind said a blank line", line.isNotBlank())
            }
            for (ev in Ev.values()) {
                val line = Voice.event(stage, ev, "이름", "대체 문장")
                if (line != null) assertTrue("$stage/$ev said a blank line", line.isNotBlank())
            }
            for (act in Idle.values()) {
                val line = Voice.idle(stage, act)
                if (line != null) assertTrue("$stage/$act said a blank line", line.isNotBlank())
            }
        }
    }

    @Test fun anAdultAlwaysHasSomethingToSayAboutAGoodTurn() {
        assertNotNull(Voice.react(GrowthStage.ADULT, Reaction(Kind.GOOD_SIGNAL, "고마워", 6, 10)))
    }

    // ================================================================ spending and saving

    @Test fun spendingExactlyAllThePointsLeavesZero() {
        e.addPoints(50)
        assertTrue(e.spend(50))
        assertEquals(0, e.points)
        assertFalse(e.spend(1))
    }

    @Test fun spendingNothingOrLessThanNothingNeverGivesPointsBack() {
        e.addPoints(10)
        e.spend(-5)
        assertTrue("points after a negative spend: ${e.points}", e.points <= 10)
    }

    @Test fun aSnapshotRestoresTheSameCounters() {
        e.onSteeringAngle(0f)
        advance(100); e.onSteeringAngle(100f)
        val s = e.snapshot()
        val again = PetEngine({ now }, s)
        assertEquals(s.mood, again.mood)
        assertEquals(s.harshTurns, again.harshTurns)
        assertEquals(s.missedSignals, again.missedSignals)
    }
}
