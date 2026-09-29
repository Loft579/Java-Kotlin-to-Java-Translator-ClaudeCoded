import import_cycle_b

val SHARED = "a"

fun fromA(): String {
    return "A" + fromB()
}
