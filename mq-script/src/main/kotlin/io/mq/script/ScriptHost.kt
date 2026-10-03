package io.mq.script

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.mq.engine.Json
import java.io.File
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * EXPERIMENTAL (measured in docs/reports/shared-host.md). One scripting host process for many backends: it holds the Kotlin compiler and
 * compiles scripts on request; a backend loads the resulting classes and does not need the compiler in its own process.
 *
 * POST /export  {"scripts": dir, "lib": dir or "", "name": script, "classpath": [jar, ...]}  ->  a jar (200), or the compile error as text (422)
 * GET  /stats   what the host holds
 */
object ScriptHost {

    private val loaders = ConcurrentHashMap<String, DevScriptLoader>()

    @JvmStatic
    fun main(args: Array<String>) {
        val port = args.getOrNull(0)?.toInt() ?: 9500
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
        server.executor = Executors.newFixedThreadPool(4)
        server.createContext("/export") { x -> export(x) }
        server.createContext("/stats") { x -> reply(x, 200, "{\"projects\":${loaders.size}}".toByteArray()) }
        server.start()
        println("script host on :$port")
    }

    @Suppress("UNCHECKED_CAST")
    private fun export(x: HttpExchange) {
        try {
            val req = Json.parse(String(x.requestBody.readAllBytes(), StandardCharsets.UTF_8)) as Map<String, Any?>
            val scripts = req["scripts"] as String
            val lib = (req["lib"] as? String)?.takeIf { it.isNotEmpty() }
            val classpath = (req["classpath"] as? List<String>)?.map { File(it) }
            val key = scripts + "|" + lib + "|" + (classpath?.hashCode() ?: 0)
            val loader = loaders.computeIfAbsent(key) { DevScriptLoader(Path.of(scripts), classpath, lib?.let { Path.of(it) }) }
            val jar = synchronized(loader) { loader.exportJar(req["name"] as String) }
            x.responseHeaders.add("Content-Type", "application/java-archive")
            reply(x, 200, jar)
        } catch (t: Throwable) {
            reply(x, 422, (t.message ?: t.toString()).toByteArray())
        }
    }

    private fun reply(x: HttpExchange, status: Int, body: ByteArray) {
        x.sendResponseHeaders(status, body.size.toLong())
        x.responseBody.use { it.write(body) }
    }
}
