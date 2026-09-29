interface Tagged {
    val name: String
    val tag: String get() = "plain"
    fun describe(): String {
        return name + ":" + tag
    }
    fun twice(): String = describe() + "|" + describe()
}

class Item(override val name: String) : Tagged

class Special(override val name: String) : Tagged {
    override val tag: String get() = "special"
    override fun describe(): String {
        return "!" + name
    }
}

fun show(t: Tagged): String {
    return t.describe()
}

fun main() {
    val i = Item("box")
    println(i.name)
    println(i.tag)
    println(i.describe())
    println(i.twice())
    val s = Special("gem")
    println(s.tag)
    println(s.describe())
    println(show(s))
    println(show(i))
}
