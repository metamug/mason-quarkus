package shop

import java.math.BigDecimal
import java.math.RoundingMode

/** Shared code for the scripts: ordinary Kotlin, not a script. Compiled before them by the Dev server and by the CLI. */
object Money {
    fun withTax(price: BigDecimal, percent: Int): BigDecimal =
        price.multiply(BigDecimal(100 + percent)).divide(BigDecimal(100), 2, RoundingMode.HALF_UP)

    fun format(amount: BigDecimal): String = "EUR " + amount.setScale(2, RoundingMode.HALF_UP).toPlainString()
}
