// += reads and writes back, naming the receiver twice: the call would run twice
fun main() {
    Thread.currentThread().name += "!"
}
