import base

class Formal(name: String) : Greeter(name) {
    override fun kind(): String {
        return "formal"
    }
}

fun main() {
    val s = Formal("ana")
    println(s.hello())
    println(s.kind())
}
