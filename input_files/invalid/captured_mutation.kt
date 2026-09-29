// A local function cannot assign to a variable it captures once hoisted.
fun main() {
    var count = 0
    fun bump() {
        count += 1
    }
    bump()
    println(count)
}
