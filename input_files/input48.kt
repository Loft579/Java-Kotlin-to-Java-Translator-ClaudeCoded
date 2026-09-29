class Engine(var power: Int)

class Car(var engine: Engine, var name: String)

fun clasify(n: Int): String {
    if (n < 0) {
        return "neg"
    } else if (n == 0) {
        return "zero"
    } else {
        return "pos"
    }
}

fun main() {
    val car = Car(Engine(120), "auto")
    println(car.name)
    println(car.engine.power)
    car.engine.power = 150
    println(car.engine.power)
    println(clasify(-5))
    println(clasify(0))
    println(clasify(7))
}
