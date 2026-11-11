package com.drivepet.app

import android.graphics.Canvas

/** fruit, crops and the things each region makes */
internal object ArtGoods : ArtKit() {

    fun draw(cv: Canvas, id: String): Boolean {
        when (id) {
            "daegu.apple" -> { fruit(cv, 0xFFE0443A); curve(cv, 0x55FFFFFF, 0.04f, -0.2f, -0.45f, 0f, -0.52f, 0.2f, -0.45f) }
            "chungbuk.apple" -> { fruit(cv, 0xFFE86A3A); ov(cv, 0.1f, -0.1f, 0.6f, 0.5f, 0x55F7D774, 0f) }
            "daegu.herb" -> herbs(cv)
            "incheon.ginseng", "chungnam.ginseng" -> ginseng(cv)
            "gwangju.melon" -> melon(cv)
            "gwangju.kimchi" -> kimchi(cv)
            "ulsan.pear" -> { fruit(cv, 0xFFE2B96A, 0.75f, 0.12f); for (k in 0..11) dot(cv, -0.45f + (k % 4) * 0.3f, -0.25f + (k / 4) * 0.28f, 0.025f, 0x88A07A3A) }
            "sejong.peach" -> peach(cv)
            "sejong.brush" -> brush(cv)
            "gyeonggi.bean" -> bean(cv)
            "gyeonggi.pinenut" -> pinecone(cv)
            "gangwon.potato" -> { for ((x, y, s) in listOf(Triple(-0.3f, 0.2f, 0.5f), Triple(0.32f, 0.3f, 0.42f), Triple(0.05f, -0.25f, 0.4f))) { ov(cv, x - s, y - s * 0.75f, x + s, y + s * 0.75f, 0xFFC99A5E); for (k in 0..2) dot(cv, x - s * 0.4f + k * s * 0.4f, y - s * 0.1f + (k % 2) * s * 0.3f, 0.03f, 0xFF8A6034) } }
            "gangwon.hanwoo" -> cow(cv)
            "chungbuk.grape" -> grapes(cv, 0xFF7A4BB0, 0.17f)
            "chungnam.strawberry" -> strawberry(cv)
            "chungnam.mud" -> mud(cv)
            "jeonbuk.cheese" -> { poly(-0.85f, 0.0f, 0.85f, 0.0f, 0.85f, 0.6f, -0.85f, 0.6f); sh(cv, p, 0xFFF2C94C); poly(-0.85f, 0.0f, 0.85f, 0.0f, -0.5f, -0.6f); sh(cv, p, 0xFFFFE38A); for ((x, y, rad) in listOf(Triple(-0.45f, 0.3f, 0.13f), Triple(0.1f, 0.22f, 0.09f), Triple(0.5f, 0.4f, 0.11f), Triple(-0.1f, 0.47f, 0.06f), Triple(-0.4f, -0.2f, 0.07f))) ci(cv, x, y, rad, 0xFFD9A830, 0.03f) }
            "jeonbuk.bokbunja" -> grapes(cv, 0xFF5A1E4A, 0.13f)
            "jeonbuk.gochujang" -> jar(cv)
            "jeonnam.tea" -> tea(cv)
            "gyeongbuk.garlic" -> garlic(cv)
            "gyeongbuk.persimmon" -> persimmons(cv)
            "gyeongnam.yuja" -> { fruit(cv, 0xFFF4D23A, 0.72f, 0.15f, 0xFF3F9A4F); for (k in 0..13) dot(cv, -0.45f + (k % 5) * 0.22f, -0.3f + (k / 5) * 0.3f, 0.03f, 0x66B88A10) }
            "jeju.hallabong" -> { ci(cv, 0f, 0.2f, 0.72f, 0xFFF59A2A); p.reset(); p.moveTo(-0.28f, -0.45f); p.quadTo(-0.3f, -0.78f, 0f, -0.8f); p.quadTo(0.3f, -0.78f, 0.28f, -0.45f); p.close(); sh(cv, p, 0xFFF59A2A); ov(cv, -0.45f, -0.25f, -0.1f, 0.05f, 0x66FFFFFF, 0f); ln(cv, 0f, -0.8f, 0.06f, -0.95f, 0xFF7A5A3C, 0.07f); leaf(cv, 0.05f, -0.88f, 0.42f, 0.15f, -15f, 0xFF3F9A4F); for (k in 0..9) dot(cv, -0.4f + (k % 4) * 0.27f, -0.05f + (k / 4) * 0.28f, 0.025f, 0x66B85A10) }
            else -> return false
        }
        return true
    }

    private fun herbs(cv: Canvas) {
        ov(cv, -0.95f, -0.15f, 0.95f, 0.65f, 0xFFD9B98A); ov(cv, -0.8f, -0.08f, 0.8f, 0.5f, 0xFFC9A46A, 0.03f)   // a woven sieve
        for (k in 0..6) ln(cv, -0.7f + k * 0.23f, -0.05f, -0.6f + k * 0.2f, 0.48f, 0x55704A20, 0.025f)
        for ((x, y) in listOf(-0.4f to 0.1f, 0.1f to 0.05f, 0.45f to 0.2f, -0.1f to 0.28f)) { ci(cv, x, y, 0.13f, 0xFFF0E2C0, 0.03f); for (k in 0..3) ln(cv, x, y, x + 0.1f * kotlin.math.cos(k * 1.57f), y + 0.1f * kotlin.math.sin(k * 1.57f), 0x66A0784A, 0.02f) }
        for (k in 0..2) { val x = -0.5f + k * 0.45f; rot(cv, 20f - k * 20f, x, -0.15f) { rr(cv, x - 0.2f, -0.2f, x + 0.25f, -0.1f, 0.04f, 0xFF8A5A2A, 0.03f) } }
        leaf(cv, -0.2f, -0.35f, 0.35f, 0.12f, -40f, 0xFF7CC66B); leaf(cv, 0.15f, -0.32f, 0.35f, 0.12f, -130f, 0xFF7CC66B)
    }

    private fun ginseng(cv: Canvas) {
        for (k in -2..2) leaf(cv, 0f, -0.55f, 0.38f, 0.13f, -90f + k * 34f, 0xFF4CAF50)
        for (k in -1..1) dot(cv, k * 0.08f, -0.62f, 0.05f, 0xFFE0442B)
        ln(cv, 0f, -0.55f, 0f, -0.3f, 0xFF6A8A3A, 0.05f)
        p.reset(); p.moveTo(-0.14f, -0.32f); p.quadTo(0.14f, -0.36f, 0.16f, -0.25f); p.quadTo(0.24f, 0.2f, 0.12f, 0.35f); p.lineTo(-0.12f, 0.35f); p.quadTo(-0.24f, 0.2f, -0.14f, -0.32f); p.close(); sh(cv, p, 0xFFF2DDB0)
        curve(cv, 0xFFE8CF9A, 0.08f, -0.06f, 0.3f, -0.3f, 0.6f, -0.35f, 0.9f); curve(cv, 0xFFE8CF9A, 0.08f, 0.08f, 0.3f, 0.25f, 0.6f, 0.4f, 0.85f)
        curve(cv, 0xFFE8CF9A, 0.05f, -0.12f, 0f, -0.45f, 0.05f, -0.6f, 0.25f); curve(cv, 0xFFE8CF9A, 0.05f, 0.14f, 0.05f, 0.45f, 0.05f, 0.62f, 0.25f)
        for (k in 0..3) ln(cv, -0.1f, -0.15f + k * 0.12f, 0.1f, -0.13f + k * 0.12f, 0x66A07A3A, 0.02f)
    }

    private fun melon(cv: Canvas) {
        ci(cv, -0.15f, 0.05f, 0.72f, 0xFF3E8E3A)
        for (k in -2..2) curve(cv, 0xFF1F5A20, 0.07f, -0.15f + k * 0.26f, -0.62f, -0.15f + k * 0.34f, 0.05f, -0.15f + k * 0.26f, 0.72f)
        ln(cv, -0.15f, -0.67f, -0.08f, -0.82f, 0xFF7A5A3C, 0.06f)
        p.reset(); p.moveTo(0.1f, 0.85f); p.lineTo(0.95f, 0.85f); p.quadTo(0.95f, 0.1f, 0.52f, 0.1f); p.close(); sh(cv, p, 0xFFE8473C)   // a slice in front
        p.reset(); p.moveTo(0.1f, 0.85f); p.lineTo(0.95f, 0.85f); l.color = col(0xFF3E8E3A); l.strokeWidth = 0.09f; cv.drawPath(p, l)
        for ((x, y) in listOf(0.55f to 0.45f, 0.72f to 0.6f, 0.42f to 0.66f, 0.8f to 0.35f)) ov(cv, x - 0.03f, y - 0.05f, x + 0.03f, y + 0.05f, 0xFF2A1A14, 0f)
    }

    private fun kimchi(cv: Canvas) {
        plate(cv, 0xFFFFFCF6, 0xFF6A9BE0)
        for ((x, y, a) in listOf(Triple(-0.3f, 0.05f, -20f), Triple(0.25f, 0f, 15f), Triple(0f, 0.22f, 0f))) rot(cv, a, x, y) {
            p.reset(); p.moveTo(x - 0.35f, y + 0.12f); p.quadTo(x - 0.3f, y - 0.3f, x, y - 0.32f); p.quadTo(x + 0.35f, y - 0.3f, x + 0.35f, y + 0.12f); p.quadTo(x, y + 0.22f, x - 0.35f, y + 0.12f); sh(cv, p, 0xFFE0503A, 0.04f)
            rr(cv, x - 0.25f, y - 0.02f, x + 0.25f, y + 0.12f, 0.06f, 0xFFF7D9B0, 0.03f)
        }
        for (k in 0..5) dot(cv, -0.45f + k * 0.18f, 0.32f + (k % 2) * 0.06f, 0.03f, 0xFFB0281A)
    }

    private fun peach(cv: Canvas) {
        p.reset(); p.moveTo(0f, -0.45f); p.cubicTo(-0.95f, -0.75f, -0.95f, 0.85f, 0f, 0.82f); p.cubicTo(0.95f, 0.85f, 0.95f, -0.75f, 0f, -0.45f); p.close(); sh(cv, p, 0xFFF9A9A0)
        ov(cv, 0.05f, -0.2f, 0.7f, 0.6f, 0x55F7C46A, 0f)
        curve(cv, 0xFFE07A80, 0.04f, 0f, -0.42f, -0.18f, 0.15f, 0.02f, 0.75f)
        ov(cv, -0.55f, -0.3f, -0.25f, -0.05f, 0x77FFFFFF, 0f)
        leaf(cv, 0.02f, -0.48f, 0.45f, 0.16f, -30f); leaf(cv, -0.02f, -0.48f, 0.38f, 0.14f, -150f)
    }

    private fun brush(cv: Canvas) {
        rr(cv, -0.92f, -0.1f, 0.92f, 0.82f, 0.06f, 0xFFFFF7E8)   // paper
        curve(cv, 0xFF2A2A2A, 0.13f, -0.6f, 0.2f, -0.2f, 0.12f, -0.15f, 0.55f)
        curve(cv, 0xFF2A2A2A, 0.11f, 0.05f, 0.18f, 0.3f, 0.2f, 0.35f, 0.65f)
        rot(cv, 38f, 0.3f, -0.3f) {
            rr(cv, -0.2f, -0.38f, 0.8f, -0.24f, 0.05f, 0xFFC9A04A); for (k in 0..3) ln(cv, -0.05f + k * 0.22f, -0.38f, -0.05f + k * 0.22f, -0.24f, 0xFF8A6A2A, 0.03f)
            p.reset(); p.moveTo(-0.2f, -0.38f); p.quadTo(-0.45f, -0.31f, -0.55f, -0.31f); p.quadTo(-0.45f, -0.31f, -0.2f, -0.24f); p.close(); sh(cv, p, 0xFF2A2A2A, 0.03f)
            ln(cv, 0.8f, -0.31f, 0.92f, -0.31f, 0xFFE0442B, 0.05f)
        }
    }

    private fun bean(cv: Canvas) {
        p.reset(); p.moveTo(-0.9f, 0.2f); p.quadTo(-0.6f, -0.45f, 0.1f, -0.35f); p.quadTo(0.8f, -0.3f, 0.92f, 0.1f); p.quadTo(0.5f, 0.55f, -0.1f, 0.5f); p.quadTo(-0.7f, 0.5f, -0.9f, 0.2f); p.close(); sh(cv, p, 0xFF7CB342)
        ov(cv, -0.72f, -0.15f, 0.75f, 0.3f, 0xFFB7D88A, 0.03f)
        for (k in 0..3) { val x = -0.48f + k * 0.34f; ci(cv, x, 0.06f, 0.17f, 0xFFF0DCA0); ov(cv, x - 0.08f, -0.06f, x, 0.02f, 0x88FFFFFF, 0f) }
        for ((x, y) in listOf(-0.2f to 0.72f, 0.2f to 0.7f, 0.55f to 0.68f)) ci(cv, x, y, 0.13f, 0xFFF0DCA0)
    }

    private fun pinecone(cv: Canvas) {
        p.reset(); p.moveTo(0f, -0.85f); p.cubicTo(0.75f, -0.6f, 0.65f, 0.55f, 0f, 0.75f); p.cubicTo(-0.65f, 0.55f, -0.75f, -0.6f, 0f, -0.85f); p.close(); sh(cv, p, 0xFF8A5A2A)
        for (row in 0..5) { val y = -0.6f + row * 0.24f; val n = if (row in 1..4) 4 else 3
            for (k in 0 until n) { val x = -0.3f * (n - 1) / 2f + k * 0.3f + (row % 2) * 0.05f; p.reset(); p.moveTo(x - 0.14f, y); p.quadTo(x, y + 0.2f, x + 0.14f, y); p.quadTo(x, y + 0.06f, x - 0.14f, y); sh(cv, p, 0xFFB07A3A, 0.03f) } }
        for ((x, y) in listOf(0.7f to 0.65f, 0.55f to 0.82f, 0.85f to 0.82f)) { ov(cv, x - 0.08f, y - 0.11f, x + 0.08f, y + 0.11f, 0xFFF4E6C8, 0.03f) }
    }

    private fun cow(cv: Canvas) {
        for (s in floatArrayOf(-1f, 1f)) { curve(cv, 0xFFFFF4D6, 0.1f, s * 0.38f, -0.5f, s * 0.6f, -0.65f, s * 0.55f, -0.88f); ov(cv, s * 0.55f - 0.18f, -0.45f, s * 0.55f + 0.18f, -0.25f, 0xFFB86A30) }
        ov(cv, -0.55f, -0.65f, 0.55f, 0.55f, 0xFFC97A3A)
        ov(cv, -0.42f, 0.1f, 0.42f, 0.72f, 0xFFF2C6A4)
        dot(cv, -0.15f, 0.4f, 0.06f, 0xFF8A4A26); dot(cv, 0.15f, 0.4f, 0.06f, 0xFF8A4A26)
        dot(cv, -0.22f, -0.15f, 0.07f, INK); dot(cv, 0.22f, -0.15f, 0.07f, INK); dot(cv, -0.2f, -0.18f, 0.025f, 0xFFFFFFFF); dot(cv, 0.24f, -0.18f, 0.025f, 0xFFFFFFFF)
        ov(cv, -0.12f, -0.62f, 0.12f, -0.4f, 0xFFB86A30, 0f)
        dot(cv, -0.38f, 0.0f, 0.06f, 0x66FF8A8A); dot(cv, 0.38f, 0.0f, 0.06f, 0x66FF8A8A)
    }

    private fun grapes(cv: Canvas, k: Long, rad: Float) {
        ln(cv, 0f, -0.75f, 0.05f, -0.92f, 0xFF7A5A3C, 0.07f); leaf(cv, 0.04f, -0.8f, 0.45f, 0.2f, -20f, 0xFF4CAF50)
        val rows = intArrayOf(4, 4, 3, 3, 2, 1)
        for ((i, n) in rows.withIndex()) for (j in 0 until n) { val x = (j - (n - 1) / 2f) * rad * 2f; val y = -0.55f + i * rad * 1.7f; ci(cv, x, y, rad, k, 0.035f); dot(cv, x - rad * 0.35f, y - rad * 0.35f, rad * 0.25f, 0x77FFFFFF) }
    }

    private fun strawberry(cv: Canvas) {
        p.reset(); p.moveTo(0f, 0.9f); p.cubicTo(-0.95f, 0.2f, -0.75f, -0.6f, 0f, -0.48f); p.cubicTo(0.75f, -0.6f, 0.95f, 0.2f, 0f, 0.9f); p.close(); sh(cv, p, 0xFFE8333F)
        for (k in 0..11) { val x = -0.42f + (k % 4) * 0.28f + (k / 4 % 2) * 0.12f; val y = -0.2f + (k / 4) * 0.3f; if (kotlin.math.abs(x) < 0.55f - (y + 0.2f) * 0.25f) ov(cv, x - 0.03f, y - 0.05f, x + 0.03f, y + 0.05f, 0xFFFFE07A, 0f) }
        for (k in -2..2) leaf(cv, 0f, -0.52f, 0.36f, 0.12f, -90f + k * 40f, 0xFF4CAF50)
        ov(cv, -0.45f, -0.3f, -0.2f, -0.05f, 0x66FFFFFF, 0f)
    }

    /** the Boryeong mud festival: a wooden tub of glossy mud with a big splash, on the beach, a festival flag behind */
    private fun mud(cv: Canvas) {
        ov(cv, -0.98f, 0.45f, 0.98f, 0.95f, 0xFFF2DDB0)                                   // the beach
        ln(cv, 0.62f, 0.55f, 0.62f, -0.9f, 0xFF7A5A3C, 0.05f)
        poly(0.62f, -0.9f, 0.98f, -0.78f, 0.62f, -0.62f); sh(cv, p, 0xFF5B8FF9, 0.035f)
        poly(0.62f, -0.62f, 0.92f, -0.52f, 0.62f, -0.42f); sh(cv, p, 0xFFF2C25A, 0.035f)
        p.reset(); p.moveTo(-0.75f, 0.0f); p.lineTo(-0.62f, 0.75f); p.quadTo(-0.1f, 0.88f, 0.42f, 0.75f); p.lineTo(0.55f, 0.0f); p.close(); sh(cv, p, 0xFFB07A44)   // the tub
        for (k in 0..3) ln(cv, -0.5f + k * 0.3f, 0.05f, -0.45f + k * 0.28f, 0.78f, 0x55603A1A, 0.03f)
        ln(cv, -0.7f, 0.35f, 0.5f, 0.35f, 0xFF7A4A20, 0.06f)
        ov(cv, -0.75f, -0.14f, 0.55f, 0.14f, 0xFF6E4A2E)                                   // the mud
        ov(cv, -0.5f, -0.07f, -0.1f, 0.03f, 0x66FFFFFF, 0f)
        for ((x, y, a) in listOf(Triple(-0.45f, -0.45f, -25f), Triple(-0.1f, -0.62f, 0f), Triple(0.25f, -0.45f, 25f))) rot(cv, a, x, y) {
            p.reset(); p.moveTo(x, y - 0.18f); p.quadTo(x + 0.13f, y + 0.02f, x, y + 0.1f); p.quadTo(x - 0.13f, y + 0.02f, x, y - 0.18f); p.close(); sh(cv, p, 0xFF6E4A2E, 0.035f)
            dot(cv, x - 0.03f, y - 0.02f, 0.025f, 0x88FFFFFF)
        }
        curve(cv, 0xFF6E4A2E, 0.07f, -0.3f, -0.1f, -0.15f, -0.32f, -0.1f, -0.4f)
        curve(cv, 0xFF6E4A2E, 0.07f, 0.1f, -0.1f, 0.0f, -0.3f, 0.12f, -0.32f)
    }

    private fun jar(cv: Canvas) {
        p.reset(); p.moveTo(-0.45f, -0.55f); p.cubicTo(-1.05f, -0.3f, -0.9f, 0.85f, 0f, 0.85f); p.cubicTo(0.9f, 0.85f, 1.05f, -0.3f, 0.45f, -0.55f); p.close(); sh(cv, p, 0xFF7A4A2A)
        ov(cv, -0.5f, -0.68f, 0.5f, -0.45f, 0xFFB0281A)
        p.reset(); p.moveTo(-0.3f, -0.6f); p.quadTo(0f, -0.85f, 0.3f, -0.6f); p.close(); sh(cv, p, 0xFFD8402A, 0.035f)
        curve(cv, 0x55FFFFFF, 0.06f, -0.6f, -0.1f, -0.65f, 0.3f, -0.45f, 0.6f)
        rr(cv, -0.32f, -0.05f, 0.32f, 0.35f, 0.05f, 0xFFF4E2C4, 0.035f); text(cv, "장", 0f, 0.25f, 0.3f, 0xFF7A2A1A)
    }

    private fun tea(cv: Canvas) {
        for (k in 0..2) { val y = 0.45f + k * 0.17f; curve(cv, 0xFF4C9A4A, 0.13f, -0.95f, y, 0f, y - 0.12f, 0.95f, y) }   // the tea field rows
        p.reset(); p.moveTo(-0.55f, -0.35f); p.lineTo(0.55f, -0.35f); p.quadTo(0.5f, 0.35f, 0f, 0.38f); p.quadTo(-0.5f, 0.35f, -0.55f, -0.35f); p.close(); sh(cv, p, 0xFFF4EEE0)
        ov(cv, -0.55f, -0.45f, 0.55f, -0.25f, 0xFF9CC85A)
        leaf(cv, 0.35f, -0.55f, 0.4f, 0.14f, -40f, 0xFF4CAF50); leaf(cv, 0.3f, -0.55f, 0.32f, 0.12f, -110f, 0xFF66BB6A)
        steam(cv, -0.2f, 0.1f)
    }

    private fun garlic(cv: Canvas) {
        p.reset(); p.moveTo(0f, -0.55f); p.cubicTo(-0.95f, -0.35f, -0.85f, 0.75f, 0f, 0.75f); p.cubicTo(0.85f, 0.75f, 0.95f, -0.35f, 0f, -0.55f); p.close(); sh(cv, p, 0xFFF8F2EA)
        for (k in -2..2) curve(cv, 0x66B07AA8, 0.035f, 0f, -0.5f, k * 0.35f, 0.1f, k * 0.12f, 0.72f)
        p.reset(); p.moveTo(-0.08f, -0.52f); p.quadTo(0f, -0.95f, 0.12f, -0.92f); p.quadTo(0.08f, -0.7f, 0.08f, -0.52f); p.close(); sh(cv, p, 0xFFF0E6D6, 0.035f)
        for (k in 0..4) ln(cv, -0.2f + k * 0.1f, 0.75f, -0.24f + k * 0.12f, 0.88f, 0xFFC9B8A0, 0.03f)
    }

    private fun persimmons(cv: Canvas) {
        ln(cv, 0f, -0.95f, 0f, 0.8f, 0xFFD9B98A, 0.04f)
        for (k in 0..2) { val y = -0.55f + k * 0.55f
            ov(cv, -0.32f, y - 0.22f, 0.32f, y + 0.24f, 0xFFC9662A)
            ov(cv, -0.22f, y - 0.12f, 0.05f, y + 0.08f, 0x55FFFFFF, 0f)
            for (j in 0..4) dot(cv, -0.2f + j * 0.1f, y + 0.12f - (j % 2) * 0.1f, 0.025f, 0xCCFFFFFF)
            poly(-0.12f, y - 0.25f, 0.12f, y - 0.25f, 0f, y - 0.16f); sh(cv, p, 0xFF5A7A3A, 0.025f) }
    }
}
