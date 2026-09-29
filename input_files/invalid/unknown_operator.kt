// '^' is not in the grammar: a lexer error must abort, not drop the operator.
fun main() {
    println(7 ^ 3)
}
