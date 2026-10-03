# Phase 2: the shared library `mq-core` (Next work item 2)

`mq-core` is a plain Java 17 library (StAX parser, validator, model, folder store and watcher) with no Quarkus and no runtime dependency (one optional dependency, `directory-watcher`, for dev reload on macOS). The Dev server, the CLI and the Quarkus runtime can all use it. Runs: `37086559770` (first), `37094118900` (latest). Runners: `ubuntu-latest` 4 vCPU 15,989 MB RAM, Windows, macOS; Mandrel 25.0.4.1.

## 1. Outcome

**Pass.** The 180 golden cases are the test suite and give the same results on the JVM (Linux, macOS, Windows) and in a native binary built with Mandrel 25. Every error carries the file name and a line number. The validator agrees with R2's `resource.xsd` on every case the schema can judge, and is stricter only where the cases say so (mpath references, `limit`/`offset` on updates, ids inside a `Transaction`).

## 2. Numbers (observed)

| | |
|---|---|
| Golden cases | 180: 71 valid, 98 invalid by the XSD, 9 invalid by R2's Java rules, 2 invalid by MQ rules |
| Test results | JVM tests green on `ubuntu-latest`, `macos-latest`, `windows-latest`; native output equals JVM output equals `golden/expected.tsv` |
| Gaps closed from the Spike validator | 59 missed rules and 4 wrongly rejected files (docs/validator-gap-analysis.md) |
| Native build of the validator CLI (`io.mq.core.Validate`) | 1:01.9 wall, 1.53 GB peak resident, binary 20.2 MB |
| Parse of 37 real-style files | native 9 ms, JVM 78 ms (docs/reports/parse-time.md) |

## 3. Evidence

Observed: `mq-core/src/test/java/io/mq/core/GoldenTest.java` (verdict per manifest, file and line on every problem, agreement with the XSD through the JDK schema validator, exact output against `golden/expected.tsv`); `.github/workflows/mq-core.yml` jobs `jvm` (3 operating systems) and `native` (build, run, diff). Native output uses the parser's own message text, so it only matches because the `XMLMessages` bundle is included (`mq-core` ships it in `META-INF/native-image`).
Read: R2's `resource.xsd` (`tools/xsd-oracle/`, Apache 2.0, attribution in `NOTICE`) and the R2 parser's Java checks.

## 4. Surprises

1. The `xsd:unique` constraints are looser than they look: two plain GETs without `item` are valid; ids inside a `Transaction` are not checked by the schema.
2. A real resource found a validator bug after the suite was green: `xsi:schemaLocation` (see parse-time report). The suite is only as good as the cases; add every real-world file as a case.
3. Messages from the JDK parser for malformed XML differ by JDK version (for example a duplicate attribute prints a W3C URL). They are identical on JVM and native here, but a JDK upgrade can change the text; the golden file would then change and must be reviewed.

## 5. Open questions for a human

1. Should the golden set grow with every resource from real projects (private ones cannot be committed), for example through a script a team runs on its own files?
2. MQ rejects duplicate ids inside a `Transaction` although the XSD does not. Keep?

## 6. Recommended change to the R2 Next spec

"Validation = R2's XSD + mpath reference checks + limit/offset on queries only + step ids unique in the whole resource. The golden cases (`golden/`) are the contract; the library must give identical results on the JVM and in a native binary."
