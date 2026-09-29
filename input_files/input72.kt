fun main() {
    val tags = setOf("delta", "alfa", "charlie", "bravo")
    println(tags)
    for (t in tags) {
        println(t)
    }
    val repeated = setOf("a", "b", "a")
    println(repeated)
    println(repeated.size)
    val more = mutableSetOf("zulu", "yankee")
    println(more)
    println(more.contains("zulu"))

    val row = "a,b,,"
    val parts = row.split(",")
    println(parts.size)
    println(parts)
    println("one".split(",").size)
}
