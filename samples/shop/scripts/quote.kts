import java.math.BigDecimal
import shop.Money

// uses the shared lib/ code
val price = BigDecimal(params["price"] ?: "0")
val tax = (params["tax"] ?: "0").toInt()
response["gross"] = Money.withTax(price, tax)
response["text"] = Money.format(Money.withTax(price, tax))
