# Tests 3 and 4: XRequest and Execute, over HTTP on the JVM and in the native binary

Quarkus 3.40.1, Kotlin 2.4.10, Temurin 25 (JVM), Mandrel 25 (native), PostgreSQL 16, `ubuntu-latest` 4 vCPU 15,989 MB RAM. Runs `37105904165` (XRequest) and `37106311222` (Execute); workflow `shop-kotlin.yml`. Unit tests: `XRequestTest` (7), `ExecuteTest` (3), `ShopOrderTest` (3), `ShopScriptsTest` (5).

## 1. Outcome

**Pass.** The whole shop acceptance run (34 HTTP checks: Script, mpath, Sql, Transaction, XRequest, Execute) gives the same result in three modes: the Dev server loader on the JVM, scripts and plugins compiled in on the JVM, and the native binary. This includes the order POST that chains four step kinds in one request, and an `https` call from the native binary.

- **Test 3, XRequest:** done with the JDK `HttpClient` against local stub servers (a JDK `HttpServer` in the unit tests, a Node server in CI). Covered: `$[x].body.args.foo1` and the flat `$[x].args.foo1` (both documented by R2, both work), the output modes, `Param` as query string or as a form body, a `Body` with request parameters and mpath into earlier steps, Kotlin reading `steps["prod"]["one"]`, a failing API (500) and an unreachable one (502), arrays and text bodies.
- **Test 4, Execute:** a plugin interface, three ways to find plugin classes, and a proposal for declaring classes and drivers (section 4). Tested with a plugin in its own jar: Dev server folder with redeploy, class-path service lookup on the JVM and in native, a plugin that uses JDBC.

## 2. XRequest semantics (R2's documentation decides; each is tested)

| Item | MQ behaviour |
|---|---|
| Result | An `XResponse`: a map of the response body's fields (when it is a JSON object), which also answers `body`, `statusCode`/`status`, `headers` and, for an array body, `[n]`. A body field with one of those names wins. So `$[x].store.book[0].title` and `$[x].body.args.foo1` both work, and a script's `steps["prod"]["one"]` reads the payload directly. |
| `output` | `true`: the payload itself under the step id. `headers`: `{headers, body, statusCode}`. `false` or absent: not in the response (R2: "By default output is false"). The result is available to later steps whatever the output. |
| Params | In the query string; in a form body for POST/PUT with `Content-Type: application/x-www-form-urlencoded`. Values are url-encoded. |
| Body | Text with `$variables` and `$[x].path` replaced as they are, not JSON-escaped (as R2 does). |
| `{{name}}` | A backend property (`quarkus.mq.properties.name`). An undefined one is a 500 that names it, not an empty string. |
| HTTP status | Any answer is a result; the request carries on and later steps can branch on `$[ext].statusCode` (R2's shop scenario ends in a Sql step after a 500). Only "no answer" (refused, timeout) fails the request with 502. |
| TLS | `https` works in the native binary with no extra setting (probe against `postman-echo.com`, status 200). |

## 3. Numbers (observed)

| | Dev loader (JVM) | Compiled, JVM | Compiled, native |
|---|---|---|---|
| HTTP checks | 34 / 34 | 34 / 34 | 34 / 34 |
| Resident memory after the checks | 612 MB | 189 MB | 77 MB |
| Native build with scripts and plugin | | | 2:00 wall, 3.47 GB peak, binary 64.8 MB |

(An earlier run without XRequest and Execute gave 138 MB JVM and 65.7 MB native; the HTTP client and the plugin cost about 50 MB on the JVM and 11 MB in native.)

## 4. Declaring what is fixed at build time (Execute classes and JDBC drivers): proposal, partly tested

**One project file, one list, used by both products.** A project has `mq.yaml` (name it as you like) with a `libs` section; the Dev server puts those on its class path, the CLI puts the same on the native build's class path:

```yaml
scripts: scripts          # folder of .kts files
plugins:                  # jars with Execute classes
  - ../shop-plugin/target/shop-plugin-1.jar     # a path, or Maven coordinates
libs:                     # libraries scripts may import
  - org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1
drivers:                  # JDBC drivers the backends may use
  - postgresql            # a known name: the extension and its native settings
  - hsqldb                # a known name: the plain driver plus the resource includes
```

- **Execute classes (tested).** A class implements `io.mq.plugin.Plugin` (module `mq-plugin-api`: three interfaces, no dependencies) and is listed in `META-INF/services/io.mq.plugin.Plugin`. `Execute classname="io.mq.sample.Discount"` is matched against the registered services by name; nothing is loaded by name, so the native binary needs no reflection configuration. The extension registers all providers at build time (`ServiceProviderBuildItem`). Dev server: jars in a folder, loaded in a class loader of their own, reloaded when a jar changes (tested, including delete). Jars are loaded from copies, because on Windows a loaded jar cannot be replaced or deleted (found by the test).
- **Return values.** JSON-ready only (Map, List, String, Number, Boolean); other objects are written with `toString()`, because writing arbitrary objects needs reflection.
- **JDBC drivers (PostgreSQL tested here, HSQLDB observed in Spike 3).** The driver set is fixed at build time. `postgresql` means the Quarkus extension (nothing else needed in native; tested in every run here). `hsqldb` means the plain driver plus `quarkus.native.resources.includes=org/hsqldb/resources/*` (Spike 3). Each known name is a recipe the CLI applies; an unknown driver is an error that says which recipe is missing. Oracle and SQL Server are out of scope as decided.
- **Not tested:** Maven-coordinate resolution of `plugins` and `libs` (the CLI does it; the tests use paths), a plugin that itself needs a native resource or reflection (it can ship `META-INF/native-image/...` in its jar, which native-image reads), and `drivers` recipes beyond PostgreSQL.

## 5. Surprises

1. **The first plugin test failed only on Windows**: deleting a plugin jar that a class loader still held. Loading copies fixes it for the Dev server, whose developers are on Windows.
2. **Plugin classes cannot be found by `Class.forName(classname)` in native.** The service file is the only discovery that works the same on the JVM and in native, and it needs the author to list the class once.
3. `Execute` results that are not JSON-ready types would need reflection to serialize; the contract says so instead of hiding it.
4. The `XResponse` aliasing (`body`, `statusCode`) needed a small hook in the expression navigator (`Expr.Aliased`), because R2's own documentation uses both the flat and the `.body` form.

## 6. Open questions for a human

1. Is `mq.yaml` the right place for the declarations, or should they live in the existing backend configuration? Both products must read the same file.
2. Should a plugin return type outside JSON-ready types be an error at the call (clear message) instead of `toString()`? I lean to an error.
3. R2's `RequestProcessable` / `ResultProcessable` plugins (`com.metamug:mtg-api`) are not supported; MQ has a new three-interface API. Is a compatibility adapter wanted for existing plugins?

## 7. Recommended change to the R2 Next spec

"`Execute` runs a Java class registered as an `io.mq.plugin.Plugin` service in a declared plugin jar; classes are matched by name against registered services, never loaded by name. XRequest results are maps of the response fields that also answer `body`, `statusCode` and `headers`. A project file declares plugins, script libraries and JDBC drivers once, for both the Dev server and the CLI."
