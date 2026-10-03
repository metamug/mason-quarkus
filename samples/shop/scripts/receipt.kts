import java.math.BigDecimal
import shop.Money

// a second script that uses the same shared lib/ code: the item request id is the quantity
val qty = (request.id ?: "1").toInt()
val unit = BigDecimal(params["price"] ?: "0")
response["qty"] = qty
response["total"] = Money.format(unit.multiply(BigDecimal(qty)))
