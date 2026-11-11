package com.drivepet.app

enum class Slot(val label: String) { HAT("모자"), GLASSES("안경"), PANTS("바지"), FURNITURE("가구·소품") }

data class Item(val id: String, val name: String, val slot: Slot, val price: Int)

/** Everything the shop sells. The drawing of each item is in [PetView], keyed by [Item.id]. */
object Catalog {
    val items = listOf(
        Item("straw", "밀짚모자", Slot.HAT, 30),
        Item("beret", "베레모", Slot.HAT, 50),
        Item("crown", "왕관", Slot.HAT, 120),
        Item("sun", "선글라스", Slot.GLASSES, 40),
        Item("round", "동그란 안경", Slot.GLASSES, 30),
        Item("jeans", "청바지", Slot.PANTS, 60),
        Item("shorts", "반바지", Slot.PANTS, 40),
        Item("plant", "화분", Slot.FURNITURE, 40),
        Item("rug", "러그", Slot.FURNITURE, 60),
        Item("lamp", "스탠드", Slot.FURNITURE, 80),
        Item("clock", "벽시계", Slot.FURNITURE, 50),
        Item("picture", "그림 액자", Slot.FURNITURE, 70),
    )

    fun get(id: String) = items.firstOrNull { it.id == id }
    fun bySlot(slot: Slot) = items.filter { it.slot == slot }
}

/**
 * What the pet owns (inventory) and has on or in its room. Buying is paid with safe-driving points; wearing is free.
 * The pet wears one item per slot (a hat, glasses, trousers); the room can hold any number of furniture items at once.
 * Pure logic, no Android.
 */
class Wardrobe(owned: Set<String> = emptySet(), worn: Collection<String> = emptyList()) {
    private val _owned = owned.filter { Catalog.get(it) != null }.toMutableSet()
    private val _worn = mutableMapOf<Slot, String>()      // hat, glasses, trousers
    private val _furniture = linkedSetOf<String>()

    init { worn.forEach { equip(it) } }

    val owned: Set<String> get() = _owned
    /** what the pet wears, one per slot (never holds furniture) */
    val equipped: Map<Slot, String> get() = _worn
    /** the furniture in the room */
    val furniture: Set<String> get() = _furniture

    fun has(id: String) = id in _owned
    fun isEquipped(id: String) = id in _furniture || _worn.values.contains(id)

    /** pays with [spend] (which returns false when there are not enough points). Already owned items are not charged twice. */
    fun buy(id: String, spend: (Int) -> Boolean): Boolean {
        val item = Catalog.get(id) ?: return false
        if (item.id in _owned) return false
        if (!spend(item.price)) return false
        _owned += item.id
        return true
    }

    /** puts it on (replacing what was worn in that slot) or into the room. Only owned items. */
    fun equip(id: String): Boolean {
        val item = Catalog.get(id) ?: return false
        if (item.id !in _owned) return false
        if (item.slot == Slot.FURNITURE) _furniture += item.id else _worn[item.slot] = item.id
        return true
    }

    /** takes off whatever is worn in a (non-furniture) slot */
    fun unequip(slot: Slot) { _worn.remove(slot) }

    /** takes one item off, or out of the room */
    fun unequipItem(id: String) {
        _furniture.remove(id)
        _worn.entries.removeAll { it.value == id }
    }

    fun clearWorn() { _worn.clear(); _furniture.clear() }

    fun ownedCsv() = _owned.joinToString(",")
    fun equippedCsv() = (_worn.values + _furniture).joinToString(",")

    companion object {
        /** [wornCsv] is a list of item ids; the older "HAT=beret" form is still understood */
        fun from(ownedCsv: String?, wornCsv: String?): Wardrobe {
            val owned = ownedCsv.orEmpty().split(",").filter { it.isNotBlank() }.toSet()
            val worn = wornCsv.orEmpty().split(",").map { it.substringAfter("=") }.filter { it.isNotBlank() }
            return Wardrobe(owned, worn)
        }
    }
}
