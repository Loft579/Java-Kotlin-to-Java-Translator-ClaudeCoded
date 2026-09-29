fun main() {
    val n = 3
    for (i in 0 until n) {
        println(i)
    }
    val s = "abc"
    if (s.isNotEmpty()) {
        println("has text")
    }
    val items = listOf("a")
    if (items.isNotEmpty()) {
        println("has items")
    }
}
