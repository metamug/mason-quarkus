# Test 2: Script to mpath to Sql chains over HTTP, Dev server loader, JVM and native

Quarkus 3.40.1, Kotlin 2.4.10, Temurin 25 (JVM), Mandrel 25 (native), PostgreSQL 16, `ubuntu-latest` 4 vCPU 15,989 MB RAM. Run `37105433783` (workflow `shop-kotlin.yml`). Input: R2's shop scenario, `samples/shop` (7 resources, 5 Kotlin scripts, schema), and the script-related checks of `mcp/scenarios/shop/run.mjs` ported to `examples/shop-kt/http-test.mjs` (18 checks).

## 1. Outcome

**Pass in all three modes**, 18 of 18 checks each: (A) the Dev server loader compiling the `.kts` files at run time on the JVM, (B) scripts compiled ahead of time, on the JVM, (C) scripts compiled ahead of time in the native binary. The same resources, the same scripts, the same HTTP checks. The Groovy script is rewritten in Kotlin; the validator rejects any non-Kotlin script file with a clear error.

## 2. What the checks cover

- **Script feeds Sql through mpath:** registration runs `hashpw` (salted SHA-256), then `INSERT ... VALUES ($name, $email, $[h].hash)`; the stored value matches `salt$hex`, the password is never echoed, a duplicate email is rejected.
- **One script, two request kinds:** `tokens` serves `/token/{id}` (digests of the id, compared with Node's own SHA-256, MD5, base64) and `/token?count=5` (5 distinct UUIDs).
- **Sql result into a script, script result into a `when`:** `PUT /order/{id}`: `cur` (Sql) then `move` (state machine script) then `apply` (Sql with `when="$[move].ok eq true"`). `NEW to SHIPPED` is refused by the script and the update step is skipped; `NEW to PAID` runs and returns the declared 202; an unknown order is reported by the script.
- **The former Groovy script, in Kotlin:** sees its request parameter.

## 3. Numbers (observed)

| | A: Dev loader (JVM) | B: compiled, JVM | C: compiled, native |
|---|---|---|---|
| Checks | 18 / 18 | 18 / 18 | 18 / 18 |
| Resident memory after the checks | 581 MB | 138 MB | 65.7 MB |
| "started in" | 0.73 s | 0.63 s | n/a (see example report) |
| Native build (Mandrel 25, with scripts) | | | 1:59.7 wall, 3.26 GB peak, binary 61.5 MB |

The Dev loader costs memory (the Kotlin compiler is in the process): 581 MB against 138 MB. First request to a script pays the compile (not measured separately here; `mq-script`'s test compiles three versions of a script in a few seconds on a warm JVM).

## 4. Design that came out of it

- **Contract in Java, in `mq-engine`:** `Params` (text values, like the shop scripts use `params["qty"]?.toIntOrNull()`), `Steps`, `Response`, `RequestInfo`, `ScriptLoader`. The Kotlin script definition `MqScript` (module `mq-script`) refers to them. One contract, two implementations of `ScriptLoader`.
- **Dev server:** `DevScriptLoader` compiles `<scripts dir>/<name>.kts` once per version of the file (modification time) and evaluates it per request. A changed file is picked up with no restart; a compile error names the file and line (`hello.kts:1`).
- **CLI / production:** `tools/mq-compile-scripts` compiles the scripts with the Kotlin compiler (started directly with the scripting plugin) against a declared class path, and generates `MqCompiledScripts`, a `ScriptLoader` that calls each compiled class's constructor. It is registered as a Java service; the extension registers the service for native at build time. No reflection.
- **Choosing at run time:** `quarkus.mq.scripts` is `interpreted`, `compiled` or `auto` (interpreted in dev mode when the compiler is on the class path). The compiler class is found by a name built at run time, so a production or native build never pulls it in.

## 5. Surprises

1. **Kotlin versions clash.** The Quarkus 3.40.1 BOM manages Kotlin libraries at **2.4.10**. Mixing in the scripting host at 2.2.20 produced `NoSuchMethodError ...K2JVMCompilerArguments.getUseJavac()` at the first script compile, inside the application only (the plain JVM unit test passed). Everything is now aligned on 2.4.10, including the compiler distribution the CLI uses. The CLI's Kotlin version must follow the Quarkus version it builds with.
2. **Inside a Quarkus fast-jar the application jars are not on `java.class.path`**, so a scripting host that uses the current class path cannot see `mq-engine`. The extension passes the list (`lib/main/*.jar`). A real Dev server (running `quarkus:dev` or its own launcher) must pass its resolved class path the same way; that class path is also the "declared libraries" for the Dev server.
3. Failed requests were silent (`errorId` only): the engine now reports every 5xx to the server log. The first run of the interpreted mode was undiagnosable without it.

## 6. Open questions for a human

1. Is 581 MB per Dev-server backend acceptable when scripts are interpreted? With lazy stop and few backends running, probably; a shared scripting host across backends would cut it.
2. The Dev server's class path for scripts: should the project declare libraries (one list, used by both the Dev server and the CLI) as proposed in the Kotlin-native report?

## 7. Recommended change to the R2 Next spec

"A script step runs a Kotlin script named by `file` (`name` or `name.kts`) with `params`, `steps`, `response` and `request`. The Dev server compiles and evaluates the file and picks up changes; the CLI compiles ahead of time. Both use the same script definition."
