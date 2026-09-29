interface Named {
    val label: String
}

interface Sized : Named {
    fun size(): Int
}

open class Base(val name: String) {
    open fun greet(): String {
        return "hello $name"
    }
}

class Loud(name: String) : Base(name), Sized {
    override val label: String get() = "loud"
    override fun size(): Int {
        return name.length
    }
    override fun greet(): String {
        return super.greet() + "!"
    }
}

fun main() {
    println(Base("a").greet())
    val l = Loud("bob")
    println(l.greet())
    println(l.label)
    println(l.size())
}
