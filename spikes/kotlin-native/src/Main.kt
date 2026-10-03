// THROWAWAY spike (spikes/kotlin-native): runs the compiled scripts from the generated registry (no reflection) and checks them.
//   Main run <script> <json>      one script, prints its response as JSON
//   Main selftest <smoke.json>    every case of the smoke file; prints PASS/FAIL per check, exit 1 on a required failure
import io.mq.engine.Json
import io.mq.script.Params
import io.mq.script.RequestInfo
import io.mq.script.Response
import io.mq.script.Steps
import java.io.File
import kotlin.system.exitProcess

@Suppress("UNCHECKED_CAST")
fun runScript(name: String, input: Map<String, Any?>): Map<String, Any?> {
    val factory = ScriptRegistry.scripts[name] ?: error("no compiled script '$name'")
    val params = ((input["params"] as? Map<String, Any?>) ?: emptyMap()).mapValues { it.value.toString() }
    val steps = (input["steps"] as? Map<String, Any?>) ?: emptyMap()
    val req = input["request"] as? Map<String, Any?>
    val response = Response()
    factory.create(Params(params), Steps(steps), response, RequestInfo(req?.get("id")?.toString(), req?.get("pid")?.toString(), null, "GET"))
    return LinkedHashMap(response)
}

@Suppress("UNCHECKED_CAST")
fun selftest(file: File): Int {
    val cases = Json.parse(file.readText()) as List<Map<String, Any?>>
    var requiredFailed = 0
    var pass = 0
    var boundaryFail = 0
    for (c in cases) {
        val name = c["script"] as String
        val boundary = c["boundary"] == true
        val response = try {
            runScript(name, c)
        } catch (t: Throwable) {
            println("FAIL $name: script threw $t")
            if (boundary) boundaryFail++ else requiredFailed++
            continue
        }
        val expect = (c["expect"] as Map<String, Any?>)
        for ((key, regex) in expect) {
            val actual = Json.write(response[key]).removeSurrounding("\"")
            val ok = Regex(regex.toString()).containsMatchIn(actual)
            if (ok) {
                pass++
                println("PASS $name.$key")
            } else {
                println("FAIL $name.$key: got '${actual.take(120)}', wanted /$regex/")
                if (boundary) boundaryFail++ else requiredFailed++
            }
        }
    }
    println("selftest: $pass passed, $requiredFailed required failures, $boundaryFail boundary constructs failed")
    return if (requiredFailed == 0) 0 else 1
}

fun main(args: Array<String>) {
    when (args.getOrNull(0)) {
        "run" -> println(Json.write(runScript(args[1], Json.parse(args.getOrElse(2) { "{}" }) as Map<String, Any?>)))
        "selftest" -> exitProcess(selftest(File(args[1])))
        else -> {
            System.err.println("usage: run <script> <json> | selftest <smoke.json>")
            exitProcess(2)
        }
    }
}
