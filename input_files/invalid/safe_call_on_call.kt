// '?.' on the result of a call would evaluate the call twice.
fun main() {
    val text = "7"
    println(text.toIntOrNull()?.toString())
}
