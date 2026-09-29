class Item(val name: String, var price: Double, var qty: Int) {
    val total: Double get() = price * qty
    fun describe(): String {
        return "$name x$qty = ${total}"
    }
}

class Inventory(val items: List<Item>) {
    val count: Int get() = items.size
    fun value(): Double {
        var sum = 0.0
        for (it in items) {
            sum = sum + it.total
        }
        return sum
    }
    fun cheapest(): Item {
        var best = items.get(0)
        for (it in items) {
            if (it.price < best.price) {
                best = it
            }
        }
        return best
    }
}

fun describeAll(inv: Inventory) {
    for (it in inv.items) {
        println(it.describe())
    }
}

fun main() {
    val a = Item("bread", 2.5, 2)
    val b = Item("milk", 1.75, 4)
    val inv = Inventory(listOf(a, b))
    describeAll(inv)
    println(inv.count)
    println(inv.value())
    println(inv.cheapest().name)
    if (inv.value() > 10.0) {
        println("expensive")
    } else {
        println("cheap")
    }
}
