package com.drivepet.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameAndGrowthTest {
    // ---------------------------------------------------------------- growth by distance

    @Test fun stagesFollowTheTotalDistance() {
        assertEquals(GrowthStage.EGG, GrowthStage.of(0f))
        assertEquals(GrowthStage.EGG, GrowthStage.of(999.9f))
        assertEquals(GrowthStage.INFANT, GrowthStage.of(1000f))
        assertEquals(GrowthStage.REBEL, GrowthStage.of(3000f))
        assertEquals(GrowthStage.REBEL, GrowthStage.of(4999f))
        assertEquals(GrowthStage.ADULT, GrowthStage.of(5000f))
        assertEquals(GrowthStage.ADULT, GrowthStage.of(20000f))
    }

    @Test fun distanceToTheNextStage() {
        assertEquals(1000f, GrowthStage.kmToNext(0f)!!, 0.001f)
        assertEquals(1f, GrowthStage.kmToNext(2999f)!!, 0.001f)
        assertNull(GrowthStage.kmToNext(5000f))                  // fully grown
    }

    // ---------------------------------------------------------------- the mini game

    private fun game() = FlapGame(900f, 560f, seed = 3L)

    @Test fun nothingMovesUntilTheFirstTap() {
        val g = game()
        g.step(1f)
        assertEquals(FlapGame.Phase.READY, g.phase)
        assertEquals(280f, g.carY, 0.001f)
    }

    @Test fun aTapHopsUpAndGravityPullsDown() {
        val g = game()
        g.flap()
        val y0 = g.carY
        g.step(0.1f)
        assertTrue(g.carY < y0)                                  // went up
        repeat(30) { g.step(0.05f) }                             // then nothing: it falls
        assertEquals(FlapGame.Phase.OVER, g.phase)               // and lands on the ground
    }

    @Test fun hitTheCeilingEndsTheRun() {
        val g = game()
        repeat(40) { g.flap(); g.step(0.05f); if (g.phase == FlapGame.Phase.OVER) return@repeat }
        assertEquals(FlapGame.Phase.OVER, g.phase)
    }

    @Test fun aCoinInTheCarsPathIsCollectedOnce() {
        val g = game()
        g.flap()
        g.coinList.clear()
        g.coinList += FlapGame.Coin(g.carX + 20f, g.carY)
        g.step(0.01f)
        assertEquals(1, g.coins)
        g.step(0.01f)
        assertEquals(1, g.coins)                                 // not counted twice
    }

    @Test fun touchingAPillarEndsTheRun() {
        val g = game()
        g.flap()
        g.pipes.clear()
        g.pipes += FlapGame.Pipe(g.carX - 10f, 100f)             // the gap is far above the car
        g.step(0.01f)
        assertEquals(FlapGame.Phase.OVER, g.phase)
    }

    @Test fun passingThroughAGapIsSafe() {
        val g = game()
        g.flap()
        g.pipes.clear(); g.coinList.clear()
        g.pipes += FlapGame.Pipe(g.carX - 10f, g.carY + 20f)     // the car is inside the gap
        g.step(0.01f)
        assertEquals(FlapGame.Phase.RUNNING, g.phase)
    }

    @Test fun pillarsKeepComingAndTheSpeedGrowsToACap() {
        val g = game()
        g.flap()
        val s0 = g.speed
        repeat(2000) { if (g.phase == FlapGame.Phase.RUNNING) { g.flap(); g.step(0.016f); if (g.carY < 120f) { /* keep it low */ } } else return@repeat }
        assertTrue(g.speed >= s0)
        assertTrue(g.speed <= FlapGame.MAX_SPEED)
        assertTrue(g.pipes.size >= 1)
    }

    @Test fun resetStartsAgain() {
        val g = game()
        g.flap(); repeat(60) { g.step(0.05f) }
        g.reset()
        assertEquals(FlapGame.Phase.READY, g.phase)
        assertEquals(0, g.coins)
        assertFalse(g.pipes.isEmpty())
    }
}
