package io.mq.script

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.script.experimental.api.CompiledScript
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.api.providedProperties
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvm.baseClassLoader
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.updateClasspath
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.script.experimental.jvmhost.createJvmCompilationConfigurationFromTemplate
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler

/**
 * The Dev server's loader: compiles `<dir>/<name>.kts` on first use and again when the file changes (compile once per version, call
 * per request), then evaluates it with the four names. What a script may import is what is on the Dev server's class path.
 *
 * With a [libDir] of ordinary Kotlin files (the lib folder) the scripts can share code: the folder is compiled to classes when any file in it
 * changes, and every script is compiled again against the new classes on its next call.
 */
class DevScriptLoader @JvmOverloads constructor(
    private val dir: Path,
    private val classpath: List<File>? = null,
    private val libDir: Path? = null,
) : ScriptLoader {

    private class Compiled(val modified: Long, val libVersion: String, val script: CompiledScript)

    /** the compiled lib folder: where the classes are, a class loader over them, and a signature of the sources they came from */
    private class Lib(val signature: String, val out: File?, val loader: ClassLoader)

    private val host = BasicJvmScriptingHost()
    private val cache = ConcurrentHashMap<String, Compiled>()
    private val baseLoader: ClassLoader = DevScriptLoader::class.java.classLoader
    private var lib = Lib("", null, baseLoader)

    /** the script compilation configuration for a given compiled lib (or none) */
    private fun compilationFor(libOut: File?): ScriptCompilationConfiguration =
        createJvmCompilationConfigurationFromTemplate<MqScript> {
            // inside Quarkus the jars are not on java.class.path: the application passes the list it knows
            jvm {
                if (classpath != null) updateClasspath(classpath) else dependenciesFromCurrentContext(wholeClasspath = true)
                if (libOut != null) updateClasspath(listOf(libOut))
            }
        }

    override fun has(name: String): Boolean = Files.isRegularFile(dir.resolve("$name.kts"))

    override fun run(name: String, params: Params, steps: Steps, response: Response, request: RequestInfo) {
        val current = refreshLib()
        val file = dir.resolve("$name.kts")
        val modified = Files.getLastModifiedTime(file).toMillis()
        var compiled = cache[name]
        if (compiled == null || compiled.modified != modified || compiled.libVersion != current.signature) {
            synchronized(this) {
                compiled = cache[name]
                if (compiled == null || compiled!!.modified != modified || compiled!!.libVersion != current.signature) {
                    compiled = Compiled(modified, current.signature, compile(name, file, current))
                    cache[name] = compiled!!
                }
            }
        }
        val evaluation = ScriptEvaluationConfiguration {
            providedProperties("params" to params, "steps" to steps, "response" to response, "request" to request)
            jvm { baseClassLoader(current.loader) }
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

    private fun compile(name: String, file: Path, current: Lib): CompiledScript {
        val result = runBlocking { host.compiler(file.toFile().toScriptSource(), compilationFor(current.out)) }
        return when (result) {
            is ResultWithDiagnostics.Success -> result.value
            is ResultWithDiagnostics.Failure -> throw RuntimeException("script '$name' does not compile: " + messages(name, result.reports))
        }
    }

    private fun messages(name: String, reports: List<ScriptDiagnostic>): String =
        reports.filter { it.severity >= ScriptDiagnostic.Severity.ERROR }
            .joinToString("; ") { "$name.kts:${it.location?.start?.line ?: 0}: ${it.message}" }

    // ---------------------------------------------------------------- the shared lib folder

    /** compiles the lib folder when a file in it was added, removed or changed; returns the classes to use */
    @Synchronized
    private fun refreshLib(): Lib {
        val d = libDir ?: return lib
        val files = if (Files.isDirectory(d)) Files.walk(d).use { s -> s.filter { it.toString().endsWith(".kt") }.sorted().toList() } else emptyList()
        val signature = files.joinToString(";") { "${d.relativize(it)}:${Files.getLastModifiedTime(it).toMillis()}:${Files.size(it)}" }
        if (signature == lib.signature) return lib
        if (files.isEmpty()) {
            lib = Lib(signature, null, baseLoader)
            return lib
        }
        val out = Files.createTempDirectory("mq-lib").toFile()
        val log = ByteArrayOutputStream()
        val cp = (classpath ?: System.getProperty("java.class.path").split(File.pathSeparator).map { File(it) }).joinToString(File.pathSeparator)
        val args = mutableListOf("-no-stdlib", "-nowarn", "-cp", cp, "-d", out.absolutePath)
        args.addAll(files.map { it.toString() })
        val code = K2JVMCompiler().exec(PrintStream(log, true, "UTF-8"), *args.toTypedArray())
        if (code != ExitCode.OK) {
            // the old classes keep serving until the lib compiles again; the error names file and line
            throw RuntimeException("lib does not compile: " + log.toString("UTF-8").lines().filter { it.contains("error:") }.joinToString("; ") { it.trim() })
        }
        val old = lib
        lib = Lib(signature, out, URLClassLoader(arrayOf(out.toURI().toURL()), baseLoader))
        (old.loader as? URLClassLoader)?.close()
        old.out?.deleteRecursively()
        return lib
    }
}
