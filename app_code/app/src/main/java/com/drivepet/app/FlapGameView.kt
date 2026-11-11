package com.drivepet.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import kotlin.math.max

/**
 * The mini game screen: a side view of a car with the pet at the wheel, hopping over pillars and picking up coins.
 * Everything that decides the game is in [FlapGame]; this view draws it, feeds it taps and time, and reports the result.
 *
 * Drawn in the app icon's world: a soft sky with clouds, a lavender city and round trees moving at different speeds,
 * the periwinkle car with the bear driving. The car and the coin never change, so they are drawn once into bitmaps.
 * The obstacles keep exactly the size and place the game uses for touching.
 */
class FlapGameView(context: Context, private val onExit: () -> Unit, private val onFinished: (coins: Int) -> Int) : View(context) {
    private var game: FlapGame? = null
    private var lastNs = 0L
    private var running = false
    private var reported = false
    private var given = 0
    private val ink = Ink()
    private val disp: Typeface = Look.display(context)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = disp }
    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val blit = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var scrollX = 0f
    private var scale = 1f

    // made once per size
    private var sky: Shader? = null
    private var road: Shader? = null
    private var carBmp: Bitmap? = null
    private var coinBmp: Bitmap? = null
    // made once, in the obstacle's own coordinates (the canvas is moved to each obstacle)
    private val signShader = LinearGradient(0f, 0f, 0f, 70f, 0xFF4FB37E.toInt(), 0xFF2E8C5C.toInt(), Shader.TileMode.CLAMP)
    private val postShader = LinearGradient(0f, 0f, FlapGame.PIPE_W * 0.1f, 0f, 0xFFB7C2D2.toInt(), 0xFF8E9BB0.toInt(), Shader.TileMode.CLAMP)
    private val towerShaders = arrayOf(
        LinearGradient(0f, 0f, FlapGame.PIPE_W, 0f, 0xFFF6BE97.toInt(), 0xFFEFA57A.toInt(), Shader.TileMode.CLAMP),
        LinearGradient(0f, 0f, FlapGame.PIPE_W, 0f, 0xFFB5C7EE.toInt(), 0xFF97AEE2.toInt(), Shader.TileMode.CLAMP),
    )

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(t: Long) {
            if (!running) return
            val dt = if (lastNs == 0L) 0f else ((t - lastNs) / 1e9f).coerceAtMost(0.05f)
            lastNs = t
            val g = game
            if (g != null) {
                g.step(dt)
                scrollX += g.speed * dt
                if (g.phase == FlapGame.Phase.OVER && !reported) { reported = true; given = onFinished(g.coins) }
            }
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun start() { running = true; lastNs = 0L; Choreographer.getInstance().postFrameCallback(frame) }
    fun stop() { running = false }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        // the game world is always 575 units high (the height of the app area on the reference display); a different display only scales it
        if (w > 0 && h > 0) { scale = h / WORLD_H; game = FlapGame(w / scale, WORLD_H, System.nanoTime()); reported = false }
        sky = null; road = null
        carBmp?.recycle(); carBmp = null
        coinBmp?.recycle(); coinBmp = null
    }

    override fun onDetachedFromWindow() {
        carBmp?.recycle(); carBmp = null; coinBmp?.recycle(); coinBmp = null
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action != MotionEvent.ACTION_DOWN) return true
        val g = game ?: return true
        val d = resources.displayMetrics.density
        // the close button, top left
        if (e.x < 120 * d && e.y < 70 * d) { onExit(); return true }
        if (g.phase == FlapGame.Phase.OVER) {
            // lower buttons: again / leave
            if (e.x > width / 2f) { onExit() } else { g.reset(); reported = false; given = 0 }
            return true
        }
        g.flap()
        return true
    }

    private companion object {
        /** the places on the road signs: a trip round the whole country (all 17 regions), starting from Seoul */
        val TRIP = listOf("서울", "인천", "경기", "강원", "충북", "세종", "대전", "충남", "전북", "광주", "전남", "제주", "경남", "부산", "울산", "대구", "경북")
        const val WORLD_H = 575f
        // the car bitmap covers this box around the car's centre (game units)
        const val CAR_L = -76f; const val CAR_T = -63f; const val CAR_R = 78f; const val CAR_B = 52f
        const val COIN_BOX = 20f
    }

    override fun onDraw(cv: Canvas) {
        val g = game ?: return
        ensureSprites()
        cv.save(); cv.scale(scale, scale)
        val groundTop = g.groundTop
        if (sky == null) sky = LinearGradient(0f, 0f, 0f, groundTop, intArrayOf(0xFF8EBAF6.toInt(), 0xFFC7DDFB.toInt(), 0xFFE3EEFD.toInt()), floatArrayOf(0f, 0.75f, 1f), Shader.TileMode.CLAMP)
        ink.fill.color = OPAQUE; ink.fill.shader = sky; cv.drawRect(0f, 0f, g.w, g.h, ink.fill); ink.fill.shader = null

        // three layers moving at different speeds: clouds, a far lavender city, round trees
        for (i in -1..5) {
            val cx = i * 300f - (scrollX * 0.12f) % 300f + 60f
            Pics.cloud(cv, ink, cx, 120f + (Math.floorMod(i, 2)) * 52f, 34f + Math.floorMod(i, 3) * 8f, Look.WHITE, 0xB3C8D8F2.toInt())
        }
        for (i in -1..9) {
            val bx = i * 130f - (scrollX * 0.25f) % 130f
            val hgt = 70f + Math.floorMod(i * 53, 5) * 22f
            rect.set(bx, groundTop - hgt, bx + 74f, groundTop + 4f)
            ink.fill.color = if (Math.floorMod(i, 2) == 1) 0xBFA9BDE6.toInt() else 0xBFB9CAEC.toInt()
            cv.drawRoundRect(rect, 6f, 6f, ink.fill)
            ink.fill.color = 0xCCE8F0FC.toInt()
            val rows = (hgt / 26f).toInt()
            for (r in 0 until rows) for (c in 0 until 2) { val wx = bx + 14f + c * 28f; val wy = groundTop - hgt + 12f + r * 26f; cv.drawRect(wx, wy, wx + 16f, wy + 10f, ink.fill) }
        }
        for (i in -1..12) {
            val tx = i * 96f - (scrollX * 0.5f) % 96f; val r = 24f + Math.floorMod(i, 3) * 6f
            ink.circle(cv, tx, groundTop - r * 0.4f, r, 0xFF7FB57A.toInt())
            ink.circle(cv, tx - r * 0.3f, groundTop - r * 0.75f, r * 0.62f, 0xFFA2C97F.toInt())
        }

        // obstacles: a road sign hanging from above, and a building rising from the ground
        for (p in g.pipes) {
            val topEnd = p.gapY - FlapGame.GAP / 2f
            val botStart = p.gapY + FlapGame.GAP / 2f
            val sx = p.x; val sw = FlapGame.PIPE_W
            // sign: two posts and a green board
            for (px in floatArrayOf(sx + sw * 0.18f, sx + sw * 0.72f)) {
                cv.save(); cv.translate(px, 0f)
                rect.set(0f, -6f, sw * 0.1f, topEnd - 40f)
                ink.fill.color = OPAQUE; ink.fill.shader = postShader; cv.drawRoundRect(rect, 3f, 3f, ink.fill); ink.fill.shader = null
                ink.line.color = 0xFF4F5B70.toInt(); ink.line.strokeWidth = 1.4f; cv.drawRoundRect(rect, 3f, 3f, ink.line)
                cv.restore()
            }
            cv.save(); cv.translate(0f, topEnd - 70f)
            rect.set(sx - 18f, 0f, sx + sw + 18f, 70f)
            ink.fill.color = OPAQUE; ink.fill.shader = signShader; cv.drawRoundRect(rect, 14f, 14f, ink.fill); ink.fill.shader = null
            ink.line.color = 0xFF1F5E3E.toInt(); ink.line.strokeWidth = 2f; cv.drawRoundRect(rect, 14f, 14f, ink.line)
            rect.set(sx - 12f, 6f, sx + sw + 12f, 64f); ink.line.color = Look.WHITE; ink.line.strokeWidth = 2.6f; cv.drawRoundRect(rect, 10f, 10f, ink.line)
            cv.restore()
            txt.color = Look.WHITE; txt.textSize = 25f; txt.textAlign = Paint.Align.CENTER
            // the signs go round the whole country, one place after another, so every region shows up on the way
            val place = TRIP[p.n % TRIP.size]
            cv.drawText(if (p.n % 2 == 0) "→ $place" else "$place ←", sx + sw / 2f, topEnd - 25f, txt)
            // building: pastel body with a cocoa line, a roof cap and lit windows
            cv.save(); cv.translate(sx, 0f)
            rect.set(-4f, botStart, sw + 4f, groundTop + 4f)
            ink.fill.color = OPAQUE; ink.fill.shader = towerShaders[p.n % 2]; cv.drawRoundRect(rect, 8f, 8f, ink.fill); ink.fill.shader = null
            ink.line.color = Look.COCOA; ink.line.strokeWidth = 2f; cv.drawRoundRect(rect, 8f, 8f, ink.line)
            rect.set(-10f, botStart - 14f, sw + 10f, botStart + 2f)
            ink.fill.color = if (p.n % 2 == 0) 0xFFD9825A.toInt() else 0xFF6F86C8.toInt(); cv.drawRoundRect(rect, 7f, 7f, ink.fill); cv.drawRoundRect(rect, 7f, 7f, ink.line)
            var wy = botStart + 18f
            ink.line.color = 0x8C4A3426.toInt(); ink.line.strokeWidth = 1.2f
            while (wy + 20f < groundTop - 8f) {
                for (wx in floatArrayOf(9f, sw - 29f)) { rect.set(wx, wy, wx + 20f, wy + 20f); ink.fill.color = 0xFFFFF4CF.toInt(); cv.drawRoundRect(rect, 4f, 4f, ink.fill); cv.drawRoundRect(rect, 4f, 4f, ink.line) }
                wy += 38f
            }
            cv.restore()
        }
        // coins
        coinBmp?.let { b -> for (co in g.coinList) { if (co.taken) continue; rect.set(co.x - COIN_BOX, co.y - COIN_BOX, co.x + COIN_BOX, co.y + COIN_BOX); cv.drawBitmap(b, null, rect, blit) } }
        // the road
        ink.fill.color = 0xFFEDE6DD.toInt(); cv.drawRect(0f, groundTop, g.w, groundTop + 8f, ink.fill)
        if (road == null) road = LinearGradient(0f, groundTop + 8f, 0f, g.h, 0xFFCFC7C2.toInt(), 0xFFB9B0AB.toInt(), Shader.TileMode.CLAMP)
        ink.fill.color = OPAQUE; ink.fill.shader = road; cv.drawRect(0f, groundTop + 8f, g.w, g.h, ink.fill); ink.fill.shader = null
        ink.line.color = Look.WHITE; ink.line.strokeWidth = 4f
        var x = -(scrollX % 60f)
        while (x < g.w) { cv.drawLine(x, groundTop + 28f, x + 28f, groundTop + 28f, ink.line); x += 60f }
        ink.fill.color = 0x40785A3C; cv.drawRect(0f, groundTop + 7f, g.w, groundTop + 8.5f, ink.fill)

        carBmp?.let { b ->
            cv.save(); cv.translate(g.carX, g.carY); cv.rotate((g.vy / 900f).coerceIn(-0.35f, 0.45f) * 25f)
            rect.set(CAR_L, CAR_T, CAR_R, CAR_B); cv.drawBitmap(b, null, rect, blit)
            cv.restore()
        }

        // top bar: leave, and the coins so far
        pill(cv, 16f, 14f, 118f, 46f)
        ink.line.color = Look.COCOA; ink.line.strokeWidth = 2.6f; cv.drawLine(32f, 31f, 44f, 43f, ink.line); cv.drawLine(44f, 31f, 32f, 43f, ink.line)
        txt.color = Look.INK; txt.textSize = 18f; txt.textAlign = Paint.Align.LEFT; cv.drawText("나가기", 56f, 44f, txt)
        val ct = "코인 ${g.coins}"
        txt.textSize = 24f; val cw = txt.measureText(ct) + 66f
        pill(cv, g.w / 2f - cw / 2f, 12f, cw, 50f)
        coinBmp?.let { b -> rect.set(g.w / 2f - cw / 2f + 27f - 16f, 37f - 16f, g.w / 2f - cw / 2f + 27f + 16f, 37f + 16f); cv.drawBitmap(b, null, rect, blit) }
        txt.color = Look.INK; cv.drawText(ct, g.w / 2f - cw / 2f + 50f, 46f, txt)
        txt.textAlign = Paint.Align.CENTER

        when (g.phase) {
            FlapGame.Phase.READY -> {
                card(cv, g.w / 2f - 240f, g.h * 0.29f, 480f, 92f, 26f, 0xF2FFFCF6.toInt())
                txt.textSize = 25f; txt.color = Look.INK
                cv.drawText("화면을 톡 치면 차가 뛰어올라요", g.w / 2f, g.h * 0.29f + 40f, txt)
                body.textSize = 14f; body.color = Look.SUB
                cv.drawText("표지판과 건물을 피해 코인을 모으면 포인트가 돼요", g.w / 2f, g.h * 0.29f + 70f, body)
            }
            FlapGame.Phase.OVER -> drawResult(cv, g)
            else -> {}
        }
        cv.restore()
    }

    private fun pill(cv: Canvas, x: Float, y: Float, w: Float, h: Float) = card(cv, x, y, w, h, h / 2f, 0xF5FFF9F1.toInt())

    private fun card(cv: Canvas, x: Float, y: Float, w: Float, h: Float, r: Float, color: Int) {
        rect.set(x, y + 4f, x + w, y + h + 4f); ink.fill.color = 0x1F7A5434; cv.drawRoundRect(rect, r, r, ink.fill)
        rect.set(x - 1f, y + 1f, x + w + 1f, y + h + 2f); ink.fill.color = 0x147A5434; cv.drawRoundRect(rect, r, r, ink.fill)
        rect.set(x, y, x + w, y + h); ink.fill.color = color; cv.drawRoundRect(rect, r, r, ink.fill)
        ink.line.color = Look.LINE; ink.line.strokeWidth = 1.2f; cv.drawRoundRect(rect, r, r, ink.line)
    }

    private fun drawResult(cv: Canvas, g: FlapGame) {
        ink.fill.color = 0x6B3C2819; cv.drawRect(0f, 0f, g.w, g.h, ink.fill)
        val w = 460f; val h = 290f
        val x0 = g.w / 2f - w / 2f; val y0 = g.h / 2f - h / 2f
        rect.set(x0, y0 + 12f, x0 + w, y0 + h + 12f); ink.fill.color = 0x40281909; cv.drawRoundRect(rect, 32f, 32f, ink.fill)
        card(cv, x0, y0, w, h, 32f, 0xFFFFFCF6.toInt())
        // the title with a coin in front of it
        val title = "코인 ${g.coins}개"
        txt.textSize = 36f; txt.color = Look.HONEY_DEEP; txt.textAlign = Paint.Align.LEFT
        val tw = txt.measureText(title); val tx = g.w / 2f - (tw + 48f) / 2f
        coinBmp?.let { b -> rect.set(tx, y0 + 62f - 22f, tx + 44f, y0 + 62f + 22f); cv.drawBitmap(b, null, rect, blit) }
        cv.drawText(title, tx + 48f, y0 + 76f, txt)
        txt.textAlign = Paint.Align.CENTER
        body.textSize = 20f; body.color = Look.INK
        val msg = if (given > 0) "+${given}P 받았어요" else if (g.coins > 0) "오늘 받을 수 있는 포인트를 다 받았어요" else "다음엔 코인을 모아 봐요"
        cv.drawText(msg, g.w / 2f, y0 + 133f, body)
        // buttons: again (left), leave (right), the halves the touch handling uses
        val bottom = y0 + h
        key(cv, x0 + 30f, bottom - 96f, w / 2f - 40f, 64f, "다시", primary = true)
        key(cv, g.w / 2f + 10f, bottom - 94f, w / 2f - 40f, 62f, "나가기", primary = false)
    }

    private fun key(cv: Canvas, x: Float, y: Float, w: Float, h: Float, label: String, primary: Boolean) {
        val lip = if (primary) 3.5f else 2.5f; val r = (h - lip) / 2f
        rect.set(x, y + lip, x + w, y + h); ink.fill.color = if (primary) Look.PERI_LIP else 0xFFD4DDF6.toInt(); cv.drawRoundRect(rect, r, r, ink.fill)
        rect.set(x, y, x + w, y + h - lip)
        if (primary) { ink.fill.color = OPAQUE; ink.fill.shader = LinearGradient(0f, y, 0f, y + h - lip, Look.PERI_TOP, Look.PERI_BOT, Shader.TileMode.CLAMP); cv.drawRoundRect(rect, r, r, ink.fill); ink.fill.shader = null }
        else { ink.fill.color = Look.WHITE; cv.drawRoundRect(rect, r, r, ink.fill); ink.line.color = 0xFFAFC1F2.toInt(); ink.line.strokeWidth = 1.6f; cv.drawRoundRect(rect, r, r, ink.line) }
        txt.textSize = 22f; txt.color = if (primary) Look.WHITE else Look.PERI_DEEP; txt.textAlign = Paint.Align.CENTER
        cv.drawText(label, x + w / 2f, y + (h - lip) / 2f + 8f, txt)
    }

    // ---------------------------------------------------------------- sprites, drawn once per size

    private fun ensureSprites() {
        val over = max(2f, scale * 2f)
        if (carBmp == null) {
            val b = Bitmap.createBitmap(((CAR_R - CAR_L) * over).toInt(), ((CAR_B - CAR_T) * over).toInt(), Bitmap.Config.ARGB_8888)
            val c = Canvas(b); c.scale(over, over); c.translate(-CAR_L, -CAR_T); drawCar(c)
            carBmp = b
        }
        if (coinBmp == null) {
            val b = Bitmap.createBitmap((COIN_BOX * 2 * over).toInt(), (COIN_BOX * 2 * over).toInt(), Bitmap.Config.ARGB_8888)
            val c = Canvas(b); c.scale(over, over)
            Pics.coin(c, ink, COIN_BOX, COIN_BOX, FlapGame.COIN_R, disp)
            Pics.sparkle(c, ink, COIN_BOX + 13f, COIN_BOX - 13f, 4f, 0xE6FFFFFF.toInt())
            coinBmp = b
        }
    }

    /** the open periwinkle car of the icon from the side, with the bear at the wheel; centred on (0, 0) */
    private fun drawCar(cv: Canvas) {
        cv.save(); cv.scale(1.45f, 1.45f)
        val ow = 1.7f; val nav = Look.NAVY_LINE
        val col = Look.body(PetState.NORMAL)
        // ears and head (behind the door)
        for ((ex, ey) in arrayOf(-23f to -30f, 7f to -33f)) {
            ink.circle(cv, ex, ey, 7.5f, col.base); ink.circleOutline(cv, ex, ey, 7.5f, Look.COCOA, ow)
            ink.circle(cv, ex, ey - 0.6f, 3.6f, Look.EAR_IN)
        }
        cv.save(); cv.rotate(-3.4f, -6f, -14f)
        rect.set(-31f, -34f, 19f, 6f)
        ink.fill.color = OPAQUE; ink.fill.shader = RadialGradient(-14f, -24f, 30f, intArrayOf(col.light, col.base, col.shade), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        cv.drawOval(rect, ink.fill); ink.fill.shader = null
        ink.line.color = Look.COCOA; ink.line.strokeWidth = ow; cv.drawOval(rect, ink.line)
        cv.restore()
        for ((bx, by) in arrayOf(-9f to -6f, 17f to -8f)) {
            ink.fill.color = OPAQUE; ink.fill.shader = RadialGradient(bx, by, 6f, Look.alpha(Look.BLUSH, 0.85f), Look.alpha(Look.BLUSH, 0f), Shader.TileMode.CLAMP)
            rect.set(bx - 6f, by - 4.6f, bx + 6f, by + 4.6f); cv.drawOval(rect, ink.fill); ink.fill.shader = null
        }
        ink.line.color = Look.COCOA; ink.line.strokeWidth = 2f
        for (ex in floatArrayOf(-1f, 12f)) { rect.set(ex - 3.6f, -13f - 3.6f, ex + 3.6f, -13f + 3.6f); cv.drawArc(rect, 212f, 116f, false, ink.line) }
        ink.line.strokeWidth = 1.7f; rect.set(6f - 3.6f, -9f - 3.6f, 6f + 3.6f, -9f + 3.6f); cv.drawArc(rect, 36f, 108f, false, ink.line)
        // the steering wheel and two paws on it
        ink.line.color = 0xFF4A3A35.toInt(); ink.line.strokeWidth = 3f
        cv.save(); cv.rotate(23f, 16f, -2f); rect.set(16f - 6f, -2f - 8f, 16f + 6f, -2f + 8f); cv.drawArc(rect, 198f, 180f, false, ink.line); cv.restore()
        for ((px, py) in arrayOf(11f to -3f, 22f to -5f)) { ink.oval(cv, px, py, 4.6f, 3.8f, col.base); ink.ovalOutline(cv, px, py, 4.6f, 3.8f, Look.COCOA, ow * 0.9f) }
        // the open car body
        val p = ink.path; p.reset()
        p.moveTo(-47f, 6f); p.quadTo(-48f, -5f, -38f, -5f); p.lineTo(-18f, -5f); p.quadTo(-14f, 1f, -6f, 1f); p.lineTo(12f, 1f)
        p.quadTo(16f, -3f, 22f, -2f); p.quadTo(40f, 0f, 46f, 6f); p.quadTo(50f, 12f, 47f, 18f); p.quadTo(45f, 23f, 38f, 23f); p.lineTo(-40f, 23f); p.quadTo(-48f, 23f, -48f, 15f); p.close()
        ink.fillPath(cv, p, LinearGradient(0f, -5f, 0f, 23f, intArrayOf(0xFFA7C0F4.toInt(), 0xFF7F9FEA.toInt(), 0xFF5878D8.toInt()), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP))
        cv.save(); cv.clipPath(p)
        ink.line.color = 0x8CFFFFFF.toInt(); ink.line.strokeWidth = 2.6f; ink.path2.reset(); ink.path2.moveTo(-36f, 3f); ink.path2.quadTo(0f, 5f, 30f, 5f); cv.drawPath(ink.path2, ink.line)
        for (wx in floatArrayOf(-25f, 27f)) ink.circle(cv, wx, 24f, 12.5f, 0x40283C82)
        cv.restore()
        ink.stroke(cv, p, nav, ow)
        ink.line.color = Look.alpha(nav, 0.45f); ink.line.strokeWidth = 1.2f; cv.drawLine(-16f, 3f, -16f, 18f, ink.line)
        // windshield
        ink.line.color = nav; ink.line.strokeWidth = 3.6f; cv.drawLine(22f, -1f, 28f, -15f, ink.line)
        ink.line.color = 0xFFDCEBFF.toInt(); ink.line.strokeWidth = 1.8f; cv.drawLine(22f, -1f, 28f, -15f, ink.line)
        // lights
        ink.circle(cv, 43f, 9f, 3.8f, 0xFFFFFBEA.toInt()); ink.circleOutline(cv, 43f, 9f, 3.8f, nav, 1.2f)
        ink.circle(cv, -45.5f, 10f, 2.4f, 0xFFF7897A.toInt()); ink.circleOutline(cv, -45.5f, 10f, 2.4f, nav, 1f)
        // wheels
        for (wx in floatArrayOf(-25f, 27f)) {
            ink.circle(cv, wx, 23f, 9.5f, 0xFF4A4245.toInt()); ink.circleOutline(cv, wx, 23f, 9.5f, 0xFF2B2326.toInt(), ow)
            ink.circle(cv, wx, 23f, 4f, 0xFFD7DCE6.toInt()); ink.circleOutline(cv, wx, 23f, 4f, 0xFF2B2326.toInt(), 1f)
            ink.circle(cv, wx - 3f, 19.5f, 1.6f, 0x80FFFFFF.toInt())
        }
        cv.restore()
    }

    @Suppress("unused") private fun c(hex: String) = Color.parseColor(hex)
}
