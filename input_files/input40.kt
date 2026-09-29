fun main() {
    val words = listOf("apple", "kiwi")
    println(words.get(0).toUpperCase())
    println("42".toIntOrNull())
    val text = "7"
    val n = text.toIntOrNull()
    println(n?.toString())
    val s = "abc"
    println(s?.length)
    println(Math.sqrt(9.0).toString())
}
