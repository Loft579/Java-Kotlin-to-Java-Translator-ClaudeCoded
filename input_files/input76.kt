fun main() {
    println(keepLong(listOf("a", "bb", "ccc")))
    for (w in keepLong(listOf("a", "bb"))) {
        println(w)
    }
    val a = true
    val b = false
    val c = true
    if ((a && b) || c) {
        println("or wins")
    }
    if (!(a && b)) {
        println("not both")
    }
    val x = 1
    val y = 2
    println((x < y))
    println(!(x < y))
    println((x + 1) * 2)
    println(2 * (x + 1))
    val f = !(x < y) && c
    println(f)
    val s = "hi"
    println(!(s == "ho"))
    println((s == "hi") && c)
    val matrix = listOf(listOf(1, 2), listOf(3, 4))
    for (row in matrix) {
        println(row.size)
    }
    val r0 = matrix[0]
    println(r0[1])
    for (v in matrix[0]) {
        println(v + 1)
    }
}

fun keepLong(words: List<String>): List<String> {
    return words.filter { it.length > 1 }
}
