# MQ: findings, experiments and outcomes (for the planning agent)

Repository: `github.com/metamug/mason-quarkus` (Apache 2.0, attribution to R2 in `NOTICE`). Everything below was measured or read unless marked **assumed**. Numbers come from GitHub Actions runs (Linux `ubuntu-latest`: 4 vCPU, 15,989 MB RAM; macOS arm64: 3 vCPU, 7 GB), Quarkus 3.40.1, Temurin 17/25, Mandrel 25.0.4.1, unless a Windows 10 local run is named. Raw results are in the repo; per-topic reports are in `docs/reports/`.

## 1. What MQ is and the decisions that are fixed

MQ runs R2 resource XML (REST APIs described as `Resource > Request > Sql/Transaction/XRequest/Script/Execute/Text`) on Quarkus. Fixed decisions (user, not to be reopened without evidence):

1. Dev reload uses MQ's own debounced file watcher, JVM Dev server only. No watcher in the native binary.
2. One MQ process per backend (project) by default, started lazily on first request, stopped when idle. Many backends in one process only as a fallback.
3. No baking of the model into the binary (kept only as a comparison). StAX only, never JAXB. No JSON intermediate form. XML is read from a configured folder at boot.
4. Quarkus 3.40.1, Mandrel 25 (JDK 25) for native. Native validation runs in GitHub Actions on Linux, not locally.
5. Licence Apache 2.0. R2 is read-only reference and may be used with attribution.
6. **Latest direction (user): two separate products.** A **Dev server** (JVM, builds and hot-deploys, interprets Kotlin scripts) and a **CLI** that validates and produces the final **native binary** (scripts compiled at build time). One artifact does not need both hot deploy and native.

Out of scope: MCP server, CLI, console, tracing, observability (later layers). Parked: Mason PRs 175-180, pagination and auth scenarios.

## 2. Outcome summary (one line each)

| Question | Outcome |
|---|---|
| Spike 1: dev reload without restart | Quarkus' own mechanism: partial (about 2 s, misses deletes/renames, not atomic). MQ's own watcher: pass, 20-30 ms |
| Spike 2: StAX parser + validator in native, parse cost | Pass with one native setting (`XMLMessages` bundle); native results identical to JVM |
| Spike 3 A: hosting several backends | One process per backend recommended; many-in-one works (about 6-7 MB per backend, classloaders unload) |
| Spike 3 B: datasources from `backend.yaml` | Pass JVM and native via Agroal programmatic API; HSQLDB needs one resource include |
| Spike 3 C: baking | Works, not recommended (gains milliseconds, breaks dev reload and the thin-image route) |
| Validator vs R2 XSD | Subset validator accepted 59 invalid files; real validator closes all gaps, 185 golden cases |
| Phase 2 shared library | Pass: same results on JVM (3 OSes) and native |
| Dev reload on Linux/macOS/Windows | Pass; macOS needs file-system events (JDK watcher polls: 2 s) |
| Lazy start | Native first request about 40 ms; JVM about 1 s |
| Parse time, real validator, 37 files | Native 9 ms of a 27 ms start; JVM 78 ms of 0.86 s |
| Phase 3: Sql + Transaction executors | Gate met: 31/31 HTTP checks on JVM and native, PostgreSQL and HSQLDB |
| Not done | Script (Kotlin), XRequest, Execute executors; CLI; backend.yaml wiring; Dev server |

## 3. Spike 1: dev-mode reload

Compared (a) Quarkus' `HotDeploymentWatchedFileBuildItem` + `consumeNoRestartChanges` (scan triggered by requests), (b) a watcher calling `HotReplacementContext.doScan`, (c) MQ's own watcher re-reading the folder.

| Scenario (ms, file write to served) | A: Quarkus, request-triggered | B: watcher + `doScan` | C: own watcher |
|---|---|---|---|
| Edit | 1995-2026 | 30 | 19-31 |
| Create | 1992-2020 | 27-31 | 26-31 |
| Delete (file created after start) | not detected | not detected | 29-32 |
| Rename | not detected | not detected | 32 |
| 20 files at once | 1102 | 76, **not atomic** (saw 14 of 20) | 37, atomic (0 or 20) |
| Invalid XML | old model kept | same | same |

Findings (read in Quarkus 3.40.1 source): the flat 2 s is a throttle in the HTTP handler (`HOT_REPLACEMENT_INTERVAL = 2000`); deletes are reported only for paths known at app start; a folder that is not a resource root is invisible to Quarkus. Recommendation adopted: MQ watches the folder itself (debounce 15 ms, full re-read, one atomic swap, last valid version kept per file).

## 4. Native image findings (Spikes 2 and 3)

- StAX (JDK Xerces) works in native with **one setting**: `-H:IncludeResourceBundles=com.sun.org.apache.xerces.internal.impl.msg.XMLMessages`. Without it, the first malformed XML file crashes the binary with `MissingResourceException`. Now shipped by the extension (`NativeImageResourceBundleBuildItem`) and by `mq-core` (`META-INF/native-image`), so users add nothing.
- Quarkus 3.40.1 requires Mandrel/GraalVM 25; 23.1 is rejected.
- HSQLDB in native needs `quarkus.native.resources.includes=org/hsqldb/resources/*`. PostgreSQL needs nothing beyond its Quarkus extension. H2 `INIT=RUNSCRIPT` needs the script included as a resource. The JDBC driver set is fixed when the binary is built; the choice per backend is runtime.
- `@ApplicationScoped` client proxies returned 0 for field reads in the spike: use `@Singleton` or accessors.
- `Class.forName("literal")` makes native-image include that class: this pulled the macOS watcher library and JNA into the image and failed the build (JNA `CarbonAPI` cannot initialise at build time). Fix: load by a run-time-built name.
- Native build: 1:01 for the small validator CLI (1.5 GB peak); 2:32-2:38 for the Quarkus examples (3.0-3.25 GB peak); binaries 20-61 MB.
- `pkill -f -- '-runner'` killed the GitHub Actions agent (its path contains `actions-runner`): use PID-based kill.

## 5. Spike 2 and 3 numbers

Boot (20 resources, parse at boot vs baked): native "started in" 0.021-0.031 s vs 0.021 s; resident 62 MB both; JVM 0.88 s vs 0.79-0.81 s. Parsing is 4-8 ms native (subset validator), 45-76 ms cold on the JVM. Baking: 33 vs 598 XML-parser strings in the binary but no measurable boot or memory gain; breaks dev reload (invalid XML takes the app down) and the thin-image route.

Hosting (minimal backend, two idle pools):

| Backends | JVM total RSS | JVM all ready | Native total RSS | Native all ready |
|---|---|---|---|---|
| 1 | 102-106 MB | 0.84-1.1 s | 56 MB | 0.12 s |
| 5 | 631 MB | 2.7-3.8 s | 277 MB | 0.1 s |
| 20 | 2.5 GB | 11-15 s | 1.1 GB | 0.3-0.4 s |

Many backends in one JVM: add 20 at runtime, about 6-7 MB each; remove 20: classloaders all unloaded, RSS not returned to the OS. Failure isolation between backends in one process was **not** tested. Datasources: Agroal programmatic from `backend.yaml` (`${DB_PASSWORD}` env substitution, missing variable is an error); a backend whose datasource fails must not stop the process.

## 6. Validator and shared library (`mq-core`)

- `golden/gen.mjs` generates **185 cases** (75 valid, 98 invalid by the XSD, 10 invalid by R2's Java rules, 2 invalid by MQ rules) with a rule description each. `tools/xsd-oracle/` checks every case against R2's `resource.xsd` with the JDK schema validator: MQ agrees on every case the XSD can judge.
- The Spike subset validator agreed on only 114 of 177 original cases (accepted 59 invalid files, rejected 4 valid). Missed: namespace checks, unknown attributes/elements, `Desc` placement, PATCH, duplicate method+item, id length, datatypes of Sql/Param/Header/XRequest/Script attributes, limit/offset on updates, mpath reference rules.
- Rules beyond the XSD: mpath ids must name an earlier step of the same Request; `limit`/`offset` only on queries; step ids unique in the whole resource **including inside a Transaction** (stricter than the XSD, so mpath names one step); `when` conditions must parse.
- `xsd:unique` is looser than it looks: two plain GETs without `item` are valid; ids inside a Transaction are not checked by the schema.
- **Real files found a bug the suite missed:** shop resources carry `xsi:schemaLocation`, always legal in XSD; the validator now accepts `xsi:schemaLocation` and `xsi:noNamespaceSchemaLocation` on any element. Lesson: add every real resource as a case.
- Library: plain Java 17, StAX → element tree with positions → checker + model (immutable records). Reports **every** problem with file, line, column. Result is a model or problems, never both. Native output is byte-identical to the JVM and to `golden/expected.tsv` (a CI job diffs them). JDK parser message texts can change with the JDK version; the golden file would then need review.
- Dev-reload pieces in the library: `ResourceStore` (atomic snapshot, invalid file keeps its last valid version, deleted file disappears), `FolderWatcher` (JDK `WatchService`; file-system events through `io.methvin:directory-watcher` on macOS, optional dependency, dev only).

## 7. Reload results by platform

| ms, file write to served | Linux (JDK) | macOS, JDK watcher | macOS, file-system events | Windows (JDK, local) |
|---|---|---|---|---|
| Edit | 22-23 | about 2000 | 29-77 | 26-32 |
| Create / delete | 17-24 | about 2000 | 30-93 | 26-32 |
| 20 files at once | 25-27 (0, 20) | 2069 | 77 (0, 20) | 63 (0, 20) |
| Invalid XML keeps old model | yes | yes | yes | yes |
| Restarts | 0 | 0 | 0 | 0 |

The library's default FSEvents latency (0.5 s) gave 258-680 ms on macOS; setting 10 ms gave the figures above. Reload work itself (parse one file, swap) is 0.7-1.8 ms. A Windows CI job was added (shell fix applied); its result had not been checked at the time of writing.

## 8. Lazy start (one process per backend)

Throwaway launcher/proxy, example app, idle 1.5 s.

| | Native | JVM (Linux) |
|---|---|---|
| First request to a stopped backend | 136 ms (cold disk), then 38-52 | 1,034-1,208 |
| Warm request through the proxy (median) | 1.5 ms | 2.4 ms |
| Resident while running | 54-56 MB | 100 MB |
| `SIGTERM` to exit | 3 ms | 40 ms |
| Five stopped backends asked at once | 105 ms | 4,356 ms |
| Memory of those five | 226 MB | 527 MB |

Findings: starting is the cost, stopping is not; limit parallel JVM starts; "bind port 0, release, start" raced under five parallel starts, so a launcher must reserve ports or retry.

## 9. Parse time with the real validator (37 shared files, 20 valid, 17 invalid)

Native 9.0 ms (7.1-11.8) of a 27 ms "started in" (about 33 %); JVM 78 ms (74-98) of 861 ms (about 9 %). Per file about 0.24 ms native, 2.1 ms cold JVM. Real validator is roughly 1.5 times the subset's cost. Recommendation: do not bake.

## 10. Phase 3: engine with Sql and Transaction (the part that actually executes)

Modules: `mq-core` (parser, validator, model, `when`/mpath expressions `Expr`, store, watcher), `mq-engine` (plain Java over JDBC `DataSource`: `Routes`, `Dispatcher`, `Engine`, `SqlRunner`, `Inputs`, `Json`), `quarkus-mq` (runtime + deployment: serves `/v<version>/<resource>[/<id>]` on the Vert.x router, worker thread, default Agroal datasource), `examples/reload`, `examples/shop`, `samples/shop-sql`.

Verified: the SQL-only shop scenario (customer register with declared Params, `?q=recent` branch, item read, LIKE, order POST via a Transaction guarded by `when` using mpath into a prior query, joined item read, PUT, rollback on constraint error) passes **31/31 over HTTP on the JVM application and on the native binary**, PostgreSQL 16; engine tests pass on HSQLDB (3 OSes) and PostgreSQL. Native build 2:38, 3.25 GB peak, 60.8 MB binary; resident after the scenario JVM 131 MB, native 65.6 MB. No reflection configuration needed for the JDBC path.

Typed binding (the JSTL `sql:param` defect): `$name` and `$[id].path` become JDBC parameters; each value is converted to the type the database reports (`ParameterMetaData`). Path value "7" binds as integer; "abc" gives 400 naming the parameter. `'%$q%'` becomes `CONCAT('%', ?, '%')`. A bound `? * ?` with no typed operand is ambiguous in HSQLDB and PostgreSQL.

Semantics chosen where R2 does not document them (each covered by a test; please confirm): Sql `output` defaults true for queries, false for updates; `requires` = these request params must be present else 400; mpath `[n]` is a 0-based row index (R2's doc is inconsistent); any `Request` with an `item` attribute is an item request; errors answer `{"errorId","message"}`: 400 bad input, 404/405 routing, 409 constraint violation (SQLSTATE 23), 500 other, details retrievable by `errorId`; a rejected order without a script still answers the declared status (201) with a `rejected` text.

Not implemented yet (validated, ignored): `onblank`, `ref` (SQL catalog), `Param exists`, `classname` on Sql, `Upload`, datasource names other than the default, `backend.yaml` datasources (standard `quarkus.datasource.*` is used), request headers beyond static response headers.

Deviation from the brief to confirm: executors use plain JDBC `DataSource` (Agroal in production) instead of binding directly to Quarkus classes, so the same tests run without Quarkus. XRequest would use the JDK `HttpClient` unless the Quarkus REST client is required.

## 11. What the earlier tests did NOT cover (user noticed)

Multi-step execution beyond SQL: Script → mpath → Sql chains, XRequest (including `$[x].body.…` and Kotlin reading `steps["prod"]`), Execute, chained mpath through scripts, Groovy. R2's shop scenario (`mcp/scenarios/shop`: 7 resources, 5 scripts, schema) did exercise these on R2/Mason and is the acceptance suite for Phase 4. Its scripts are Kotlin `.kts` with bindings `params`, `steps`, `response` (plus one Groovy).

## 12. Architecture direction and challenges (user's latest split)

Dev server: JVM, builds and hot-deploys, interprets Kotlin scripts (hot deploy needs runtime class loading). CLI: validates with the same `mq-core`, refuses to build on any error, compiles scripts at build time, produces the native binary. A native image cannot compile or load new code at run time, so scripts cannot hot-deploy there; this is why the products are separate.

Challenges: (1) native toolchain: Mandrel, about 3 GB RAM, 2.5 min; macOS/Windows need a builder container and only produce Linux binaries; (2) two artifact kinds: no scripts/drivers/Execute classes → prebuilt native MQ + XML folder (no rebuild, "thin image"); otherwise a project-specific native build; (3) script parity: compile with the same Kotlin compiler and bindings in Dev and CLI; scripts using reflection, `javax.crypto`, serialization or resources can compile yet fail only in the native binary, so the CLI must smoke-test the built binary; (4) Groovy is dynamic: Dev-server-only or dropped; (5) `Execute` classes and JDBC drivers are fixed at build time and need a declaration mechanism; (6) the XML stays unchanged and read from a folder at boot; (7) dev/production drift is controlled by a standing JVM-vs-native conformance matrix (exists for the SQL slice). **Assumed, not verified:** Kotlin scripting at build time into native image works for the shop scripts. Verify first.

## 13. Owner decisions on the open questions (details in docs/decisions.md)

1. directory-watcher + JNA on the Dev server: yes (dev-only, optional, never in the native binary).
2. Lazy stop idle time: 10-15 minutes; start the backend in the background when a project opens, so the 1 s JVM first request is rarely seen.
3. Transaction id rule: kept, because all real resources pass (78 of 80 found; the 2 failures are old-dialect fixtures that R2's XSD rejects too). Real files are now in golden/real and tested.
4. One invalid file in a multi-file change: apply the valid files, keep the last good version of the invalid one, report the error. The CLI build refuses on any error.
5. Plain-JDBC executors: yes.
6. Phase 3 semantics: provisional; the shop acceptance suite decides, especially the 0-based mpath index (no real resource uses a numeric mpath index; shop scripts read rows with 0-based Kotlin list access; only R2's docs use [1], inconsistently).
7. 2.5 GB for 20 JVM backends: acceptable for a dev machine with lazy stop; many-in-one stays a fallback.
8. Windows in CI: yes; mq-core, mq-engine and the reload scenarios run green on windows-latest.

## 14. Recommended next steps

1. Phase 4 executors in this order: XRequest (JDK `HttpClient`, `$[x].body…` mpath, `output` modes), chained mpath into scripts, `Execute`; Kotlin `Script` executor in the **Dev server** first (compile once per version, call per request); port the shop scenario's remaining checks to HTTP tests as the acceptance suite.
2. Prototype the CLI native build inside a Mandrel builder container in CI using the shop scenario's four Kotlin scripts; record build time, binary size, memory, and which script constructs break in native.
3. Wire `backend.yaml` datasources (Agroal programmatic, per Spike 3) and `datasource` names.
4. Keep the conformance matrix (JVM vs native, HSQLDB and PostgreSQL) as the gate for every new executor; add every real-world resource as a golden case.
5. Dev server pieces: launcher/proxy with port reservation and parallel-start limits, per-backend lifecycle.

## 15. Where things are

`golden/` (cases, manifest, expected output), `tools/xsd-oracle/`, `mq-core/`, `mq-engine/`, `quarkus-mq/`, `examples/{reload,shop}/`, `samples/shop-sql/`, `spikes/` (throwaway, labelled), `.github/workflows/` (`mq-core`, `mq-engine`, `reload`, `shop-conformance`, `lazy-start`, `native-spikes`), `docs/reports/` (one page per topic), `docs/validator-gap-analysis.md`.
