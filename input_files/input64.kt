const val MAX = 10

private val SECRET = 42

private fun hidden(): Int {
    return SECRET
}

class Vault(private var code: Int) {
    private fun check(n: Int): Boolean {
        return n == code
    }
    fun open(n: Int): String {
        if (check(n)) {
            return "open"
        }
        return "closed"
    }
}

class Holder {
    lateinit var name: String
}

fun main() {
    println(MAX)
    println(hidden())
    println(Vault(7).open(7))
    println(Vault(7).open(1))
    val h = Holder()
    h.name = "x"
    println(h.name)
}
