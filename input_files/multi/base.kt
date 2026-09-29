val SUFFIX = "!"

abstract class Greeter(val name: String) {
    abstract fun kind(): String
    fun hello(): String {
        return "hello " + name + SUFFIX
    }
}
