# MQ: all findings, experiments, conclusions and open decisions (for the planning agent)

Repository: `github.com/metamug/mason-quarkus` (Apache 2.0, R2 material used with attribution in `NOTICE`). Everything here was measured (M), read in source or docs (R) or is an assumption (A); the tag is given where it matters. Environment unless stated: Quarkus 3.40.1, Kotlin 2.4.10 (the version the Quarkus BOM manages), Temurin 17 or 25 on the JVM, Mandrel 25.0.4.1 for native, PostgreSQL 16 and HSQLDB 2.7.4, GitHub Actions `ubuntu-latest` (4 vCPU, 15,989 MB RAM), `macos-latest` (arm64, 3 vCPU, 7 GB), `windows-latest`. Detailed reports are in `docs/reports/`, the decision log in `docs/decisions.md`, the proposal in `docs/proposals/script-modules.md`.

## 1. What MQ is, and the decisions that stand

MQ runs R2 resource XML (REST APIs described as `Resource > Request > Sql | Transaction | XRequest | Script | Execute | Text`) on Quarkus. Decisions made by the owner (do not reopen without evidence):

1. **Two products.** A **Dev server**: JVM, builds and hot-deploys, interprets Kotlin scripts. A **CLI**: validates, compiles scripts ahead of time, produces the final native binary. Scripts cannot hot-deploy in native, and one artifact does not need both.
2. **Scripts are Kotlin only.** Groovy is dropped (the validator gives a clear error). Everything a script imports must be declared, because native cannot load unknown code later.
3. Dev reload by MQ own debounced folder watcher, JVM Dev server only, no watcher in native. `directory-watcher` + JNA is accepted on the Dev server classpath (dev-only, optional, never in the native binary).
4. One process per backend (project), started lazily on first request, stopped after **10 to 15 minutes** idle, started in the background when its project opens. Many backends in one process only as a fallback. 2.5 GB for 20 JVM backends is acceptable on a dev machine with lazy stop.
5. No baking of the model into the binary. StAX only, never JAXB. XML is read from a configured folder at boot. Plain JDBC `DataSource` executors (Agroal in production) instead of Quarkus-bound classes, so the same tests run without Quarkus.
6. On a multi-file change: apply the valid files, keep the last good version of an invalid one, report the error. **The CLI build refuses on any error.**
7. Native validation runs in GitHub Actions on Linux (Mandrel container); nothing is installed locally. Windows is in CI for mq-core, mq-engine and reload.
8. Out of scope: MCP, console, tracing, observability, pagination, auth, Oracle, SQL Server, CLI product work beyond the prototype. Nothing private is committed; R2 is read-only reference.

## 2. One-line outcomes

| Question | Outcome |
|---|---|
| Dev reload without restart (Spike 1) | Quarkus own mechanism partial (about 2 s, misses deletes and renames, not atomic). MQ own watcher: pass, 20 to 30 ms, atomic, old model kept on invalid XML |
| StAX parser + validator in native (Spike 2) | Pass with one native setting (`XMLMessages` bundle), now shipped by the extension and by `mq-core` |
| Hosting several backends (Spike 3) | One process per backend recommended; many-in-one works (6 to 7 MB per backend) |
| `backend.yaml` datasources | Pass in JVM and native (Agroal programmatic); not wired into the extension yet |
| Baking | Works, gains milliseconds, breaks reload and the thin-image route: not adopted |
| Shared library `mq-core` | Pass: 189 golden cases, identical results on JVM (3 OSes) and native |
| Dev reload on Linux, macOS, Windows | Pass; macOS needs file-system events (JDK watcher polls: 2 s) |
| Lazy start | Native about 40 ms first request, JVM about 1 s |
| Sql + Transaction executors (Phase 3) | Gate met: 31/31 HTTP checks on JVM and native |
| **Kotlin in native (test 1)** | **Pass**: shop scripts work with no declarations; one construct (`kotlin-reflect`) cannot work |
| Script, mpath, Sql chains (test 2) | 18/18 in three modes (Dev loader, compiled JVM, native) |
| XRequest (test 3) | Pass, including `https` in native |
| Execute + declarations (test 4) | Pass with a plugin API; declaration proposal made |
| Script modules and libraries (test 5) | Options 1 and 2 built and pass in all three modes; commons-text works in native undeclared |
| Shared scripting host | Measured: -61 % memory at 20 backends (10.2 GB to 4.0 GB), break-even about 2 backends |
| Real R2 resources (test 6) | 31 accepted, 4 rejected for stated reasons; Transaction id rule stays |

Current full acceptance: the shop scenario, 34 HTTP checks, identical on the Dev server loader (JVM), compiled scripts (JVM) and the native binary. Native build with scripts and a plugin: 2:00 wall, 3.47 GB peak, 64.8 MB binary, 77 MB resident after the run. JVM: 189 MB compiled, 612 MB with the Dev loader (the Kotlin compiler is in the process).

## 3. Spikes (details)

### Spike 1: reload (M, source read R)
Compared Quarkus request-triggered scan (A), a watcher calling `HotReplacementContext.doScan` (B), and MQ own watcher (C).

| ms, write to served | A | B | C |
|---|---|---|---|
| Edit | 1995-2026 | 30 | 19-31 |
| Create | 1992-2020 | 27-31 | 26-31 |
| Delete (file created after start) | not detected | not detected | 29-32 |
| Rename | not detected | not detected | 32 |
| 20 files at once | 1102 | 76, **not atomic** (saw 14 of 20) | 37, atomic |
Read in Quarkus source: the 2 s is a throttle in the HTTP handler; deletes are reported only for paths known at start; a folder that is not a resource root is invisible to Quarkus.

### Native image settings (M)
- StAX needs `-H:IncludeResourceBundles=com.sun.org.apache.xerces.internal.impl.msg.XMLMessages`, else a malformed file crashes the binary with `MissingResourceException`.
- Quarkus 3.40.1 needs Mandrel/GraalVM 25. HSQLDB needs `quarkus.native.resources.includes=org/hsqldb/resources/*`; PostgreSQL needs nothing beyond its Quarkus extension. JDBC drivers are fixed at build time.
- `Class.forName("literal")` makes native-image include that class: this pulled the macOS watcher library and JNA into the image and failed the build. Loading by a run-time-built name fixes it.
- Native build: 1:01 (small CLI), 2:00 to 2:38 (Quarkus examples), 3.0 to 3.5 GB peak, 20 to 65 MB binary.

### Spike 2 and 3 numbers (M)
Parse at boot vs baked, 20 resources: native "started in" 21 to 31 ms vs 21 ms, 62 MB both; JVM 0.88 s vs 0.79 s. Hosting: 20 JVM processes 2.5 GB (126 MB each), 20 native 1.1 GB (55 MB each); 20 backends in one JVM about 6 to 7 MB each, all 20 classloaders unload; RSS is not returned to the OS. Failure isolation between backends in one process was not tested.

## 4. Validator and shared library `mq-core`

- 189 generated golden cases (`golden/gen.mjs`) + 35 real-resource cases. The XSD oracle (JDK validator on R2 `resource.xsd`) agrees on every case the XSD can judge.
- The Spike subset validator agreed on only 114 of 177 cases (accepted 59 invalid, rejected 4 valid). Closed: namespace, unknown attributes/elements, `Desc` placement, PATCH, duplicate method+item, id length, datatypes, limit/offset on updates, mpath reference rules.
- Rules beyond the XSD: mpath ids must name an earlier step of the same Request; `limit`/`offset` only on queries; step ids unique in the whole resource **including inside a Transaction**; `when` conditions must parse; `<Script file>` must be a name or `name.kts` (Groovy and others rejected).
- Reports every problem with file, line, column. Result is a model or problems, never both. Native output is byte-identical to JVM and to `golden/expected.tsv`.
- Real files exposed what synthetic cases missed: `xsi:schemaLocation` (always legal in XSD) and Groovy references.
- `xsd:unique` is looser than it looks: two plain GETs without `item` are valid; Transaction ids are unchecked by the schema.

## 5. Reload by platform (M)

| ms, write to served | Linux (JDK) | macOS JDK watcher | macOS file events | Windows (JDK) |
|---|---|---|---|---|
| Edit | 22-23 | about 2000 | 29-77 | 21-28 |
| 20 files at once | 25-27 (0 or 20 visible) | 2069 | 77 (0 or 20) | 33 (0 or 20) |
Invalid XML keeps the old model on all. FSEvents default latency 0.5 s must be set to 10 ms. Reload work itself 0.7 to 1.8 ms.

## 6. Lazy start (M, throwaway launcher)
Native first request 38 to 52 ms (136 ms with a cold disk cache), JVM 1,034 to 1,208 ms; stop 3 ms vs 40 ms; five stopped backends at once 105 ms vs 4,356 ms. Parse of 37 files with the real validator: native 9 ms of a 27 ms start, JVM 78 ms of 0.86 s. Findings: limit parallel JVM starts; "bind port 0, release, start" races; reserve ports.

## 7. Engine (Phase 3 and tests 2 to 4)

Modules: `mq-core` (parser, validator, model, `Expr` for `when`/mpath, store, watcher), `mq-plugin-api`, `mq-engine` (Routes, Dispatcher, Engine, SqlRunner, Inputs, Json, ScriptHandler, XRequestHandler, ExecuteHandler), `mq-script` (Kotlin script definition + Dev loader), `quarkus-mq` (runtime + deployment), examples, `samples/shop` and `samples/shop-plugin`, `tools/mq-compile-scripts`.

**Typed binding (the JSTL `sql:param` defect):** `$name` and `$[id].path` become JDBC parameters; each value is converted to the type the database reports (`ParameterMetaData`). Path value "7" binds as integer; "abc" gives a 400 naming the parameter. `'%$q%'` becomes `CONCAT('%', ?, '%')`. A bound `? * ?` with no typed operand is ambiguous in HSQLDB and PostgreSQL.

**Semantics chosen where R2 does not document them (provisional; the shop acceptance suite decides):** Sql `output` defaults true for queries and false for updates; `requires` = these params must be present, else 400; mpath `[n]` is a 0-based row index (R2 docs inconsistent; no real resource uses a numeric index; shop scripts index rows with Kotlin lists); any Request with an `item` attribute is an item request; errors are `{"errorId","message"}` (400 input, 404/405 routing, 409 constraint, 500 other; details by `errorId`, 5xx logged).

**Not implemented (validated, ignored):** `onblank`, `ref` (SQL catalog), `Param exists`, `classname` on Sql, `Upload`, datasource names other than the default, `backend.yaml` datasources, request-level dynamic headers.

## 8. Kotlin scripts (tests 1 and 2)

**Test 1, native (M):** the four shop scripts and the Kotlin rewrite of the Groovy one compile ahead of time and run in a native binary built in a Mandrel container (`quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25`) with no native declarations. Build 60 to 97 s, binary 26 MB, 50 MB resident, selftest 0.15 s. 31 boundary constructs: 26 work undeclared (SHA-256, HMAC, AES/GCM, PBKDF2, RSA, kotlinx.serialization, coroutines, regex, BigDecimal, time zones, HttpClient object, JDK reflection on JDK classes); 4 need declarations (ResourceBundle, reflection on a script own class, Java serialization, non-English locales); **`kotlin-reflect` cannot work**. Two failures are **silent** (empty reflection result, English number format), so smoke tests must check values.

**How scripts work:** the script sees `params` (text map), `steps` (results by id), `response` (map to fill), `request` (`id`, `pid`, `uid`, `method`). Contract classes are Java (`Params`, `Steps`, `Response`, `RequestInfo`, `ScriptLoader`) because the command-line Kotlin compiler loses generic type arguments of provided properties. Dev server: `DevScriptLoader` compiles each `.kts` once per file version and evaluates per request; a compile error names file and line. CLI/production: `tools/mq-compile-scripts` starts the Kotlin compiler class directly (the `kotlinc` wrapper does not pass the scripting-plugin options), generates `MqCompiledScripts` (a `ScriptLoader` calling each compiled class constructor, registered as a Java service; no reflection). Kotlin does not let ordinary Kotlin sources refer to script-compiled classes, hence the generated Java registry.

**Test 2 (M):** the shop script chains pass in all three modes (hashing script feeds Sql through mpath; one script serves item and collection requests; state machine script decides whether an update `when` runs).

**Pitfalls found:** Kotlin versions must match the Quarkus BOM (2.4.10); mixing 2.2.20 caused `NoSuchMethodError ...getUseJavac()` inside Quarkus only; inside a Quarkus fast-jar the app jars are not on `java.class.path`, so the host must be given the class path (`lib/main`); the CLI ships or downloads the Kotlin compiler (about 80 MB); colon/drive-letter class paths broke on Windows.

## 9. XRequest and Execute (tests 3 and 4)

**XRequest (M, R2 docs read):** JDK `HttpClient`. Result is an `XResponse`: a map of the response body fields that also answers `body`, `statusCode`/`status`, `headers` and `[n]` (R2 documents both `$[x].store.book[0].title` and `$[x].body.args.foo1`). `output`: `true` = payload, `headers` = `{headers, body, statusCode}`, absent/`false` = hidden (still usable by mpath). Params go in the query, or a form body for POST/PUT with urlencoded content type. Body and url take `$variables` and mpath unescaped (as R2). `{{name}}` = backend property `quarkus.mq.properties.name`; undefined is a 500 naming it. Any HTTP status is a result (later steps can branch on `$[x].statusCode`); only "no answer" is 502. `https` works in native with no setting.

**Execute (M):** plugin API in `mq-plugin-api` (`Plugin.process(PluginRequest, Map args)`; three interfaces, no dependencies). Classes are listed in `META-INF/services/io.mq.plugin.Plugin` and matched by name against registered services, never loaded by name (native-safe). Args: `value` = text with variables, `path` = typed value found by mpath. Return JSON-ready values. Dev server loads jars from a folder in their own class loader and reloads on change, **loading copies because Windows locks open jars** (found by a failing test). Not supported: R2 `RequestProcessable`/`ResultProcessable` (`com.metamug:mtg-api`).

**Declaration proposal (partly tested):** one project file (`mq.yaml`) read by both products with `scripts`, `plugins`, `libs` (Maven coordinates or jars) and `drivers` (known names, each a recipe: `postgresql` = the Quarkus extension, tested; `hsqldb` = plain driver + resource include, observed in Spike 3). Maven resolution and driver recipes beyond PostgreSQL are untested.

## 10. Script modules and libraries (test 5): options 1 and 2 built and tested

Owner decision: option 1 (no cross-script use; shared code in a declared jar), then option 2 (a shared `lib/` folder of ordinary Kotlin files for interdependent scripts); option 3 (`@file:Import`) skipped; test library commons-text.
**Result (M):** both pass in all three modes (Dev loader, compiled JVM, native), 40/40 HTTP checks each. A script importing commons-text (`WordUtils`, `StringEscapeUtils`, `LevenshteinDistance`, `StringSubstitutor`) works in native with **no native declaration**. Two scripts use `shop.Money` from `lib/`: the Dev loader compiles the lib folder when any file in it changes and recompiles each script on its next call (new lib file, new script, broken lib reported with file and line, fix picked up); the CLI compiles the lib first, then the scripts against it, and the lib classes ship in the same jar. Native build with scripts, lib, commons-text and a plugin: 1:40, 3.37 GB peak, 64.8 MB, 77 MB resident. Rules: lib code is ordinary Kotlin (no `params`/`steps`/`response`: pass arguments); a lib change recompiles every script; a lib that does not compile stops every script until fixed. Both products must compile with the same class path, which is what `mq.yaml` declares. Report: `docs/reports/script-modules.md`.

## 10b. Shared scripting host across backends (measured first, as decided)

**Result (M, 4 vCPU runner, each backend its own project copy):** with the compiler in every backend (today) a backend is 510 to 660 MB after running its scripts (idle: 116 to 163 MB); capping the JVM heap barely helps (662 to 514 MB). With one shared host that holds the compiler and backends that load the compiled classes, a backend is 148 to 170 MB and the host 470 to 930 MB (capped at 700 MB heap; 1.4 GB uncapped at 5 projects).

| Backends | In-process (compiler in each) | Shared host (backend + host) |
|---|---|---|
| 1 | 662 MB (514 capped) | 624 to 646 MB |
| 5 | 3,466 MB (2,577 capped) | 1,752 to 2,160 MB |
| 20 | 10,245 MB (capped) | **3,994 MB** (-61 %) |

Break-even about 2 backends. Warm script calls are 3 to 5 times faster remote (2 to 5 ms vs 10 to 17 ms); the first call of the first script is 4.6 to 5.2 s either way (compiler warm-up); with 20 backends starting at once the first call took 80 s in-process and 7 to 9 s with the shared host. Backends keep running their loaded version if the host is down. Recommended: host as a managed child process of the Dev server, started on demand, capped, stopped when idle; compile all scripts of a project in the background when it opens. Code is experimental (`ScriptHost`, `RemoteScriptLoader`, `quarkus.mq.script-host`). Report: `docs/reports/shared-host.md`.

## 10c. Owner decisions taken after the v2 summary (docs/decisions.md)

- **One project file.** `mq.yaml` replaces `backend.yaml` (which existed only as the datasource file of the hosting spike): its `datasources:` section moves in unchanged; for one release a lone `backend.yaml` is read as that section with a warning. Sections: `datasources`, `properties`, `scripts`, `lib`, `plugins`, `libs`, `drivers`. Same reader in both products. **Reader not built yet.**
- **Plugin results** outside JSON-ready types (String, Number, Boolean, null, List, Map with String keys) are an error naming the step, class and path (implemented, tested).
- **R2 plugin adapter:** not now. Check done: of 35 distinct real resources only `execute.xml` (a parser test fixture) uses `Execute`; no real application resource does.
- **Provisional semantics accepted**, including the 0-based mpath row index.

## 11. Real resources (test 6)

80 resource files in the R2, Mason and spike folders reduce to 35 distinct; 31 accepted, 4 rejected: two old-dialect fixtures (`persist` attribute, `Query` element) that R2 own XSD also rejects, and two Groovy references. 12 accepted files contain a Transaction and none has a duplicate step id, so the MQ Transaction id rule rejects nothing real and stays. (The instruction for this item was cut off; read as "stays if no real resource breaks".)

## 12. Risks and cautions for planning

- **Version coupling:** the Kotlin version in the Dev server, the CLI compiler and the app must equal the Quarkus BOM Kotlin; a Quarkus upgrade moves all three.
- **Memory:** the Dev loader costs 510 to 800 MB per backend; the shared scripting host measured at -61 % for 20 backends (section 10b); until it is built, 20 backends do not fit comfortably on a 16 GB machine.
- **CLI toolchain:** native build needs Mandrel, about 3.5 GB RAM, 2 to 2.5 minutes; macOS/Windows need a builder container and produce Linux binaries only. Distribution of the Kotlin compiler (80 MB) with the CLI.
- **Silent native differences:** locales, reflection, resources; the CLI smoke test must compare values against JVM results, not just run.
- **Unmeasured:** compile latency of the first script request in the Dev server, native behaviour of third-party libraries other than those tested, `drivers` recipes beyond PostgreSQL, failure isolation with many backends in one process, Maven resolution of declared libraries.

## 13. Recommended next steps

1. Build the `mq.yaml` reader (one module, SnakeYAML, used by Dev server and CLI): `datasources` (Agroal programmatic, per Spike 3), `properties`, `scripts`, `lib`, `plugins`, `libs` with Maven resolution, `drivers` recipes. Today `libs` is a pom file and the paths are system properties.
2. Productise the shared scripting host in the Dev server: managed child process, on-demand start, heap cap, idle stop, background compile of all scripts when a project opens, project class path handed over from `mq.yaml`.
3. CLI prototype end to end: validate (refuse on any error), compile scripts and lib, declared libs, plugin jars, driver recipes, Mandrel container build, value-checking smoke test against JVM results.
4. Dev server launcher/proxy: port reservation and parallel-start limit, idle stop 10 to 15 minutes, background start on project open.
5. Keep the JVM-vs-native conformance matrix (shop acceptance, HSQLDB and PostgreSQL) as the gate for every executor; add every real resource as a golden case.
6. Remaining engine gaps (`onblank`, `ref`, `Param exists`, `Upload`) once real resources need them.

## 14. Open questions for the owner

1. Host lifecycle: managed child process of the Dev server, started with the first backend that has scripts, stopped when idle (recommended)? Compile all scripts when a project opens (recommended: removes the 5 s first call)?
2. Host heap cap: 700 MB worked for 20 projects; tune with real projects.
3. Should `mq.yaml` allow a per-project override of the Kotlin version? (Recommendation: no; one version, the Quarkus BOM one.)

## 15. Where things are

`golden/` (cases, manifest, expected output, `real/`), `tools/xsd-oracle/`, `tools/mq-compile-scripts/`, `mq-core/`, `mq-plugin-api/`, `mq-engine/`, `mq-script/`, `quarkus-mq/`, `examples/{reload,shop,shop-kt}/`, `samples/{shop-sql,shop,shop-plugin}/`, `spikes/` (throwaway, labelled), `.github/workflows/` (`mq-core`, `mq-engine`, `reload`, `shop-conformance`, `shop-kotlin`, `kotlin-native`, `lazy-start`, `native-spikes`), `docs/reports/`, `docs/proposals/`, `docs/decisions.md`.
