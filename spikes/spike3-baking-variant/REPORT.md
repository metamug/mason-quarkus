# Spike 3, question C: the baking variant (build-time object model, Camel Quarkus pattern)

THROWAWAY. A production-only comparison variant, per the brief: dev mode and the default design keep runtime parsing. Code: `quarkus-mq/` (extension: runtime + deployment modules) and `app/` (sample that bakes the 20 shared valid resources).
JVM numbers: Quarkus 3.40.1, JDK 17.0.17, Windows 10 (dev-mode measurements) and Temurin 25 on `ubuntu-latest` (CI). Native: Mandrel 25.0.4.1 on `ubuntu-latest`, 4 vCPU, 15,989 MB RAM.

## 1. Outcome

**Pass as a technique, not recommended as the default.** A deployment-module build step parses the XML with the **shared** StAX parser and validator, converts it into a recordable object model, and a `@Recorder` builds the routes on the Vert.x `Router` at startup. The native binary starts, serves the baked routes, and contains almost none of the XML parser code. Boot time and memory are not measurably better than parsing 20 resources at boot (Spike 2), and in dev mode baking breaks the in-place reload requirement.

## 2. Numbers (observed)

Boot comparison with the same 20 resources (CI run `37082823615`; the apps differ, so only boot and memory are comparable, not binary size or build time):

| | Parse at boot (Spike 2 app) | Baked (this variant) |
|---|---|---|
| Native "started in" | 0.021-0.031 s | 0.021 s |
| Native process start to first response | 66-70 ms | 74 ms |
| Native resident memory after boot | 62.4-63.1 MB | 62.4 MB |
| JVM "started in" | 0.883 s | 0.788-0.806 s |
| JVM resident memory | 123.7-123.9 MB | 135.2-135.7 MB |
| `XMLStreamReader`/`xerces` strings in the native binary | 598 | 33 |
| Native build | 1:41-2:37, 2.9-3.0 GB | 2:47-2:54, 3.5 GB |

Dev mode with the baked model (Windows, JVM 17, `restartNeeded=true`): change a resource: 1.8-2.1 s (Quarkus "Live reload total time" 0.40-0.54 s, plus the 2 s scan throttle from Spike 1); invalid XML: the running app answers HTTP 500 "Error restarting Quarkus" and recovers about 2.1 s after the file is fixed (`logs/dev-mode.log`).
JVM run: routes (GET, POST 201, PUT 202, DELETE 410) work; of 6,111 classes loaded, none were XML parser/stream classes; the runtime module has no XML references (`javap`); the deployment module (parser) is not on the runtime classpath.

## 3. Evidence

Observed: workflow job `spike3-baked-native` (artifact `spike3-baked-results`, files in `results/`); `javap -c` of the generated class `io/quarkus/runner/recorded/MqProcessor$routes<n>` (`new MqModel()`, `new MqResource()`, `putfield name`, ...; no XML text); `logs/jvm-verbose-class.log.gz` (class loading).
First native run failed to serve (`evidence/run1-baked-native-no-init-sql.log`): the H2 `INIT=RUNSCRIPT FROM 'classpath:init.sql'` needs `quarkus.native.resources.includes=init.sql`; fixed in the sample app, unrelated to baking.
Read: `RuntimeUpdatesProcessor.java`, `HotDeploymentWatchedFileBuildItem.java` (see Spike 1).

## 4. Surprises

1. Baking gains only milliseconds: 4-8 ms of parsing in native, 45-76 ms cold on the JVM.
2. The baked JVM variant used 11 MB more resident memory than parse-at-boot (different apps: this one carries Agroal, H2 and reactive routes). Treat as noise, not as a cost of baking.
3. Baking cannot serve the cheap image route (prebuilt MQ image plus a thin layer of XML and config): the XML must be known when the image is built.
4. A baked build fails on a bad file with the validator's messages, which is good for CI, but in dev it takes the whole app down.

## 5. Open questions for a human

None beyond Spike 2's question 1 (is the parse time negligible?). By the brief's rule, baking is adopted only if boot parsing is not negligible; the numbers say it is, so I recommend not adopting it.

## 6. Recommended change to the R2 Next spec

None.
