package com.gildongmu.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrandsTest {
    private fun shop(id: String, name: String, brand: String, place: String, km: Double, vararg menu: MenuItem) =
        Shop(id, name, brand, place, km, (km * 1.2).toInt().coerceAtLeast(1), 3, 0, "031-000-0000", menu.toList())

    private val view = ShopsView(
        5.0,
        listOf(
            shop("a", "해뜰커피 DT", "해뜰커피", "드라이브스루", 2.1, MenuItem("아메리카노", 4500, "음료"), MenuItem("크로와상", 3800, "간식")),
            shop("b", "든든버거 DT", "든든버거", "드라이브스루", 3.0, MenuItem("치즈버거", 7900, "식사"), MenuItem("콜라", 2000, "음료")),
            shop("c", "초록편의점", "초록편의점", "편의점·주유", 3.8, MenuItem("생수", 1000, "음료"), MenuItem("샌드위치", 3800, "식사")),
        ),
        null, 0.0, null,
    )

    @Test
    fun `one request gives the nearest shops with exactly one item each`() {
        val drinks = Errands.picks(view, "음료")
        assertEquals(listOf("해뜰커피 DT", "든든버거 DT", "초록편의점"), drinks.map { it.shop.name })
        assertEquals(listOf("아메리카노", "콜라", "생수"), drinks.map { it.item.name })
        assertEquals(listOf("치즈버거", "샌드위치"), Errands.picks(view, "식사").map { it.item.name })
    }

    @Test
    fun `only the kinds that some shop can give are offered`() {
        assertEquals(listOf("음료", "식사", "간식"), Errands.kindsIn(view))
        val noSnacks = view.copy(shops = view.shops.drop(1))
        assertEquals(listOf("음료", "식사"), Errands.kindsIn(noSnacks))
        assertEquals(emptyList<String>(), Errands.kindsIn(view.copy(shops = emptyList())))
    }

    @Test
    fun `with favourites set only those brands are offered`() {
        val fav = setOf("든든버거")
        assertEquals(listOf("든든버거 DT"), Errands.picks(view, "음료", fav).map { it.shop.name })
        assertEquals(listOf("음료", "식사"), Errands.kindsIn(view, fav))
        assertEquals(emptyList<String>(), Errands.kindsIn(view, setOf("없는브랜드")))   // no favourite nearby: nothing, and the screen says so
    }

    @Test
    fun `a kind of place is asked for by its first menu item`() {
        assertEquals(listOf("아메리카노", "치즈버거"), Errands.picks(view, "드라이브스루").map { it.item.name })
    }

    @Test
    fun `speech maps to what the driver wants, and never swallows a jam or parking question`() {
        assertEquals("음료", Errands.matchType("커피 마시고 싶어"))
        assertEquals("식사", Errands.matchType("배고픈데 뭐 먹을 수 있어?"))
        assertEquals("간식", Errands.matchType("출출한데 간식 있어?"))
        assertEquals("드라이브스루", Errands.matchType("앞에 드라이브스루 뭐 있어?"))
        assertNull(Errands.matchType("휴게소 주차장에 자리 있어요?"))
        assertNull(Errands.matchType("앞에 사고 났어?"))
        assertNull(Errands.matchType("안녕하세요"))
    }

    @Test
    fun `the spoken offer carries distance, item, price and the words to answer with`() {
        val p = Errands.picks(view, "음료").first()
        val s = Errands.spoken(p)
        assertTrue("해뜰커피 DT" in s && "4,500원" in s && "결제" in s && "단골" in s)
    }

    @Test
    fun `a menu item named by voice is matched within the shop and a status is spoken plainly`() {
        val shop = view.shops.first()   // 해뜰커피 DT: 아메리카노, 크로와상
        assertEquals("크로와상", Errands.itemNamed(shop, "크로와상으로 할게")?.name)
        assertEquals(null, Errands.itemNamed(shop, "버거 주세요"))
        val o = OrderReceipt("D-1001", "해뜰커피 DT", "아메리카노", 4500, 4, 0, "cooking", "조리 중", delayMin = 2)
        val line = Errands.statusSpoken(o)
        assertTrue("조리 중" in line && "4분" in line && "2분 늦어졌어요" in line)
        assertTrue("도착" !in Errands.statusSpoken(o.copy(status = "picked_up", statusLabel = "수령 완료")))
    }
}
