open class Base(val id: Int) {
    open val label: String get() = "base"
    open fun tag(): String {
        return "b" + id
    }
}

class Child(id: Int) : Base(id) {
    override val label: String get() = "child"
    val twice: Int get() = id * 2
    override fun tag(): String {
        val parent = super.label
        val mine = this.label
        val n = this.twice
        return super.tag() + "/" + parent + "/" + mine + "/" + n + "/" + parent.length
    }
    fun me(): Child {
        return this
    }
}

fun main() {
    println(Child(3).tag())
    println(Child(4).me().twice)

    val text = "Mixed Case"
    println(text.uppercase())
    println(text.lowercase())

    val xs = mutableListOf(10, 20, 30)
    val gone = xs.removeAt(0)
    println(gone + 1)
    val ok = xs.remove(30)
    println(ok)
    println(xs)
}
