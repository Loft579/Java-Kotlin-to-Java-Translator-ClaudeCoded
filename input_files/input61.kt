fun show(n: Int): String {
    return "int $n"
}

fun show(s: String): String {
    return "str $s"
}

fun value(): Int {
    return 1
}

class Holder(var n: Int) {
    fun value(): Int {
        return n
    }
    fun both(): Int {
        return value() + n
    }
}

fun main() {
    println(show(1))
    println(show("x"))
    println(value())
    println(Holder(5).value())
    println(Holder(5).both())
}
