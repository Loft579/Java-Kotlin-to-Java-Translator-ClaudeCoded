fun items(): List<String> {
    return listOf("a", "b")
}

fun main() {
    for (x in items()) {
        println(x)
    }
    val ints = listOf(1, 2, 3)
    println(ints.sum())
    val nested = listOf(listOf(1, 2), listOf(3))
    println(nested.get(0).get(1))
    val names = listOf("ana", "bob", "aldo")
    names
        .filter { it.startsWith("a") }
        .map { it.toUpperCase() }
        .forEach { println(it) }
}
