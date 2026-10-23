import java.time.LocalDate

// LocalDate has getYear() and no setYear(): for Kotlin, year is a val
fun main() {
    val d = LocalDate.now()
    d.year = 2020
}
