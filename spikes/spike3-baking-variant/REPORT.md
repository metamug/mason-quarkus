# Spike 3, question C: the baking variant (build-time object model, Camel Quarkus pattern)

Quarkus 3.40.1, JDK 17.0.17 (Temurin), Maven 3.9.16, H2 2.x in memory, Windows 10. Code: `quarkus-mq/` (extension) and `app/` (sample) in this folder. THROWAWAY. Per the brief this is a production-only comparison variant: dev mode and the default design keep runtime parsing.

## 1. Outcome

**Partial.** The build-time-model design works on the JVM: the deployment module parses the R2 resource XML at build time, the recorder bakes the object model into generated startup bytecode, and the routes are created on the Vert.x `Router` at startup with no XML in the runtime path. **The native binary was not built or run: this machine has no GraalVM/Mandrel, no MSVC Build Tools, no Docker/Podman.** The native claim is therefore unverified.

## 2. What was built

- `quarkus-mq/runtime`: recordable model (`MqModel`, `MqResource`, `MqRequest`, `MqStep`: public fields, no-arg constructors), `MqRecorder` (`@Recorder`, builds routes on the `Router`), `MqHandler` (executes steps with JDBC via Arc/Agroal on a worker thread). Depends on `quarkus-vertx-http` and `quarkus-agroal` only. 
- `quarkus-mq/deployment`: `MqProcessor` with `@BuildStep @Record(RUNTIME_INIT)`: reads `mq/*.xml` from the application root archive, parses with StAX, fills `MqModel`, calls `recorder.registerRoutes(VertxWebRouterBuildItem.getHttpRouter(), model)`. Also a `HotDeploymentWatchedFileBuildItem` (`mq/*.xml`, `restartNeeded=true`).
- `app`: `mq/movie.xml` (R2 syntax: list, item, POST 201, PUT 202, DELETE 410) and `mq/hello.xml` (`Text`), `quarkus-reactive-routes` + `quarkus-jdbc-h2`.
- Supported steps in the spike: `Sql` (query/update, `$param` bound as `?`, from path/query/form), `Text`, request `status`. Not supported: `when`, `Script`, `XRequest`, `Transaction`, typed binding (H2 coerces strings).

## 3. Numbers (observed)

| Measure | Result |
|---|---|
| JVM startup (`java -jar quarkus-run.jar`) | 1.287 s ("started in") |
| Routes served correctly | GET list/item, POST (201), PUT (202), DELETE (410), unknown path 404, `Text` route |
| Classes loaded, whole run incl. requests | 6,111; **XML classes loaded: 0** (no `javax.xml`, `com.sun.xml`, Xerces, SAX, DOM); MQ classes loaded: `MqRecorder, MqModel, MqResource, MqRequest, MqStep, MqHandler` |
| XML references in runtime module bytecode (javap) | 0 (deployment module: 23 `javax/xml/stream` references) |
| Deployment module on the runtime classpath | no (`quarkus-app-dependencies.txt`) |
| `<Resource` text in generated startup bytecode | 0 files; the class `io/quarkus/runner/recorded/MqProcessor$routes361063519` builds the model with `new MqModel()`, `new MqResource()`, `putfield name="hello"` ... |
| Dev mode, change a resource XML (edit / create / delete) | 1.8-2.1 s each (all three work; Quarkus logs "Live reload total time" 0.40-0.54 s of that) |
| Dev mode, invalid XML | the running app answers HTTP 500 "Error restarting Quarkus" (no old model kept); recovers 2.1 s after the file is fixed |

## 4. Evidence

Read: `HotDeploymentWatchedFileBuildItem.java`, `RuntimeUpdatesProcessor.java` (lines ~470-630 `doScan`, ~1088-1230 `checkForFileChange`, ~1517 `isRestartNeeded`), `VertxHttpHotReplacementSetup.java:38` (`HOT_REPLACEMENT_INTERVAL = 2000`, request-triggered scan throttle at lines ~143-173), all at tag 3.40.1 (fetched from the quarkusio/quarkus tag, not copied here).
Observed commands (run from this folder): `mvn package` then `java -verbose:class -jar quarkus-run.jar` plus curl (logs in `logs/`), `javap -c` on the runtime and generated classes, `node measure-dev.mjs` against `mvn quarkus:dev`.

## 5. Surprises

1. **This design contradicts decisions in the brief.** Section 2 decided "StAX at boot, resource XML read from a folder, not compiled into the binary". With a build-time model, changing a resource in production means rebuilding the image (for native: a native compile), not updating a mounted folder and restarting a pod. Spike 2's "boot from a folder outside the binary" is not testable with this design.
2. Dev reload becomes a Quarkus application restart. Quarkus's own reload took 0.40-0.54 s, but each change is only noticed on the next HTTP request after the 2 s throttle (`VertxHttpHotReplacementSetup.java:38`), hence ~2 s observed (the old Dev server loop was 0.45-0.9 s). `HotReplacementContext.doScan` is public and not throttled (read, not measured): a file watcher that calls it should remove the 2 s.
3. In dev mode an invalid file takes the whole app down (Quarkus error page) instead of keeping the old model, which the brief asked for. Validation at build time fails the build instead (good for CI, bad for an agent loop).
4. Spike 1 read-only finding that still matters: for no-restart reload of files created after start, Quarkus reports edits/creates but not deletes (`RuntimeUpdatesProcessor.java ~1190-1230`: deletes are reported only for paths known at (re)start). With `restartNeeded=true` (this design) all three were detected because the whole model is rebuilt.
5. The extension API fit the Camel Quarkus pattern without friction: a recorded POJO model plus a `@Recorder` taking `RuntimeValue<Router>` compiled and worked first time.

## 6. Open questions for a human

1. Native validation: install Mandrel/GraalVM plus Visual Studio Build Tools here (several GB), build in CI (a GitHub Actions ubuntu runner has GraalVM; needs the repo and licence decisions from the brief), or use a Linux machine?
2. Build-time model (this spike) or runtime StAX (the brief)? Hybrid is possible: the build-time model for the packaged application and a runtime path for resources that change without a rebuild; that doubles the executor-facing surface.
3. For the Dev server loop: accept restart-based reload (about 0.5 s plus the scan trigger) or add the watcher plus `doScan`?

## 7. Recommended change to the R2 Next spec

None until questions 1 and 2 are answered. If the build-time model is chosen, replace the sentence "the binary reads the XML from a configured folder at boot" with "the resource XML is parsed at build time and compiled into the binary; changing a resource requires a rebuild".
