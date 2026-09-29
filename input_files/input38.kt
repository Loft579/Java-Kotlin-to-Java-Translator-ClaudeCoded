fun parseInt(str: String): Int? {
    return str.toIntOrNull()
}

fun main() {
    val a = parseInt("1000")
    val b = parseInt("1000")
    if (a == b) {
        println("boxed values compare by value")
    }
    val s1 = "ab"
    val s2 = "ab"
    if (s1 == s2) {
        println("strings compare by text")
    }
}
