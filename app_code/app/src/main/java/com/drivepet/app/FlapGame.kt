package com.drivepet.app

import java.util.Random
import kotlin.math.hypot
import kotlin.math.min

/**
 * The mini game: a flappy-style bounce. The pet rides in a car (seen from the side); a tap makes the car hop up, gravity
 * pulls it down, pillars come from the right with a gap, and coins hang in the gaps. Touching a pillar, the ground or the
 * ceiling ends the run. Every coin collected is worth a point (the daily cap is applied by the pet's engine, not here).
 *
 * Pure logic with no Android classes, so it can be tested: the view only draws what is here and calls [flap] and [step].
 */
class FlapGame(var w: Float, var h: Float, seed: Long = 7L) {
    enum class Phase { READY, RUNNING, OVER }

    class Pipe(var x: Float, val gapY: Float)
    class Coin(var x: Float, val y: Float, var taken: Boolean = false)

    private val rnd = Random(seed)

    var phase = Phase.READY; private set
    var carY = h / 2f; private set
    var vy = 0f; private set
    var coins = 0; private set
    var distance = 0f; private set
    val pipes = mutableListOf<Pipe>()
    val coinList = mutableListOf<Coin>()

    val carX get() = w * CAR_X_FRACTION
    val groundTop get() = h - GROUND_H
    val speed get() = min(MAX_SPEED, BASE_SPEED + distance * 0.02f)

    init { reset() }

    fun reset() {
        phase = Phase.READY
        carY = h / 2f; vy = 0f; coins = 0; distance = 0f
        pipes.clear(); coinList.clear()
        // the first pillar comes a little after the start so there is time to get a feel for the hop
        var x = w * 0.85f
        while (x < w + SPACING) { addPipe(x); x += SPACING }
    }

    private fun addPipe(x: Float) {
        val minY = GAP / 2f + 50f
        val maxY = groundTop - GAP / 2f - 30f
        val gapY = minY + rnd.nextFloat() * (maxY - minY)
        pipes += Pipe(x, gapY)
        coinList += Coin(x + PIPE_W / 2f, gapY)                                           // a coin in the gap
        coinList += Coin(x + PIPE_W + (SPACING - PIPE_W) / 2f, minY + rnd.nextFloat() * (maxY - minY))   // one between pillars
    }

    /** a tap: starts the run, and makes the car hop */
    fun flap() {
        if (phase == Phase.READY) phase = Phase.RUNNING
        if (phase == Phase.RUNNING) vy = FLAP_V
    }

    /** advances the game by [dt] seconds */
    fun step(dt: Float) {
        if (phase != Phase.RUNNING) return
        vy += GRAVITY * dt
        carY += vy * dt
        val dx = speed * dt
        distance += dx
        for (p in pipes) p.x -= dx
        for (c in coinList) c.x -= dx
        pipes.removeAll { it.x + PIPE_W < -20f }
        coinList.removeAll { it.x < -40f }
        val last = pipes.maxOfOrNull { it.x } ?: 0f
        if (last < w) addPipe(last + SPACING)

        // coins: close enough to the middle of the car
        for (c in coinList) {
            if (!c.taken && hypot(c.x - carX, c.y - carY) < COIN_R + CAR_H * 0.55f) { c.taken = true; coins++ }
        }

        // the ceiling, the ground, a pillar: the run is over
        val top = carY - HIT_H / 2f; val bottom = carY + HIT_H / 2f
        val left = carX - HIT_W / 2f; val right = carX + HIT_W / 2f
        if (top < 0f || bottom > groundTop) { phase = Phase.OVER; return }
        for (p in pipes) {
            if (right > p.x && left < p.x + PIPE_W && (top < p.gapY - GAP / 2f || bottom > p.gapY + GAP / 2f)) { phase = Phase.OVER; return }
        }
    }

    companion object {
        const val CAR_W = 96f
        const val CAR_H = 46f
        const val HIT_W = 78f        // the part of the car that counts for touching (a bit smaller than it looks)
        const val HIT_H = 34f
        const val CAR_X_FRACTION = 0.26f
        const val GRAVITY = 1500f
        const val FLAP_V = -430f
        const val BASE_SPEED = 190f
        const val MAX_SPEED = 290f
        const val PIPE_W = 72f
        const val GAP = 190f
        const val SPACING = 330f
        const val GROUND_H = 44f
        const val COIN_R = 15f
    }
}
