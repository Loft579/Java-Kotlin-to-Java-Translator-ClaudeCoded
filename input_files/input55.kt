fun fact(n: Int): Int {
    if (n <= 1) {
        return 1
    }
    return n * fact(n - 1)
}

fun even(n: Int): Boolean {
    if (n == 0) {
        return true
    }
    return odd(n - 1)
}

fun odd(n: Int): Boolean {
    if (n == 0) {
        return false
    }
    return even(n - 1)
}

fun main() {
    println(fact(5))
    println(even(4))
    println(odd(4))
}
