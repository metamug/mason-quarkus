# Spike 2: StAX parser and validator under native-image

THROWAWAY spike. Quarkus 3.40.1, Mandrel 25.0.4.1 (JDK 25.0.4.1, required by Quarkus 3.40.1: 23.1 was rejected), GitHub Actions `ubuntu-latest`
(ubuntu24 image 20260927.320.1, 4 vCPU, 15,989 MB RAM, 3 GB swap). Same code is run on the JVM (Temurin 25) and as a native image. No database.

## 1. Outcome

**Pass, with one native setting.** The shared StAX parser and validator needs no reflection configuration and gives identical results for all 37 golden
cases on the JVM and in the native image. It needs one resource bundle included (`-H:IncludeResourceBundles=com.sun.org.apache.xerces.internal.impl.msg.XMLMessages`),
without which a malformed file crashes the native process at boot. Baking the model in at build time gives no measurable boot or memory advantage over parsing at boot.

## 2. Numbers (observed; CI runs `37081839666`, `37082116341`, `37082823615`; raw files in `results/`)

| Measure | JVM | Native |
|---|---|---|
| Golden cases (20 valid, 17 invalid, folder outside the binary, configurable by `MQ_RESOURCES_DIRS`) | 37 files, 20 valid, 17 invalid | **identical output for all 37** (`diff` of per-file results) |
| Parse + validate all 37 files, first pass (cold) | 44.9 / 52.9 / 76.4 ms | 4.1 / 4.2 / 7.7 ms |
| Same, second pass in the same process | 13.9 / 19.8 / 25.1 ms | 3.7 / 3.5 / 6.4 ms |
| Quarkus "started in" | 0.883 s | 0.021-0.031 s |
| Process start to first successful HTTP response | not measured | 66 / 70 ms |
| Resident memory after boot, 37 resources loaded | 123.7-123.9 MB | 62.4-63.1 MB |
| Binary size | n/a | 55,970,128 bytes (56.0 MB) |
| Native build: wall time / peak resident memory | n/a | 1:41, 1:43, 2:37 / 2.92-2.97 GB |
| Reflection/resource config in the project | n/a | none (no `META-INF/native-image`); one `additional-build-args` entry for the XMLMessages bundle |

Baking the same 20 resources into the image (Spike 3, question C; separate app, so binary sizes and build times are not comparable, boot and memory are):

| | Parse at boot (this spike) | Baked at build time |
|---|---|---|
| Native "started in" | 0.021-0.031 s | 0.021 s |
| Native process start to first response | 66-70 ms | 74 ms |
| Native resident memory after boot | 62.4-63.1 MB | 62.4 MB |
| JVM "started in" | 0.883 s | 0.788-0.806 s |
| JVM resident memory | 123.7-123.9 MB | 135.2-135.7 MB |
| Strings matching `XMLStreamReader`/`xerces` in the native binary | 598 | 33 |

Parsing is 4-8 ms in native and 45-76 ms cold on the JVM, i.e. about 6-11% of the native time to first response and about 5-9% of JVM start-up.

## 3. Evidence

Observed: `.github/workflows/native-spikes.yml` jobs `spike2-native-parse` and `spike3-baked-native`; artifacts `spike2-results`, `spike3-baked-results`. First native run (`37081215808`, kept as
`evidence/run1-native-startup-failure.log`) failed at boot:
`java.util.MissingResourceException: Could not load any resource bundle by com.sun.org.apache.xerces.internal.impl.msg.XMLMessages` raised while the JDK formatted the error for `not-xml.xml`
(a `RuntimeException`, not an `XMLStreamException`, so the parser's own error handling does not catch it). Well-formed files parsed fine in that run.
Read: `ResourceParser.java` (this repo, `spikes/shared-parser`); Quarkus native requirement from the build error `Out of date version of GraalVM or Mandrel detected: 23.1.12.1. Quarkus currently supports 25.0.0`.

## 4. Surprises

1. Quarkus 3.40.1 needs Mandrel/GraalVM 25 (JDK 25) for native builds, not 21/23.1. The repo's workflow now pins `java-version: 25`.
2. The failure mode of the missing bundle is worse than a missing message: an invalid file takes the process down at boot. Independent of the setting, the loader should catch `RuntimeException` per file so one bad file cannot stop the server.
3. Baking removes the XML parser code from the binary (598 to 33 matching strings) but not measurable boot time or memory.
4. Native build resource use is high for such a small app: 2.9-3.5 GB peak, 1:41 to 2:54 (3 runs, noisy runners).

## 5. Open questions for a human

1. Is "parsing is about 4-8 ms native / about 45-76 ms cold JVM" negligible next to process start-up? It is about 6-11% of native time to first response; if the threshold is stricter, baking is the only lever, and it gains only those milliseconds.
2. Native memory with 20 baked resources was identical to parsing at boot; confirm that RSS is the metric that matters (heap after boot was not compared separately).

## 6. Recommended change to the R2 Next spec

Add: "The native build must include the resource bundle `com.sun.org.apache.xerces.internal.impl.msg.XMLMessages`; the loader must treat any exception from parsing one resource file as that file being invalid."
