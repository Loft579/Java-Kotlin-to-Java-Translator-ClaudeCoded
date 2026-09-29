open class Animal(val name: String)
class Dog(name: String, val breed: String) : Animal(name)
class Cat(name: String, val lives: Int) : Animal(name)

fun show(a: Animal): String {
    if (a is Dog && a.breed.length > 2) {
        return "dog " + a.breed
    }
    if (a !is Cat) {
        return "not a cat " + a.name
    }
    return "cat " + a.lives
}

fun onRight(a: Animal, flag: Boolean): String {
    if (flag && a is Dog) {
        return "right " + a.breed
    }
    return "no"
}

fun guarded(a: Animal, flag: Boolean): String {
    if (a !is Dog || flag) {
        return "out"
    }
    return "in " + a.breed
}

fun onlyDogs(pets: List<Animal>) {
    for (p in pets) {
        if (p !is Dog) {
            continue
        }
        println("kept " + p.breed)
    }
}

fun main() {
    onlyDogs(listOf(Dog("rex", "lab"), Cat("tom", 9), Dog("z", "pug")))
    println(show(Dog("rex", "lab")))
    println(show(Dog("z", "xy")))
    println(show(Cat("tom", 9)))
    println(onRight(Dog("a", "pug"), true))
    println(onRight(Cat("c", 1), true))
    println(guarded(Dog("a", "pug"), false))
    println(guarded(Dog("a", "pug"), true))
}
