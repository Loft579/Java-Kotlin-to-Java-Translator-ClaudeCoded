val MAX_SIZE = 10
// declared before what it calls, so its type is only known once the file is read
val GREETING = build_greeting()
val BOX = Point2D(1, 1)

class Point2D(val x1: Int, val y_pos: Int) {
    val sum_all: Int get() = x1 + y_pos
    fun shift_by(step_size: Int): Int {
        return sum_all + step_size
    }
}

fun add_two(n_value: Int): Int {
    return n_value + 2
}

fun build_greeting(): String {
    return "hi"
}

fun main() {
    val my_name = "a_b"
    println(MAX_SIZE)
    println(my_name)
    println(add_two(3))
    val p = Point2D(1, 2)
    println(p.sum_all)
    println(p.shift_by(4))
    println("size is $MAX_SIZE and $my_name")
    val v1a = 7
    println(v1a * 2)
    var total_count = 0
    for (i in 0 until MAX_SIZE) {
        total_count += i
    }
    println(total_count)
    println(GREETING.length)
    println(GREETING.uppercase())
    println(BOX.sum_all)
}
