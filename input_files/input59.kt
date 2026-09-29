class Account(val owner: String, var balance: Double) {
    fun deposit(amount: Double) {
        if (amount > 0.0) {
            balance = balance + amount
        }
    }
    fun withdraw(amount: Double): Boolean {
        if (amount > balance) {
            return false
        }
        balance = balance - amount
        return true
    }
    fun state(): String {
        return "$owner has $balance"
    }
}

class Bank(val accounts: List<Account>) {
    fun find(owner: String): Account {
        for (a in accounts) {
            if (a.owner == owner) {
                return a
            }
        }
        return accounts.get(0)
    }
    fun total(): Double {
        var t = 0.0
        for (a in accounts) {
            t = t + a.balance
        }
        return t
    }
}

fun main() {
    val bank = Bank(listOf(Account("ana", 100.0), Account("bob", 50.0)))
    val ana = bank.find("ana")
    ana.deposit(25.0)
    println(ana.state())
    println(bank.find("bob").withdraw(80.0))
    println(bank.find("bob").withdraw(20.0))
    println(bank.find("bob").state())
    println(bank.total())
    var round = 0
    while (round < 3) {
        ana.deposit(10.0)
        round = round + 1
    }
    println(ana.state())
}
