abstract class Shape(val name: String) {
    abstract fun area(): Double
}

class Circle(var r: Double) : Shape("circle") {
    override fun area(): Double {
        return 3.14 * r * r
    }
}

class Square(var side: Double) : Shape("square") {
    override fun area(): Double {
        return side * side
    }
}

fun main() {
    val shapes = listOf(Circle(1.0), Square(2.0))
    for (s in shapes) {
        println(s.name)
        println(s.area())
    }
}
