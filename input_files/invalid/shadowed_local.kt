// Kotlin allows this with a warning; Java rejects it outright.
fun f(n: Int) {
    val n = 5
    println(n)
}

fun main() {
    f(1)
}
