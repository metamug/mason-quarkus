import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

// boundary: serialization. kotlinx.serialization (compiler plugin, no reflection) and Java serialization (reflection based).
fun <T> attempt(f: () -> T): String = try { f().toString() } catch (t: Throwable) { "FAIL " + t.javaClass.simpleName + " " + (t.message ?: "").take(80) }

@Serializable
data class Item(val name: String, val qty: Int)

class Plain(val a: Int) : java.io.Serializable

response["kotlinx_json_encode"] = attempt { Json.encodeToString(Item("pen", 3)) }
response["kotlinx_json_decode"] = attempt { Json.decodeFromString<Item>("""{"name":"ink","qty":7}""").qty }
response["kotlinx_json_tree"] = attempt { Json.parseToJsonElement("""{"a":[1,2,3]}""").toString() }
response["java_serialization_roundtrip"] = attempt {
    val out = ByteArrayOutputStream()
    ObjectOutputStream(out).use { it.writeObject(Plain(5)) }
    (ObjectInputStream(ByteArrayInputStream(out.toByteArray())).readObject() as Plain).a
}
