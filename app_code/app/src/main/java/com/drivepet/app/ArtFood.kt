package com.drivepet.app

import android.graphics.Canvas

/** the dishes and snacks of the collection */
internal object ArtFood : ArtKit() {

    fun draw(cv: Canvas, id: String): Boolean {
        when (id) {
            "seoul.chicken" -> chicken(cv)
            "busan.gukbap" -> { pot(cv, 0xFFF4EBDD); for ((x, y) in listOf(-0.4f to -0.14f, 0.05f to -0.2f, 0.42f to -0.1f, -0.1f to -0.02f)) { ov(cv, x - 0.22f, y - 0.09f, x + 0.22f, y + 0.09f, 0xFFF2C6B4, 0.035f); ln(cv, x - 0.16f, y - 0.03f, x + 0.16f, y - 0.03f, 0xFFFFE9DF, 0.03f) }; scallion(cv); steam(cv, -0.3f, 0.1f, 0.45f) }
            "busan.eomuk" -> eomuk(cv)
            "busan.hotteok" -> hotteok(cv)
            "daegu.makchang" -> { grill(cv); for ((x, y) in listOf(-0.42f to 0.05f, 0.05f to -0.02f, 0.45f to 0.12f, -0.15f to 0.28f, 0.3f to 0.32f)) { rot(cv, x * 60f, x, y) { rr(cv, x - 0.22f, y - 0.09f, x + 0.22f, y + 0.09f, 0.09f, 0xFFD98E5B, 0.04f); ln(cv, x - 0.14f, y, x + 0.14f, y, 0xFF8A4A26, 0.03f) } }; leaf(cv, 0.4f, -0.12f, 0.3f, 0.1f, -30f) }
            "daegu.napjak" -> { plate(cv); for ((x, y) in listOf(-0.32f to 0.08f, 0.22f to 0f, 0.05f to 0.25f)) { p.reset(); p.moveTo(x - 0.34f, y + 0.06f); p.quadTo(x, y - 0.32f, x + 0.34f, y + 0.06f); p.quadTo(x, y + 0.14f, x - 0.34f, y + 0.06f); sh(cv, p, 0xFFF2D6A2, 0.04f); ln(cv, x - 0.15f, y - 0.05f, x + 0.12f, y - 0.08f, 0xFFC98A44, 0.035f) }; for (k in 0..6) dot(cv, -0.4f + k * 0.13f, 0.05f + (k % 3) * 0.08f, 0.03f, if (k % 2 == 0) 0xFF5DB85C else 0xFFE0442B) }
            "incheon.jjajang" -> { bowl(cv, 0xFFFFFCF6, 0xFFF6D97A, 0xFFE0442B); noodles(cv, 0xFFE9C060); ov(cv, -0.55f, -0.3f, 0.55f, 0.02f, 0xFF3A2418, 0.04f); for (k in 0..3) rr(cv, -0.3f + k * 0.16f, -0.28f, -0.22f + k * 0.16f, -0.08f, 0.02f, 0xFF7CC66B, 0.025f) }
            "incheon.jjolmyeon" -> { bowl(cv, 0xFF3F6FD0, 0xFFF6D97A); noodles(cv, 0xFFF2D27A); ov(cv, -0.5f, -0.28f, 0.2f, 0.02f, 0xFFE0442B, 0.035f); for (k in 0..4) ln(cv, 0.1f + k * 0.08f, -0.26f, 0.2f + k * 0.08f, 0f, 0xFF8FD67B, 0.05f); egg(cv, -0.12f, -0.2f) }
            "gwangju.galbi" -> { plate(cv); for ((x, y) in listOf(-0.35f to 0.06f, 0.3f to 0.05f, -0.02f to 0.26f)) { ov(cv, x - 0.3f, y - 0.17f, x + 0.3f, y + 0.17f, 0xFF8A4A26); for (k in -1..1) ln(cv, x - 0.15f + k * 0.12f, y - 0.1f, x - 0.05f + k * 0.12f, y + 0.1f, 0xFF5A2A10, 0.035f) }; for (k in 0..4) dot(cv, -0.2f + k * 0.1f, -0.08f, 0.025f, 0xFFFFF4D6); leaf(cv, 0.45f, -0.15f, 0.3f, 0.1f, 20f) }
            "gwangju.duck" -> { pot(cv, 0xFFC98B4A); for ((x, y) in listOf(-0.38f to -0.12f, 0.15f to -0.18f, 0.4f to -0.06f)) ov(cv, x - 0.17f, y - 0.1f, x + 0.17f, y + 0.1f, 0xFFE8B98A, 0.035f); for (k in 0..3) leaf(cv, -0.55f + k * 0.3f, -0.02f, 0.25f, 0.08f, -15f + k * 12f, 0xFF4CAF50); steam(cv, -0.2f, 0.3f) }
            "daejeon.soboro" -> soboro(cv)
            "daejeon.kalguksu" -> { bowl(cv, 0xFFFFFCF6, 0xFFF1E3C6, 0xFF3F6FD0); for (k in 0..5) curve(cv, 0xFFFFF4D6, 0.07f, -0.7f + k * 0.08f, -0.18f + (k % 2) * 0.1f, -0.2f, -0.35f + k * 0.04f, 0.65f - k * 0.05f, -0.1f + (k % 3) * 0.05f); for ((x, y) in listOf(-0.3f to -0.15f, 0.25f to -0.08f)) ci(cv, x, y, 0.11f, 0xFFB7E08E, 0.03f); for (k in 0..4) ln(cv, 0.1f + k * 0.05f, -0.26f, 0.2f + k * 0.05f, -0.14f, 0xFF2E3B2A, 0.035f); steam(cv, -0.3f, 0.35f) }
            "daejeon.duruchigi" -> { grill(cv, true); ov(cv, -0.72f, -0.15f, 0.72f, 0.44f, 0xFFD8402A, 0.03f); for ((x, y) in listOf(-0.4f to 0f, 0f to -0.05f, 0.38f to 0.05f, -0.2f to 0.22f, 0.2f to 0.24f)) { rr(cv, x - 0.15f, y - 0.11f, x + 0.15f, y + 0.11f, 0.03f, 0xFFFFF7E8, 0.035f); dot(cv, x - 0.05f, y - 0.03f, 0.03f, 0x66D8402A) }; for (k in 0..3) ln(cv, -0.5f + k * 0.3f, 0.12f, -0.38f + k * 0.3f, 0.06f, 0xFF5DB85C, 0.06f); steam(cv, -0.2f, 0.3f) }
            "ulsan.bulgogi" -> { grill(cv); for (k in 0..4) { val x = -0.45f + k * 0.22f; p.reset(); p.moveTo(x - 0.2f, 0.1f); p.quadTo(x - 0.05f, -0.2f, x + 0.18f, -0.05f); p.quadTo(x + 0.25f, 0.2f, x, 0.32f); p.quadTo(x - 0.25f, 0.3f, x - 0.2f, 0.1f); sh(cv, p, if (k % 2 == 0) 0xFF8E4A2A else 0xFFA65A34, 0.035f) }; for (k in 0..3) ln(cv, -0.4f + k * 0.25f, -0.02f, -0.3f + k * 0.25f, 0.06f, 0xFF5DB85C, 0.06f); for (k in 0..5) dot(cv, -0.4f + k * 0.15f, 0.2f, 0.022f, 0xFFFFF4D6) }
            "gyeonggi.galbi" -> { plate(cv); rot(cv, -12f, 0f, 0.1f) { rr(cv, -0.7f, -0.05f, 0.5f, 0.3f, 0.12f, 0xFF8A4A26); for (k in 0..3) ln(cv, -0.5f + k * 0.25f, 0f, -0.42f + k * 0.25f, 0.25f, 0xFF5A2A10, 0.035f); rr(cv, 0.42f, 0.04f, 0.92f, 0.2f, 0.08f, 0xFFFFF7E8); ci(cv, 0.92f, 0.04f, 0.09f, 0xFFFFF7E8); ci(cv, 0.92f, 0.2f, 0.09f, 0xFFFFF7E8) } }
            "gyeonggi.rice" -> { bowl(cv, 0xFFFFFCF6, 0xFFFFFFFF, 0xFF6A9BE0); p.reset(); p.moveTo(-0.75f, -0.12f); p.quadTo(-0.6f, -0.72f, 0f, -0.74f); p.quadTo(0.6f, -0.72f, 0.75f, -0.12f); p.close(); sh(cv, p, 0xFFFFFFFF); for (k in 0..9) ov(cv, -0.5f + (k % 5) * 0.24f, -0.55f + (k / 5) * 0.22f, -0.44f + (k % 5) * 0.24f, -0.47f + (k / 5) * 0.22f, 0xFFF1EEE6, 0.015f) }
            "gangwon.sundubu" -> { pot(cv, 0xFFF4EEE4); p.reset(); p.moveTo(-0.5f, -0.05f); p.quadTo(-0.45f, -0.42f, -0.05f, -0.4f); p.quadTo(0.45f, -0.45f, 0.5f, -0.05f); p.quadTo(0f, 0.06f, -0.5f, -0.05f); sh(cv, p, 0xFFFFFFFF, 0.035f); dot(cv, 0.3f, -0.2f, 0.04f, 0xFF5DB85C); steam(cv, -0.3f, 0.1f, 0.4f) }
            "gangwon.squid" -> squid(cv)
            "chungbuk.olgaengi" -> { bowl(cv, 0xFFFFFCF6, 0xFFB8A35A, 0xFF3F6FD0); for ((x, y) in listOf(-0.4f to -0.15f, -0.05f to -0.2f, 0.3f to -0.12f, 0.1f to -0.02f, -0.25f to -0.02f)) { ci(cv, x, y, 0.07f, 0xFF4A6A3A, 0.025f); curve(cv, 0xFFB8D88A, 0.02f, x - 0.03f, y, x, y - 0.05f, x + 0.03f, y) }; for (k in 0..3) leaf(cv, -0.6f + k * 0.35f, -0.06f, 0.24f, 0.08f, -10f, 0xFF7CC66B); steam(cv, -0.2f, 0.3f) }
            "chungnam.walnutcake" -> { p.reset(); p.moveTo(-0.75f, -0.3f); p.lineTo(0.75f, -0.3f); p.lineTo(0.62f, 0.85f); p.lineTo(-0.62f, 0.85f); p.close(); sh(cv, p, 0xFFF4E2C4); ln(cv, -0.6f, 0.2f, 0.6f, 0.2f, 0xFFB87510, 0.05f); for ((x, y) in listOf(-0.36f to -0.3f, 0.06f to -0.42f, 0.42f to -0.28f, -0.14f to -0.08f, 0.26f to -0.06f)) walnut(cv, x, y, 0.22f) }
            "chungnam.crab" -> crab(cv, 0xFFE8743A, false)
            "jeonbuk.bibimbap" -> { bowl(cv, 0xFFC9A04A, 0xFFFFFFFF); val parts = listOf(0xFF4CAF50 to -0.55f, 0xFFF0883A to -0.25f, 0xFFFFF4C2 to 0.25f, 0xFF8A5A3C to 0.55f); for ((k, x) in parts) rot(cv, 0f, 0f, 0f) { ov(cv, x - 0.2f, -0.3f, x + 0.2f, 0.02f, k, 0.03f) }; ci(cv, 0f, -0.18f, 0.18f, 0xFFFFFFFF, 0.03f); ci(cv, 0f, -0.18f, 0.09f, 0xFFFFC233, 0.025f); ov(cv, -0.12f, -0.06f, 0.12f, 0.06f, 0xFFD8402A, 0.025f); for (k in 0..4) dot(cv, -0.1f + k * 0.05f, 0f, 0.015f, 0xFFFFF4D6) }
            "jeonnam.kkomak" -> { plate(cv); for ((x, y) in listOf(-0.42f to 0.05f, 0.05f to -0.02f, 0.45f to 0.08f, -0.2f to 0.28f, 0.25f to 0.3f)) shell(cv, x, y, 0.22f); for (k in 0..4) dot(cv, -0.3f + k * 0.15f, 0.12f + (k % 2) * 0.1f, 0.03f, 0xFFE0442B) }
            "jeonnam.abalone" -> abalone(cv)
            "gyeongbuk.snowcrab" -> crab(cv, 0xFFE8603A, true)
            "gyeongbuk.jjimdak" -> { plate(cv, 0xFFFFFCF6, 0xFF6A9BE0); ov(cv, -0.72f, -0.12f, 0.72f, 0.45f, 0xFF6A3A1E, 0.03f); for (k in 0..4) curve(cv, 0xFFC9B8A0, 0.035f, -0.5f + k * 0.1f, 0.1f, 0f, -0.05f + k * 0.04f, 0.5f - k * 0.05f, 0.2f); for ((x, y) in listOf(-0.35f to 0.05f, 0.1f to 0f, 0.35f to 0.2f, -0.1f to 0.25f)) ov(cv, x - 0.17f, y - 0.12f, x + 0.17f, y + 0.12f, 0xFF9A5A30, 0.035f); for ((x, y) in listOf(-0.5f to 0.25f, 0.45f to 0f)) rr(cv, x - 0.08f, y - 0.04f, x + 0.08f, y + 0.04f, 0.03f, 0xFFF0883A, 0.025f); ln(cv, -0.15f, 0.1f, 0.05f, 0.08f, 0xFF4CAF50, 0.05f); ln(cv, 0.2f, 0.3f, 0.32f, 0.36f, 0xFFE0442B, 0.05f) }
            "gyeongnam.oyster" -> { plate(cv, 0xFFEFF5FF, 0xFFB8D4F5); for ((x, y) in listOf(-0.35f to 0.05f, 0.3f to 0f, 0f to 0.25f)) oyster(cv, x, y); rot(cv, -20f, 0.5f, 0.3f) { ov(cv, 0.38f, 0.2f, 0.66f, 0.38f, 0xFFF6E36A, 0.03f) } }
            "gyeongnam.agujjim" -> { plate(cv); ov(cv, -0.75f, -0.1f, 0.75f, 0.45f, 0xFFD8402A, 0.03f); for (k in 0..8) curve(cv, 0xFFFFF4D6, 0.04f, -0.6f + k * 0.14f, 0.3f, -0.55f + k * 0.14f, 0.05f, -0.45f + k * 0.14f, 0f); for ((x, y) in listOf(-0.25f to 0.12f, 0.25f to 0.18f)) ov(cv, x - 0.17f, y - 0.11f, x + 0.17f, y + 0.11f, 0xFFF2D6C4, 0.035f); ln(cv, 0.4f, -0.05f, 0.55f, 0.05f, 0xFF4CAF50, 0.06f) }
            "jeju.blackpork" -> { grill(cv); for (k in 0..3) { val y = -0.1f + k * 0.14f; rot(cv, -8f, 0f, y) { rr(cv, -0.6f + (k % 2) * 0.1f, y - 0.06f, 0.5f + (k % 2) * 0.1f, y + 0.06f, 0.04f, 0xFFF2C6A4, 0.03f); ln(cv, -0.58f + (k % 2) * 0.1f, y - 0.05f, 0.48f + (k % 2) * 0.1f, y - 0.05f, 0xFF2A1A14, 0.035f); ln(cv, -0.55f + (k % 2) * 0.1f, y + 0.015f, 0.45f + (k % 2) * 0.1f, y + 0.015f, 0xFFFFE9DA, 0.02f) } }; for (k in 0..3) ln(cv, -0.5f + k * 0.3f, -0.18f, -0.4f + k * 0.3f, 0.48f, 0x55000000, 0.025f) }
            "jeju.galchi" -> { pot(cv, 0xFFD8402A); for ((x, y) in listOf(-0.32f to -0.12f, 0.22f to -0.16f)) { rr(cv, x - 0.22f, y - 0.1f, x + 0.22f, y + 0.1f, 0.06f, 0xFFDDE3EE, 0.035f); ln(cv, x - 0.16f, y - 0.03f, x + 0.16f, y - 0.03f, 0xFFFFFFFF, 0.03f) }; for ((x, y) in listOf(0.48f to -0.05f, -0.05f to 0f)) rr(cv, x - 0.13f, y - 0.07f, x + 0.13f, y + 0.07f, 0.03f, 0xFFF8E4C8, 0.03f); ln(cv, -0.55f, -0.05f, -0.4f, 0.02f, 0xFF4CAF50, 0.06f); steam(cv, -0.2f, 0.3f) }
            else -> return false
        }
        return true
    }

    private fun scallion(cv: Canvas) { for (k in 0..5) ci(cv, -0.5f + k * 0.2f, -0.24f + (k % 2) * 0.12f, 0.035f, 0xFF7CC66B, 0.015f) }
    private fun noodles(cv: Canvas, k: Long) { for (i in 0..4) curve(cv, k, 0.05f, -0.75f + i * 0.05f, -0.1f + (i % 2) * 0.06f, -0.2f, -0.3f + i * 0.03f, 0.75f - i * 0.05f, -0.08f + (i % 2) * 0.05f) }
    private fun egg(cv: Canvas, x: Float, y: Float) { ov(cv, x - 0.2f, y - 0.13f, x + 0.2f, y + 0.13f, 0xFFFFFFFF, 0.03f); ci(cv, x, y, 0.08f, 0xFFFFC233, 0.025f) }

    private fun chicken(cv: Canvas) {
        rot(cv, -30f, 0f, 0f) {
            rr(cv, 0.15f, -0.11f, 0.78f, 0.11f, 0.08f, 0xFFFFF7E8); ci(cv, 0.8f, -0.12f, 0.13f, 0xFFFFF7E8); ci(cv, 0.8f, 0.12f, 0.13f, 0xFFFFF7E8)
            p.reset(); p.moveTo(0.25f, -0.18f); p.cubicTo(0.0f, -0.6f, -0.85f, -0.55f, -0.88f, 0f); p.cubicTo(-0.85f, 0.55f, 0f, 0.6f, 0.25f, 0.18f); p.close(); sh(cv, p, 0xFFC8743A)
            for ((x, y) in listOf(-0.6f to -0.1f, -0.35f to 0.2f, -0.2f to -0.25f, -0.5f to 0.3f)) ci(cv, x, y, 0.06f, 0xFFE09A55, 0f)
            ov(cv, -0.7f, -0.38f, -0.35f, -0.22f, 0x66FFFFFF, 0f)
        }
    }

    private fun eomuk(cv: Canvas) {
        ln(cv, 0f, -0.95f, 0f, 0.95f, 0xFFD9B98A, 0.07f)
        for (k in 0..3) { val y = -0.65f + k * 0.38f; val s = if (k % 2 == 0) 1f else -1f
            p.reset(); p.moveTo(-0.08f, y - 0.16f); p.quadTo(0.55f * s, y - 0.25f, 0.6f * s, y); p.quadTo(0.55f * s, y + 0.22f, -0.08f, y + 0.18f); p.close(); sh(cv, p, 0xFFE9B36A) }
        rr(cv, -0.6f, 0.62f, 0.6f, 0.98f, 0.08f, 0xFFFFFCF6); ln(cv, -0.45f, 0.75f, 0.45f, 0.75f, 0xFF3F6FD0, 0.04f)   // the paper cup of broth
    }

    private fun hotteok(cv: Canvas) {
        p.reset(); p.moveTo(-0.6f, 0f); p.quadTo(-0.6f, -0.8f, 0f, -0.8f); p.quadTo(0.6f, -0.8f, 0.6f, 0f); p.close(); sh(cv, p, 0xFFE6A24A)
        ov(cv, -0.42f, -0.48f, 0.42f, 0.02f, 0xFF7A4A28, 0.035f)
        for (k in 0..8) { val x = -0.3f + (k % 3) * 0.3f; val y = -0.38f + (k / 3) * 0.13f; rot(cv, k * 40f, x, y) { ov(cv, x - 0.07f, y - 0.035f, x + 0.07f, y + 0.035f, if (k % 2 == 0) 0xFF7CC66B else 0xFFF4E2C4, 0.015f) } }
        p.reset(); p.moveTo(-0.72f, -0.08f); p.lineTo(0.72f, -0.08f); p.lineTo(0.55f, 0.9f); p.lineTo(-0.55f, 0.9f); p.close(); sh(cv, p, 0xFFFFFCF6)
        ln(cv, -0.62f, 0.25f, 0.62f, 0.25f, 0xFFE0442B, 0.05f)
    }

    private fun soboro(cv: Canvas) {
        p.reset(); for (k in 0 until 24) { val a = k / 24f * 6.283f; val rad = if (k % 2 == 0) 0.8f else 0.72f; val x = (Math.cos(a.toDouble()) * rad).toFloat(); val y = 0.1f + (Math.sin(a.toDouble()) * rad * 0.82f).toFloat(); if (k == 0) p.moveTo(x, y) else p.lineTo(x, y) }; p.close()
        sh(cv, p, 0xFFE0A24A)
        for (k in 0..14) dot(cv, -0.5f + (k % 5) * 0.25f, -0.3f + (k / 5) * 0.28f, 0.07f, if (k % 2 == 0) 0xFFF2C46E else 0xFFC9862E)
        ov(cv, -0.5f, -0.45f, -0.1f, -0.25f, 0x55FFFFFF, 0f)
    }

    private fun squid(cv: Canvas) {
        for (k in 0..5) { val x = -0.35f + k * 0.14f; curve(cv, 0xFFE07A5A, 0.07f, x, 0.25f, x + (k - 2.5f) * 0.12f, 0.6f, x + (k - 2.5f) * 0.05f, 0.92f) }
        p.reset(); p.moveTo(0f, -0.95f); p.quadTo(0.55f, -0.4f, 0.42f, 0.3f); p.lineTo(-0.42f, 0.3f); p.quadTo(-0.55f, -0.4f, 0f, -0.95f); p.close(); sh(cv, p, 0xFFF08A68)
        poly(-0.35f, -0.55f, -0.7f, -0.35f, -0.38f, -0.25f); sh(cv, p, 0xFFF08A68); poly(0.35f, -0.55f, 0.7f, -0.35f, 0.38f, -0.25f); sh(cv, p, 0xFFF08A68)
        ci(cv, -0.16f, 0.12f, 0.09f, 0xFFFFFFFF, 0.03f); ci(cv, 0.16f, 0.12f, 0.09f, 0xFFFFFFFF, 0.03f); dot(cv, -0.14f, 0.13f, 0.045f, INK); dot(cv, 0.18f, 0.13f, 0.045f, INK)
        for (k in 0..3) dot(cv, -0.15f + k * 0.1f, -0.4f + (k % 2) * 0.15f, 0.04f, 0xFFD45A3A)
    }

    private fun walnut(cv: Canvas, x: Float, y: Float, rad: Float) {
        ci(cv, x, y, rad, 0xFFC98A44)
        curve(cv, 0xFF8A5A2A, 0.03f, x, y - rad * 0.85f, x - rad * 0.3f, y, x, y + rad * 0.85f)
        for (k in -1..1) curve(cv, 0xFF8A5A2A, 0.02f, x - rad * 0.7f, y + k * rad * 0.35f, x - rad * 0.4f, y + k * rad * 0.35f - 0.04f, x - rad * 0.15f, y + k * rad * 0.35f)
    }

    private fun crab(cv: Canvas, k: Long, longLegs: Boolean) {
        val leg = if (longLegs) 0.98f else 0.75f
        for (s in floatArrayOf(-1f, 1f)) for (i in 0..2) { val y = 0.1f + i * 0.15f; curve(cv, k, 0.08f, s * 0.4f, y, s * (leg - 0.1f), y - 0.25f, s * leg, y + 0.35f) }
        for (s in floatArrayOf(-1f, 1f)) { ln(cv, s * 0.35f, -0.05f, s * 0.6f, -0.45f, k, 0.09f); p.reset(); p.moveTo(s * 0.6f, -0.45f); p.quadTo(s * 0.9f, -0.75f, s * 0.6f, -0.85f); p.quadTo(s * 0.62f, -0.65f, s * 0.48f, -0.62f); p.quadTo(s * 0.45f, -0.5f, s * 0.6f, -0.45f); sh(cv, p, k) }
        ov(cv, -0.55f, -0.25f, 0.55f, 0.45f, k)
        ov(cv, -0.35f, -0.15f, 0.05f, 0.05f, 0x44FFFFFF, 0f)
        ln(cv, -0.12f, -0.22f, -0.15f, -0.38f, INK, 0.04f); ln(cv, 0.12f, -0.22f, 0.15f, -0.38f, INK, 0.04f); dot(cv, -0.15f, -0.4f, 0.05f, INK); dot(cv, 0.15f, -0.4f, 0.05f, INK)
    }

    private fun shell(cv: Canvas, x: Float, y: Float, rad: Float) {
        p.reset(); p.moveTo(x - rad, y + rad * 0.3f); p.quadTo(x - rad, y - rad, x, y - rad); p.quadTo(x + rad, y - rad, x + rad, y + rad * 0.3f); p.quadTo(x, y + rad * 0.6f, x - rad, y + rad * 0.3f); p.close(); sh(cv, p, 0xFF9C8A7A, 0.035f)
        for (k in -2..2) ln(cv, x, y + rad * 0.35f, x + k * rad * 0.38f, y - rad * 0.8f, 0xFF6A5A4A, 0.02f)
    }

    private fun abalone(cv: Canvas) {
        ov(cv, -0.9f, -0.45f, 0.9f, 0.6f, 0xFF7FB8A8)
        for (k in 0..3) curve(cv, 0xFFB7E0D4, 0.04f, -0.75f + k * 0.12f, 0.05f, -0.2f, -0.4f + k * 0.12f, 0.75f - k * 0.1f, 0.05f)
        ov(cv, -0.55f, -0.2f, 0.55f, 0.4f, 0xFFF2DDB8)
        ov(cv, -0.35f, -0.1f, 0.35f, 0.28f, 0xFFE8C892, 0.03f)
        for (k in 0..4) dot(cv, -0.6f + k * 0.3f, -0.32f, 0.04f, 0xFFDCEFF0)
    }

    private fun oyster(cv: Canvas, x: Float, y: Float) {
        p.reset(); p.moveTo(x - 0.28f, y); p.quadTo(x - 0.25f, y - 0.2f, x, y - 0.18f); p.quadTo(x + 0.3f, y - 0.2f, x + 0.28f, y + 0.02f); p.quadTo(x + 0.15f, y + 0.18f, x - 0.05f, y + 0.15f); p.quadTo(x - 0.3f, y + 0.15f, x - 0.28f, y); p.close(); sh(cv, p, 0xFFB0A8A0, 0.035f)
        ov(cv, x - 0.18f, y - 0.1f, x + 0.16f, y + 0.08f, 0xFFF0EEE4, 0.025f); ov(cv, x - 0.1f, y - 0.06f, x + 0.06f, y + 0.04f, 0xFFD8D0C0, 0f)
    }
}
