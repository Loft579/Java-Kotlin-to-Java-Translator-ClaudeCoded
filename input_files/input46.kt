fun outer(): Int {
    val base = 10
    fun add(n: Int): Int {
        return base + n
    }
    fun twice(n: Int): Int {
        return add(n) + add(n)
    }
    return twice(1)
}

fun main() {
    println(outer())
}
