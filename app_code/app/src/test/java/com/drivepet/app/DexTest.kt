package com.drivepet.app

import java.util.Random
import org.junit.Test

private fun assertTrue(c: Boolean, m: String? = null) = org.junit.Assert.assertTrue(m, c)
private fun assertFalse(c: Boolean, m: String? = null) = org.junit.Assert.assertFalse(m, c)
private fun <T> assertEquals(e: T, a: T, m: String? = null) = org.junit.Assert.assertEquals(m, e, a)
private fun assertNull(x: Any?) = org.junit.Assert.assertNull(x)
private fun assertNotNull(x: Any?) = org.junit.Assert.assertNotNull(x)

class DexTest {
    // ---------------------------------------------------------------- the data

    @Test fun seventeenRegionsFiveItemsEach() {
        assertEquals(17, Korea.regions.size)
        Korea.regions.forEach { assertEquals(5, it.items.size, it.name) }
        assertEquals(85, Korea.items.size)
        assertEquals(85, Korea.items.map { it.id }.toSet().size)   // no id twice
    }

    @Test fun everyRegionHasACommonAndARareItemAndAnchors() {
        Korea.regions.forEach { r ->
            assertTrue(r.items.any { it.rarity == Rarity.RARE }, r.name)
            assertTrue(r.items.count { it.rarity == Rarity.COMMON } >= 2, r.name)
            assertTrue(r.anchors.isNotEmpty(), r.name)
            r.items.forEach { assertEquals(r.id, it.regionId) }
        }
    }

    @Test fun tileMapHasEveryRegionOnceWithoutOverlap() {
        assertEquals(Korea.regions.map { it.id }.toSet(), Korea.tile.keys)
        assertEquals(17, Korea.tile.values.toSet().size)
        Korea.tile.values.forEach { (c, r) -> assertTrue(c in 0..4 && r in 0..5) }
    }

    @Test fun itemsHaveNamesAndEmoji() {
        Korea.items.forEach { assertTrue(it.name.isNotBlank() && it.emoji.isNotBlank(), it.id) }
    }

    // ---------------------------------------------------------------- where is this position

    private fun at(lat: Double, lon: Double) = Korea.locate(lat, lon)?.id

    @Test fun locatesWellKnownPlaces() {
        assertEquals("seoul", at(37.5663, 126.9779))      // city hall
        assertEquals("seoul", at(37.5563, 126.9236))      // Hongdae
        assertEquals("seoul", at(37.4979, 127.0276))      // Gangnam
        assertEquals("gyeonggi", at(37.3947, 127.1112))   // Pangyo
        assertEquals("gyeonggi", at(37.2660, 127.0010))   // Suwon
        assertEquals("incheon", at(37.4563, 126.7052))
        assertEquals("incheon", at(37.4602, 126.4407))    // the airport
        assertEquals("gangwon", at(37.7637, 128.8996))    // Gangneung
        assertEquals("gangwon", at(38.2070, 128.5918))    // Sokcho
        assertEquals("chungbuk", at(36.6357, 127.4917))   // Cheongju
        assertEquals("chungnam", at(36.8151, 127.1139))   // Cheonan
        assertEquals("sejong", at(36.4800, 127.2890))
        assertEquals("daejeon", at(36.3320, 127.4342))
        assertEquals("jeonbuk", at(35.8242, 127.1480))    // Jeonju
        assertEquals("gwangju", at(35.1595, 126.8526))
        assertEquals("jeonnam", at(34.8118, 126.3922))    // Mokpo
        assertEquals("jeonnam", at(34.7604, 127.6622))    // Yeosu
        assertEquals("daegu", at(35.8714, 128.6014))
        assertEquals("gyeongbuk", at(36.5684, 128.7294))  // Andong
        assertEquals("gyeongbuk", at(35.8562, 129.2247))  // Gyeongju
        assertEquals("ulsan", at(35.5384, 129.3114))
        assertEquals("busan", at(35.1151, 129.0422))      // the station
        assertEquals("busan", at(35.1631, 129.1635))      // Haeundae
        assertEquals("gyeongnam", at(35.2279, 128.6811))  // Changwon
        assertEquals("gyeongnam", at(34.8544, 128.4331))  // Tongyeong
        assertEquals("jeju", at(33.5066, 126.4930))       // the airport
        assertEquals("jeju", at(33.2530, 126.5600))       // Seogwipo
    }

    @Test fun islandsBelongToTheirProvince() {
        assertEquals("gyeongbuk", at(37.4844, 130.9057))   // Ulleungdo
        assertEquals("incheon", at(37.9660, 124.7140))     // Baengnyeongdo
        assertEquals("gyeongnam", at(34.8800, 128.6210))   // Geoje
    }

    @Test fun outsideKoreaIsNowhere() {
        assertNull(Korea.locate(35.68, 139.69))    // Tokyo
        assertNull(Korea.locate(39.03, 125.75))    // Pyongyang
        assertNull(Korea.locate(0.0, 0.0))
        assertNull(Korea.locate(Double.NaN, 127.0))
        assertNull(Korea.locate(36.0, 124.5))      // far out in the Yellow Sea
    }

    @Test fun distanceBetweenSeoulAndBusan() {
        val d = Korea.distKm(37.5663, 126.9779, 35.1796, 129.0756)
        assertTrue(d in 315.0..335.0, "was $d")
    }

    // ---------------------------------------------------------------- finding

    private fun dex(seed: Long = 1L, state: DexState = DexState()) = Dex(state, Random(seed))

    @Test fun firstVisitFindsSomethingWithinTheFirstKm() {
        val d = dex()
        val f = d.onDrive("busan", 1.0f, 1)
        assertEquals(1, f.size)
        assertTrue(f[0].isNew)
        assertEquals("busan", f[0].item.regionId)
        assertEquals(1, d.count("busan"))
    }

    @Test fun nothingBeforeAKilometre() {
        val d = dex()
        assertTrue(d.onDrive("busan", 0.4f, 1).isEmpty())
        assertTrue(d.onDrive("busan", 0.4f, 1).isEmpty())
        assertEquals(1, d.onDrive("busan", 0.3f, 1).size)   // 1.1 km in total
    }

    @Test fun noRegionOrNoDistanceFindsNothing() {
        val d = dex()
        assertTrue(d.onDrive(null, 5f, 1).isEmpty())
        assertTrue(d.onDrive("busan", 0f, 1).isEmpty())
        assertTrue(d.onDrive("busan", -2f, 1).isEmpty())
        assertTrue(d.onDrive("busan", Float.NaN, 1).isEmpty())
        assertTrue(d.onDrive("nowhere", 5f, 1).isEmpty())
        assertEquals(0, d.owned.size)
    }

    @Test fun onlyItemsOfTheCurrentRegionAreFound() {
        val d = dex(5)
        repeat(300) { d.onDrive("jeju", 1f, 1 + it / 15) }
        assertTrue(d.owned.isNotEmpty())
        assertTrue(d.owned.all { it.startsWith("jeju.") })
    }

    @Test fun aHugeJumpIsNotTrusted() {
        val d = dex()
        // 500 km in one call counts as at most MAX_STEP_KM kilometres
        val f = d.onDrive("seoul", 500f, 1)
        assertTrue(f.size <= Dex.MAX_STEP_KM.toInt())
    }

    @Test fun droughtGuaranteesAFind() {
        // a random source that never rolls under the chance
        val never = object : Random() { override fun nextFloat() = 0.99f }
        val d = Dex(DexState(visited = setOf("seoul")), never)
        var found = 0
        for (i in 1..30) found += d.onDrive("seoul", 1f, 1).size
        assertEquals(1, found)                      // exactly the one at kilometre 25
    }

    @Test fun roughlyOneFindPerTwelveKm() {
        var total = 0
        val runs = 200
        for (s in 1..runs) {
            val d = Dex(DexState(visited = setOf("seoul")), Random(s.toLong()))
            for (day in 1..10) for (k in 1..10) total += d.onDrive("seoul", 1f, day).size
        }
        val perKm = total.toDouble() / (runs * 100)
        assertTrue(perKm in 0.06..0.11, "was $perKm")
    }

    @Test fun dailyCapStopsFinding() {
        val always = object : Random() { override fun nextFloat() = 0f }
        val d = Dex(DexState(visited = setOf("seoul")), always)
        var found = 0
        repeat(100) { found += d.onDrive("seoul", 1f, 7).size }
        assertEquals(Dex.DAILY_CAP, found)
        // a new day starts again
        assertEquals(1, d.onDrive("seoul", 1f, 8).size)
    }

    @Test fun pityGivesANewItemAfterRepeats() {
        // own 4 of 5 in busan, then with a rolling source every find is the one missing item at the latest after PITY repeats
        val always = object : Random() { override fun nextFloat() = 0f }
        val owned = Korea.region("busan")!!.items.take(4).map { it.id }.toSet()
        val d = Dex(DexState(owned = owned, visited = setOf("busan")), always)
        var finds = ArrayList<Find>()
        var day = 1
        while (finds.none { it.isNew } && finds.size < 50) { finds += d.onDrive("busan", 1f, day++); }
        assertTrue(finds.first { it.isNew }.item.id == Korea.region("busan")!!.items[4].id)
        // before the new one there were at most PITY_DUPS repeats
        val before = finds.takeWhile { !it.isNew }.size
        assertTrue(before <= Dex.PITY_DUPS, "was $before")
    }

    @Test fun repeatsPayLessAndNewPayByRarity() {
        val d = dex()
        d.grantRegion("seoul")
        val f = d.forceFind("seoul", 1)
        assertNotNull(f)
        assertFalse(f!!.isNew)
        assertEquals(Dex.DUP_POINTS, f.points)
        val d2 = dex(); val n = d2.forceFind("seoul", 1)!!
        assertTrue(n.isNew)
        assertEquals(n.item.rarity.points, n.points)
    }

    @Test fun completingARegionPaysTheBonusOnce() {
        val d = dex()
        val items = Korea.region("jeju")!!.items
        items.dropLast(1).forEach { d.grant(it.id) }
        var last: Find? = null
        var day = 1
        // keep finding until the last one turns up (pity makes this end)
        while (last == null && day < 100) { last = d.onDrive("jeju", 1f, day++).firstOrNull { it.isNew } }
        assertNotNull(last)
        assertTrue(last!!.regionDone)
        assertEquals(last.item.rarity.points + Dex.REGION_BONUS, last.points)
        assertTrue(d.regionDone("jeju"))
        assertEquals(1, d.regionsDone())
        assertFalse(last.allDone)
    }

    @Test fun completingTheMapPaysTheBigBonus() {
        val d = dex()
        d.grantAll()
        // take one item away by rebuilding the state without it, then find it again
        val missing = Korea.region("jeju")!!.items.last().id
        val s = d.state()
        val d2 = Dex(s.copy(owned = s.owned - missing, guaranteed = emptySet()), Random(3))
        var last: Find? = null
        var day = 1
        while (last == null && day < 200) { last = d2.onDrive("jeju", 1f, day++).firstOrNull { it.isNew } }
        assertNotNull(last)
        assertEquals(missing, last!!.item.id)
        assertTrue(last.allDone)
        assertEquals(last.item.rarity.points + Dex.REGION_BONUS + Dex.MAP_BONUS, last.points)
        assertTrue(d2.allDone)
    }

    @Test fun completeRegionStillGivesRepeatsNotNew() {
        val d = dex(); d.grantRegion("ulsan")
        repeat(100) { d.onDrive("ulsan", 1f, 1 + it / 15).forEach { f -> assertFalse(f.isNew) } }
        assertEquals(5, d.count("ulsan"))
    }

    @Test fun saveAndRestoreKeepsEverything() {
        val d = dex(9)
        repeat(40) { d.onDrive("gyeonggi", 1f, 3 + it / 15) }
        d.enter("busan")
        val before = d.state()
        val restored = Dex.decode(Dex.encode(before))
        assertEquals(before, restored)
        val d2 = Dex(restored, Random(1))
        assertEquals(d.owned, d2.owned)
        assertEquals(d.visited, d2.visited)
    }

    @Test fun decodeToleratesGarbage() {
        assertEquals(DexState(), Dex.decode(null))
        assertEquals(DexState(), Dex.decode(""))
        val s = Dex.decode("x.y,seoul.tteok||| abc |-3|zz|q")
        assertEquals(setOf("seoul.tteok", "x.y"), s.owned)       // unknown ids are dropped later by Dex
        val d = Dex(s)
        assertEquals(setOf("seoul.tteok"), d.owned)
        assertEquals(0, d.state().dupStreak)
    }

    @Test fun counts() {
        val d = dex()
        assertEquals(85, d.total)
        d.grantRegion("seoul"); d.grant("busan.gukbap")
        assertEquals(5, d.count("seoul")); assertEquals(1, d.count("busan"))
        assertTrue(d.regionDone("seoul")); assertFalse(d.regionDone("busan"))
        assertEquals(0, d.count("nowhere"))
    }

    @Test fun fillingTheWholeMapIsPossibleAndTakesLongDrives() {
        // the simulated driver drives through all regions in turn until the map is full
        val d = Dex(DexState(), Random(11))
        var km = 0
        var day = 1
        var kmToday = 0
        while (!d.allDone && km < 20000) {
            for (r in Korea.regions) {
                if (d.regionDone(r.id)) continue
                d.onDrive(r.id, 1f, day); km++; kmToday++
                if (kmToday >= 100) { day++; kmToday = 0 }
            }
        }
        assertTrue(d.allDone, "not complete after $km km")
        assertTrue(km in 600..12000, "needed $km km")
    }

    // ---------------------------------------------------------------- the outlines

    @Test fun outlinesExistForEveryRegion() {
        assertEquals(17, KoreaMap.shapes.size)
        assertEquals(Korea.regions.map { it.id }.toSet(), KoreaMap.shapes.map { it.id }.toSet())
        KoreaMap.shapes.forEach { assertTrue(it.rings.isNotEmpty(), it.id) }
    }

    @Test fun insideTheOutlineAnswersTheRegion() {
        assertEquals("seoul", KoreaMap.regionAt(37.5663, 126.9779))
        assertEquals("gyeonggi", KoreaMap.regionAt(37.2660, 127.0010))   // Suwon, around Seoul: Seoul is a hole in Gyeonggi
        assertEquals("busan", KoreaMap.regionAt(35.1631, 129.1635))
        assertEquals("jeju", KoreaMap.regionAt(33.4996, 126.5312))
        assertEquals("gangwon", KoreaMap.regionAt(37.7519, 128.8761))
        assertNull(KoreaMap.regionAt(36.0, 124.5))                       // the sea
    }

    @Test fun everyLabelPointIsNearItsRegion() {
        Korea.regions.forEach { r ->
            val p = KoreaMap.labelPoint(r.id)
            assertNotNull(p)
            val d = Korea.distKm(p!!.second, p.first, r.anchors.first().first, r.anchors.first().second)
            assertTrue(d < 150.0, "${r.id} $d")
        }
    }
}