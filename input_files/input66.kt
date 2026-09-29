fun find(key: String): String? {
    if (key == "a") {
        return "alpha"
    }
    return null
}

class Entry(var key: String, var value: String?) {
    val shown: String? get() = value
    fun describe(): String {
        if (value == null) {
            return "$key has no value"
        }
        return "$key = $value"
    }
}

fun main() {
    val hit = find("a")
    println(hit)
    println(hit?.length)
    val miss = find("z")
    println(miss)
    println(miss?.length)
    val e1 = Entry("k", null)
    val e2 = Entry("k", "v")
    println(e1.describe())
    println(e2.describe())
    println(e1.shown)
    println(e2.shown)
    if (e1.value == null) {
        println("missing")
    }
    val items: List<String> = listOf("x")
    println(items.size)
}
