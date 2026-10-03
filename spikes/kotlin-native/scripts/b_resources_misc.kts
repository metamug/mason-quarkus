import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import java.util.ResourceBundle
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

// boundary: bundled resources, time zones, locale formatting, regex, coroutines, an HTTP client object
fun <T> attempt(f: () -> T): String = try { f().toString() } catch (t: Throwable) { "FAIL " + t.javaClass.simpleName + " " + (t.message ?: "").take(80) }

response["classpath_resource"] = attempt { object {}.javaClass.getResourceAsStream("/greeting.txt")!!.readBytes().decodeToString().trim() }
response["resource_bundle"] = attempt { ResourceBundle.getBundle("messages", Locale.ROOT).getString("hello") }
response["time_zone"] = attempt { ZonedDateTime.of(2026, 1, 2, 3, 4, 5, 0, ZoneId.of("Asia/Kolkata")).offset.toString() }
response["locale_format"] = attempt { String.format(Locale.GERMANY, "%,.2f", 1234.5) }
response["regex"] = attempt { Regex("(\\d+)-(\\w+)").find("42-abc")!!.groupValues[2] }
response["coroutines"] = attempt { runBlocking { val a = async { 20 }; val b = async { 22 }; a.await() + b.await() } }
response["http_client_object"] = attempt { java.net.http.HttpClient.newHttpClient().version().toString() }
response["bigdecimal"] = attempt { java.math.BigDecimal("4.50").multiply(java.math.BigDecimal(3)).toPlainString() }
response["uuid_random"] = attempt { java.util.UUID.randomUUID().toString().length }
