// THROWAWAY spike (spikes/kotlin-native): the script definition. A script sees params, steps, response and request,
// exactly like the shop scenario's scripts expect. The same definition serves the Dev server (evaluate) and the CLI (compile ahead of time).
package io.mq.script

import kotlin.reflect.typeOf
import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.providedProperties

// Non-generic wrapper types: the command-line compiler loses the type arguments of provided properties (Map<String, String> arrives as Map<K, V>).
class Params(private val m: Map<String, String>) : Map<String, String> by m
class Steps(private val m: Map<String, Any?>) : Map<String, Any?> by m
class Response(private val m: MutableMap<String, Any?> = LinkedHashMap()) : MutableMap<String, Any?> by m

class RequestInfo(val id: String?, val pid: String?, val uid: String?, val method: String)

@KotlinScript(fileExtension = "kts", compilationConfiguration = MqCompilation::class)
abstract class MqScript

object MqCompilation : ScriptCompilationConfiguration({
    providedProperties(
        "params" to typeOf<Params>(),
        "steps" to typeOf<Steps>(),
        "response" to typeOf<Response>(),
        "request" to typeOf<RequestInfo>(),
    )
})
