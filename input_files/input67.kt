fun label(s: String?): String {
    return when (s) {
        null -> "none"
        "a" -> "alpha"
        else -> "other"
    }
}

fun describe(n: Int): String {
    return when (n) {
        1 -> {
            "one"
        }
        2 -> "two"
        else -> {
            "many"
        }
    }
}

fun main() {
    println(label(null))
    println(label("a"))
    println(label("z"))
    val n = 1
    when (n) {
        1 -> {
            println("one")
            println("first")
        }
        2 -> println("two")
        else -> {
            println("other")
        }
    }
    println(describe(1))
    println(describe(9))
    for (i in 0 until 3) {
        when (i) {
            1 -> {
                println("skip")
                continue
            }
            else -> println("i=$i")
        }
    }
}
