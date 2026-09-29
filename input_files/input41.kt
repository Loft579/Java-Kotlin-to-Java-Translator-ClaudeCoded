import kotlin.text.*

fun main() {
    val digits = Regex("[0-9]+")
    println(digits.matches("123"))
    println(digits.containsMatchIn("abc42def"))
    val spaces = Regex("\\s+")
    println(spaces.replace("a  b   c", "-"))
    val pat = "[a-z]+"
    println(pat.toRegex().matches("abc"))
}
