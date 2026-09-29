interface Shown {
    val tag: String get() = if (true) "on" else "off"
}

class Level(val n: Int) : Shown {
    val label: String get() = when (n) {
        1 -> "one"
        2 -> "two"
        else -> "many"
    }
    val sign: String get() = if (n > 0) "pos" else "neg"
}

fun grade(n: Int): String {
    return if (n > 8) "high" else if (n > 4) "mid" else "low"
}

fun main() {
    println(Level(1).label)
    println(Level(5).label)
    println(Level(1).sign)
    println(Level(1).tag)
    println(grade(9))
    println(grade(5))
    println(grade(1))

    val a = false
    val b = true
    val x = if (a) 1 else if (b) 2 else 3
    println(x)

    val xs = listOf(1, 2, 3)
    val ys = listOf(2, 3)
    val chosen = if (b) xs.filter { it > 1 } else ys
    println(chosen)
    val nested = xs.map { p -> ys.filter { q -> q > p } }
    println(nested)
    val chained = xs.map { it * 2 }.filter { it > 2 }
    println(chained)
    val kinds = xs.map { if (it > 1) "big" else "small" }
    println(kinds)
}
