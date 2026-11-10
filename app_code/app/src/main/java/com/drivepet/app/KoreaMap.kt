package com.drivepet.app

/**
 * The outlines of the 17 시·도 (a simplified version of the Statistics Korea 2013 boundaries, about 1 km accurate, kept in
 * the resource file korea_provinces.txt: one line per region, rings of "lon,lat" points). Used to draw the map collection and
 * to tell which region a GPS position is in. Pure Kotlin, no Android classes.
 *
 * The data is from 2013: later boundary changes (for example Gunwi, which moved from 경북 to Daegu in 2023) are not in it.
 */
object KoreaMap {
    class Shape(val id: String, val rings: List<DoubleArray>)   // each ring: lon0, lat0, lon1, lat1, ...

    val shapes: List<Shape> by lazy { load() }

    private fun load(): List<Shape> {
        val stream = KoreaMap::class.java.getResourceAsStream("/korea_provinces.txt") ?: return emptyList()
        return stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.filter { it.isNotBlank() }.mapNotNull { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0) return@mapNotNull null
                val id = line.substring(0, tab)
                val rings = line.substring(tab + 1).split(';').filter { it.isNotBlank() }.map { ring ->
                    val pts = ring.trim().split(' ')
                    val a = DoubleArray(pts.size * 2)
                    pts.forEachIndexed { i, p -> val c = p.indexOf(','); a[2 * i] = p.substring(0, c).toDouble(); a[2 * i + 1] = p.substring(c + 1).toDouble() }
                    a
                }
                Shape(id, rings)
            }.toList()
        }
    }

    /** the region whose outline holds this position (rings of one region count even-odd, so holes and enclaves work), or null */
    fun regionAt(lat: Double, lon: Double): String? {
        for (s in shapes) if (contains(s, lat, lon)) return s.id
        return null
    }

    fun contains(s: Shape, lat: Double, lon: Double): Boolean {
        var inside = false
        for (r in s.rings) if (ringContains(r, lat, lon)) inside = !inside
        return inside
    }

    private fun ringContains(r: DoubleArray, lat: Double, lon: Double): Boolean {
        val n = r.size / 2
        var inside = false
        var j = n - 1
        for (i in 0 until n) {
            val xi = r[2 * i]; val yi = r[2 * i + 1]; val xj = r[2 * j]; val yj = r[2 * j + 1]
            if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    /** the centre of the biggest ring of a region: a place for its name on the map (lon, lat) */
    fun labelPoint(id: String): Pair<Double, Double>? {
        val s = shapes.firstOrNull { it.id == id } ?: return null
        var best: DoubleArray? = null; var bestA = -1.0
        for (r in s.rings) { val a = Math.abs(area(r)); if (a > bestA) { bestA = a; best = r } }
        val r = best ?: return null
        val n = r.size / 2
        var a2 = 0.0; var cx = 0.0; var cy = 0.0
        for (i in 0 until n) {
            val j = (i + 1) % n
            val cross = r[2 * i] * r[2 * j + 1] - r[2 * j] * r[2 * i + 1]
            a2 += cross; cx += (r[2 * i] + r[2 * j]) * cross; cy += (r[2 * i + 1] + r[2 * j + 1]) * cross
        }
        if (Math.abs(a2) < 1e-12) return r[0] to r[1]
        return (cx / (3 * a2)) to (cy / (3 * a2))
    }

    private fun area(r: DoubleArray): Double {
        val n = r.size / 2
        var a = 0.0
        for (i in 0 until n) { val j = (i + 1) % n; a += r[2 * i] * r[2 * j + 1] - r[2 * j] * r[2 * i + 1] }
        return a / 2
    }
}
