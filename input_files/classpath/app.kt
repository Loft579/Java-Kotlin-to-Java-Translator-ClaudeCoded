import demo.widget.Label

fun main() {
    val label = Label.of("hola mundo")
    println(label.text)
    label.text = "chau"
    label.text += " mundo"
    println(label.text)
    label.isEnabled = false
    println(label.isEnabled)
    label.visibility = Label.GONE
    println(label.visibility == Label.GONE)
    label.tag = 5
    println(label.tag)
    println(label.url)
    for (word in label.words) {
        println(word.length)
    }
    val copy = label.copy()
    println(copy.text.length)
    val twice = label.measure(3)
    println(twice + 1)
    val half = label.measure(3.0)
    println(half)
}
