class Box(var n: Int) {
    fun first(): Int {
        return n
    }
    fun second(): Int {
        return n * 2
    }
}

fun check(flag: Boolean) {
    if (!flag) {
        println("not set")
    }
}

fun main() {
    val box = Box(7)
    val big = 1000L
    println(box.first())
    println(box.second())
    println(big)
    check(false)
}
