interface Payable {
    fun pay(): Double
    val label: String
}

abstract class Employee(val name: String, var salary: Double) : Payable {
    val initial: String get() = name.toUpperCase()
    abstract fun role(): String
    fun describe(): String {
        return "$name (${role()}) earns ${pay()}"
    }
}

class Manager(name: String, salary: Double, var bonus: Double) : Employee(name, salary) {
    override val label: String get() = "manager"
    override fun role(): String {
        return label
    }
    override fun pay(): Double {
        return salary + bonus
    }
}

class Developer(name: String, salary: Double, var langs: List<String>) : Employee(name, salary) {
    override val label: String get() = "dev"
    override fun role(): String {
        return label
    }
    override fun pay(): Double {
        return salary
    }
    fun knows(lang: String): Boolean {
        return langs.contains(lang)
    }
}

class Team(val title: String, val members: List<Employee>) {
    val size: Int get() = members.size
    fun total(): Double {
        var sum = 0.0
        for (m in members) {
            sum = sum + m.pay()
        }
        return sum
    }
    fun best(): Employee {
        var top = members.get(0)
        for (m in members) {
            if (m.pay() > top.pay()) {
                top = m
            }
        }
        return top
    }
    fun report() {
        println("team $title with $size people")
        for (m in members) {
            println(m.describe())
        }
        println("total ${total()}")
    }
}

fun classify(amount: Double): String {
    return when {
        amount > 20000.0 -> "high"
        amount > 5000.0 -> "mid"
        else -> "low"
    }
}

fun countDevs(team: Team): Int {
    var devs = 0
    for (m in team.members) {
        if (m is Developer) {
            devs = devs + 1
        }
    }
    return devs
}

fun main() {
    val ana = Manager("ana", 9000.0, 2000.0)
    val bob = Developer("bob", 7000.0, listOf("kotlin", "java"))
    val cat = Developer("cat", 8000.0, listOf("java"))
    val team = Team("core", listOf(ana, bob, cat))
    team.report()
    println(classify(team.total()))
    println(team.best().name)
    println(countDevs(team))
    println(bob.knows("kotlin"))
    println(cat.knows("kotlin"))
    fun label(n: Int): String {
        return "n=$n"
    }
    for (i in 0 until team.size) {
        println(label(i))
    }
    println(ana.initial)
}
