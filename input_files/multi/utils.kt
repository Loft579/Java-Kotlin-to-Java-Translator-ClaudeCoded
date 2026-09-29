val VERSION = 2

fun twice(n: Int): Int {
    return n * 2
}

class Counter(var value: Int) {
    fun next(): Int {
        return value + 1
    }
}
