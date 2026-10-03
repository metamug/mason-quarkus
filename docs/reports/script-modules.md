# Script modules and libraries: options 1 and 2, built and tested (test 5, after the owner chose)

Quarkus 3.40.1, Kotlin 2.4.10, Temurin 25, Mandrel 25, PostgreSQL 16, `ubuntu-latest`. Run `37107457822` (workflow `shop-kotlin.yml`, 40 HTTP checks per mode) and the unit tests of `mq-script` (13). The owner decided: option 1 first (no cross-script use, shared code in a declared jar), then option 2 (a shared `lib/` source folder for interdependent scripts), skip option 3; test library `commons-text`.

## 1. Outcome

**Both options pass in all three modes**: the Dev server loader (JVM), scripts compiled ahead of time (JVM) and the native binary. 40 of 40 HTTP checks in each mode.

| | Option 1: declared library | Option 2: shared `lib/` folder |
|---|---|---|
| What was tested | A script `format.kts` imports `org.apache.commons.text` (`WordUtils`, `StringEscapeUtils`, `LevenshteinDistance`, `StringSubstitutor`) | `lib/shop/Money.kt` (an `object`) used by two scripts, `quote.kts` and `receipt.kts` |
| Dev server | Library on the application class path: compiles and runs, 6 unit tests | The lib folder is compiled when any file in it changes, and each script is compiled again against the new classes on its next call; a new lib file and a new script that uses it work; a lib that does not compile is reported with file and line and the next fix is picked up |
| CLI | `LIBS` class path for the compiler; the libraries go on the native build class path | `tools/mq-compile-scripts` compiles the lib folder first, then the scripts against it; the lib classes are in the same jar |
| Native | **commons-text works with no native declaration** for the four classes used | Ordinary Kotlin classes: nothing to declare |
| Declared where | one `libs` list (until `mq.yaml` has a reader: `samples/shop/libs-pom.xml`) | the `lib` folder, by convention (`quarkus.mq.lib-dir`) |

## 2. Numbers (observed)

| | Dev loader (JVM) | Compiled (JVM) | Compiled (native) |
|---|---|---|---|
| Checks | 40 / 40 | 40 / 40 | 40 / 40 |
| Resident memory after the checks | 800 MB | 183 MB | 77 MB |
| Native build with scripts, lib, commons-text and a plugin | | | 1:40 wall, 3.37 GB peak, 64.8 MB binary |

Dev server lib recompile: the three `SharedLibTest` cases (compile the lib, change it, add a file, break it, fix it) run in 8 s including the first compiler start; the cost of a lib change is one in-process Kotlin compile of the folder plus a recompile of each script on its next call (not measured separately; the shared-host report has per-script compile times of 0.4 to 0.8 s once the compiler is warm).

## 3. Rules and limits found

1. **The lib is ordinary Kotlin, not script code**: it has no `params`, `steps`, `response` or `request`; a script passes what it needs as arguments.
2. **A lib change recompiles every script** (simplest correct rule); a script change recompiles only that script.
3. **Both products compile with the same class path**: on the Dev server the application class path (including the libraries declared for the project), in the CLI the `LIBS` list. Declaring a library once for both is the job of `mq.yaml` (decided: it replaces `backend.yaml`; reader not built yet).
4. A lib file with a compile error stops every script until fixed, with the error naming the lib file and line (the old classes are not silently kept, so a broken lib cannot go unnoticed).
5. **Kotlin reserved a trap in the documentation comment itself**: `lib/*.kt` inside a `/** */` comment opens a nested comment and the file does not compile. Not a product issue, noted for authors of lib files and scripts.

## 4. Evidence

`mq-script/src/test/java/io/mq/script/SharedLibTest.java`, `ShopScriptsTest.java` (commons-text and pricing), `examples/shop-kt/http-test.mjs`, `tools/mq-compile-scripts/compile-scripts.sh`, workflow `shop-kotlin.yml`.

## 5. Open questions for a human

1. Should a library used only by a script (commons-text) also be listed for the Dev server separately, or is "the application class path" enough? It is enough today because the Dev server and the project share a class path; a Dev server that is one program for many projects needs each project class path from `mq.yaml`, which is why the reader matters.
2. Option 3 is skipped as decided.

## 6. Recommended change to the R2 Next spec

"Scripts may import any library declared for the project and any class in the project `lib` folder. Libraries are declared once (project file) for the Dev server and the CLI. A change to `lib` recompiles the scripts that use it."
