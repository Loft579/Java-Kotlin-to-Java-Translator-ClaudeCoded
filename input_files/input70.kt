fun find(k: String): String? {
    if (k == "a") {
        return "alpha"
    }
    return null
}

fun kind(): String {
    return "a"
}

fun main() {
    val hit = find("a")
    val miss = find("z")
    println("hit: $hit")
    println("miss: $miss")
    println("len: ${hit?.length}")
    println("len: ${miss?.length}")
    val items = listOf("a", null, "c")
    println(items.size)
    for (i in items) {
        println(i)
    }
    when (kind()) {
        "a" -> println("is a")
        else -> println("other")
    }
}
