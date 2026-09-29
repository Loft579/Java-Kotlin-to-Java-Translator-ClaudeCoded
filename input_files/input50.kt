class Point(var x: Int, var y: Int)

fun origin(): Point {
    return Point(0, 0)
}

fun shift(p: Point, dx: Int): Point {
    p.x = p.x + dx
    return p
}

fun main() {
    val p: Point = origin()
    val q = shift(p, 5)
    println(q.x)
    println(q.y)
    val same = p == q
    println(same)
}
