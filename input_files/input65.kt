class Config(var n: Int) {
    init {
        n = n * 2
    }
}

fun main() {
    println(Config(3).n)
    val a = "ana"
    val b = "bob"
    if (a < b) {
        println("before")
    }
    if (b > a) {
        println("after")
    }
}
