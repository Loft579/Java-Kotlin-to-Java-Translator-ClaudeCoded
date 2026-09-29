// matchesAt needs Matcher regions, which this mapping does not cover.
fun main() {
    val re = Regex("[0-9]+")
    println(re.matchesAt("a1b", 1))
}
