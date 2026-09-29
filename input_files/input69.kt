class Node(var name: String, var next: Node?)

fun main() {
    val b = Node("b", null)
    val a = Node("a", b)
    println(a.name)
    println(a.next?.name)
    println(b.next?.name)
    if (a.next != null) {
        println("has next")
    }
    val label = when (b.next) {
        null -> "end"
        else -> "more"
    }
    println(label)
}
