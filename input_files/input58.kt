fun main() {
    val line = "hello world hello kotlin"
    val words = line.split(" ")
    println(words.size)
    for (w in words) {
        println(w.toUpperCase())
    }
    println(line.indexOf("world"))
    println(line.substring(0, 5))
    println(line.trim().length)
}
