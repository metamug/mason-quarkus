package io.mq.script

import kotlin.reflect.typeOf
import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.providedProperties

/**
 * The script definition: a MQ script sees [Params], [Steps], [Response] and [RequestInfo] under the names params, steps,
 * response and request. The same definition is used by the Dev server (evaluate on the JVM) and by the CLI (compile ahead of time).
 * The contract classes are Java classes in mq-engine, because the command-line compiler loses the type arguments of generic provided properties.
 */
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
