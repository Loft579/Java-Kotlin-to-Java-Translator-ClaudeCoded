interface Greeter {
    fun greet(): String
    val lang: String
}

class English(var name: String) : Greeter {
    override val lang: String get() = "en"
    override fun greet(): String {
        return "hello " + name
    }
}

fun main() {
    val g = English("ana")
    println(g.greet())
    println(g.lang)
}
