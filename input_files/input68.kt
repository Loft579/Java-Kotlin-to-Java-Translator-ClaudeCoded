class Bag(val raw: List<Int>) {
    val items: List<Int> get() = raw
}

fun names(): List<String>? {
    return listOf("a")
}

fun main() {
    val b = Bag(listOf(4, 5))
    println(b.items.size)
    println(b.items.get(1))
    println(names())
    val x: List<Int>? = null
    println(x)
    var y: Set<String>? = null
    println(y)
    val fixed: List<String> = listOf("p", "q")
    for (f in fixed) {
        println(f)
    }
}
