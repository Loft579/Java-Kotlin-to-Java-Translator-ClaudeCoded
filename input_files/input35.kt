fun area(w: Int, h: Int): Int {
    return w * h
}

fun f(n: Int): Int {
    return n
}

fun main() {
    val side = 4
    println("area of ${area(2, 3)} and of ${area(side, side)}")
    println("f($side) = ${f(side)}")
}
