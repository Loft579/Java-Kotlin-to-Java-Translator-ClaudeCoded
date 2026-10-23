import java.time.LocalDate
import java.time.Month
import java.time.ZoneId

// a class of the file named like a library one: the library's gets qualified
class DayOfWeek(val n: Int)

fun main() {
    val d = LocalDate.of(2024, 2, 29)
    val y = d.getYear()
    println(y + 1)
    println(d.year)
    println(d.isLeapYear)
    println(d.month)
    val month = d.month
    println(month.name.length + month.ordinal)
    val next = d.plusDays(1)
    println(next.dayOfMonth)
    println(next.month == Month.MARCH)
    val weekday = next.dayOfWeek
    println(weekday)
    println(DayOfWeek(3).n)
    val sb = StringBuilder()
    sb.append("ab").append(1)
    println(sb.length)
    val t = Thread.currentThread()
    t.name = "worker"
    t.name += "!"
    println(t.name)
    println(Integer.MAX_VALUE)
    val zones = ZoneId.getAvailableZoneIds()
    println(zones.size > 10)
    val ranges = Locale.LanguageRange.parse("en-US,fr")
    for (r in ranges) {
        println(r.range)
    }
    val first = ranges[0]
    println(first.weight)
}
