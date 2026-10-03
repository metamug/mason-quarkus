package io.mq.script

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.script.experimental.api.CompiledScript
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.api.providedProperties
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.script.experimental.jvmhost.createJvmCompilationConfigurationFromTemplate
import kotlinx.coroutines.runBlocking

/**
 * The Dev server's loader: compiles `<dir>/<name>.kts` on first use and again when the file changes (compile once per version, call
 * per request), then evaluates it with the four names. What a script may import is what is on the Dev server's class path.
 */
class DevScriptLoader(private val dir: Path) : ScriptLoader {

    private class Compiled(val modified: Long, val script: CompiledScript)

    private val host = BasicJvmScriptingHost()
    private val cache = ConcurrentHashMap<String, Compiled>()
    private val compilation = createJvmCompilationConfigurationFromTemplate<MqScript> {
        jvm { dependenciesFromCurrentContext(wholeClasspath = true) }
    }

    override fun has(name: String): Boolean = Files.isRegularFile(dir.resolve("$name.kts"))

    override fun run(name: String, params: Params, steps: Steps, response: Response, request: RequestInfo) {
        val file = dir.resolve("$name.kts")
        val modified = Files.getLastModifiedTime(file).toMillis()
        var compiled = cache[name]
        if (compiled == null || compiled.modified != modified) {
            synchronized(this) {
                compiled = cache[name]
                if (compiled == null || compiled!!.modified != modified) {
                    compiled = Compiled(modified, compile(name, file))
                    cache[name] = compiled!!
                }
            }
        }
        val evaluation = ScriptEvaluationConfiguration {
            providedProperties("params" to params, "steps" to steps, "response" to response, "request" to request)
        }
        val result = runBlocking { host.evaluator(compiled!!.script, evaluation) }
        when (result) {
            is ResultWithDiagnostics.Success -> {
                val value = result.value.returnValue
                if (value is ResultValue.Error) {
                    throw RuntimeException("script '$name' failed: ${value.error}", value.error)
                }
            }
            is ResultWithDiagnostics.Failure -> throw RuntimeException("script '$name' could not run: " + messages(name, result.reports))
        }
    }

    private fun compile(name: String, file: Path): CompiledScript {
        val result = runBlocking { host.compiler(file.toFile().toScriptSource(), compilation) }
        return when (result) {
            is ResultWithDiagnostics.Success -> result.value
            is ResultWithDiagnostics.Failure -> throw RuntimeException("script '$name' does not compile: " + messages(name, result.reports))
        }
    }

    private fun messages(name: String, reports: List<ScriptDiagnostic>): String =
        reports.filter { it.severity >= ScriptDiagnostic.Severity.ERROR }
            .joinToString("; ") { "$name.kts:${it.location?.start?.line ?: 0}: ${it.message}" }
}
