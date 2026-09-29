class Money(val cents: Int) {
    override fun toString(): String {
        return "c" + cents
    }
    override fun equals(other: Any?): Boolean {
        if (other is Money) {
            return other.cents == cents
        }
        return false
    }
    override fun hashCode(): Int {
        return cents
    }
}

fun main() {
    val a = Money(150)
    val b = Money(150)
    val c = a
    println(a)
    println(a == b)
    println(a === b)
    println(a === c)
    println(a !== b)
    val items = listOf(a)
    println(items.contains(b))
    println("price $a")

    var n = 10
    n %= 3
    println(n)

    val words = mutableListOf("a")
    words += "b"
    println(words)
    words -= "a"
    println(words)
    val nums = mutableListOf(10, 20, 30)
    nums += 40
    nums -= 20
    println(nums)
    val tags = mutableSetOf("x")
    tags += "y"
    println(tags)
}
