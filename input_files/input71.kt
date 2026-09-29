fun lengthOf(s: String): Int {
    return s.length
}

fun main() {
    val words = listOf("aa", "b", "ccc")
    val big = words.filter { it.length > 1 }
    println(big)
    println(big.size)
    val up = words.map { w -> w.toUpperCase() }
    println(up.get(0))
    val lengths = words.map { it.length }
    println(lengths)
    val sorted = words.sortedBy { it.length }
    println(sorted)
    for (w in up) {
        println(w.length)
    }
    var total = 0
    for (n in 0 until 4) {
        if (n == 1) {
            continue
        }
        if (n == 3) {
            break
        }
        total = total + n
    }
    println(total)
    val marks = mutableListOf(10, 20, 30)
    marks[0] = 99
    marks[1] += 5
    val at = 2
    marks[at] *= 2
    println(marks)
    println(words.filter { it.length > 1 })
    val flag = true
    println(if (flag) "yes" else "no")
    println(lengthOf(if (flag) "abc" else "z"))
    words.forEach { w -> println(w) }
}
