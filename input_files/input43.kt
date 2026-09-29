import kotlin.text.*

fun main() {
    val date = Regex("([0-9]+)-([0-9]+)")
    val hit = date.find("on 12-25 ok")
    println(hit?.value)
    println(hit.groupValues)
    println(hit.groupValues.get(1))
    val all = date.findAll("1-2 and 3-4")
    for (one in all) {
        println(one)
    }
    println(date.matchEntire("12-25"))
    println(date.matchEntire("nope"))
    val digits = Regex("[0-9]+")
    println(digits.matches("123"))
    println(digits.replace("a1b2", "-"))
}
