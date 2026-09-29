class Empty()

class OnlyGetter(var n: Int) {
    val double: Int get() = n * 2
}

fun many(a: Int, b: Int, c: Int, d: Int, e: Int, f: Int): Int {
    return a + b + c + d + e + f
}

fun nothing() {
}

fun main() {
    nothing()
    println(many(1, 2, 3, 4, 5, 6))
    println(OnlyGetter(4).double)
    println(((1 + 2) * (3 - 1)) / 2)
    println("")
    println("$")
    val e = Empty()
    println(many(1, 1, 1, 1, 1, 1))
}

fun level(n: Int): String {
    if (n < 10) {
        return "a"
    } else if (n < 20) {
        return "b"
    } else if (n < 30) {
        return "c"
    } else if (n < 40) {
        return "d"
    } else {
        return "e"
    }
}

