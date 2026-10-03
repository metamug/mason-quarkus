# Test 1: Kotlin scripts in a native binary (compiled ahead of time, built in a Mandrel container)

THROWAWAY spike code in `spikes/kotlin-native/`. Kotlin 2.2.20 compiler (command line, scripting plugin), Mandrel 25.0.4.1 from the container image `quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25`, `ubuntu-latest` 4 vCPU 15,989 MB RAM, run `37103999214` (builds A and B in `37103781818`). The scripts: the shop scenario's `hashpw`, `ordercalc`, `transition`, `tokens`, the Groovy script rewritten in Kotlin (`legacy.kts`), and five boundary scripts (reflection, crypto, serialization, resources and miscellany) with 31 constructs.

## 1. Outcome

**Pass: the Kotlin approach works in native. No stop needed.** All five shop scripts (the four Kotlin ones and the Kotlin rewrite of the Groovy one) compile ahead of time and give the same results in the native binary as on the JVM, **with no native declarations at all**. The binary smoke-tests itself as part of the build. Of 31 boundary constructs, 26 work with no declaration, 4 more work once declared (resources, reflection on a script's own classes, Java serialization, locale data), and **1 does not work at all: `kotlin-reflect`** (`::class.members`). Two things fail silently if not declared (see Surprises).

## 2. Numbers (observed)

| Build | Declarations | Build time | Binary | Selftest | Resident memory of the selftest |
|---|---|---|---|---|---|
| A | none | 97 s | 26.1 MB | 43 pass, 0 required failures, 5 boundary failures | 52 MB |
| B | resources, bundle, reflection, serialization, proxy files | 82 s | 26.1 MB | 46 pass, 0 required, 2 boundary | 52 MB |
| C | B + `-H:IncludeLocales=de,en` | 60 s | 26.1 MB | 47 pass, 0 required, **1 boundary** | 50 MB |
| JVM | none | n/a | n/a | 48 pass, 0 failures | n/a |

The whole selftest (every script, 48 checks) takes 0.15 s in the native binary. Build memory was not recorded in this spike (the Quarkus builds in other reports need about 3 GB).

Boundary results (A = nothing declared):

| Construct | A | after declaring |
|---|---|---|
| `javax.crypto`: SHA-256, HMAC-SHA256, AES/GCM round trip, PBKDF2, `SecureRandom`, RSA sign and verify | works | n/a |
| `kotlinx.serialization` (encode, decode, JSON tree; compiler plugin) | works | n/a |
| coroutines (`runBlocking`, `async`), regex, `BigDecimal`, time zones (`Asia/Kolkata`), `HttpClient` object, `UUID` | works | n/a |
| bundled file (`getResourceAsStream`) | works (file was in a class-path folder) | n/a |
| `Class.forName` and `getMethod().invoke()` on JDK classes, a dynamic proxy | works (the proxy needs `proxy-config.json` in a real project; it passed here) | |
| `ResourceBundle` | **fails** (`MissingResourceException`) | `resource-config.json` with `bundles` |
| reflection on the script's own class (`declaredFields`) | **silently returns an empty list** | `reflect-config.json` |
| Java serialization (`ObjectOutputStream`) | **fails** (`UnsupportedFeatureError`) | `serialization-config.json` |
| German number format (`String.format(Locale.GERMANY, ...)`) | **silently wrong** ("1,234.50") | `-H:IncludeLocales=de` |
| **`kotlin-reflect`** (`Point::class.members`) | fails (`KotlinReflectionNotSupportedError`) | **no fix found** |

## 3. How the scripts are compiled (the design this validates)

- A **script definition** (`MqScript`, Kotlin) gives scripts the names `params`, `steps`, `response`, `request`, exactly what the shop scripts expect. The same definition is used by the Dev server to evaluate and by the CLI to compile (the Dev server side is the next test).
- The CLI step is the Kotlin compiler started directly with the scripting plugin: `-Xplugin=kotlin-scripting-compiler.jar -P plugin:kotlin.scripting:script-templates=io.mq.script.MqScript -Xallow-any-scripts-in-source-roots`. Whatever the scripts import must be on that class path: **that class path is the declaration** of what a script may use (decision 2). A script that imports something not declared fails to compile, with the compiler's message and line.
- A **generated Java registry** maps script name to `new Hashpw(params, steps, response, request)`: no reflection, no class loading by name. (Kotlin refuses to let ordinary Kotlin sources refer to classes compiled from scripts; Java can.)
- The runner (`Main`) has `selftest <smoke.json>`; `build-native.sh` runs it on the binary it just built and fails the build when a required case fails.

## 4. Evidence

Observed: workflow `.github/workflows/kotlin-native.yml`; results in `spikes/kotlin-native/results/` (selftests, build logs, times); scripts in `spikes/kotlin-native/scripts/`. JVM and native print the same checks (`jvm-selftest.txt`).
Read: Kotlin script definition API (`@KotlinScript`, `providedProperties`).

## 5. Surprises

1. **Silent wrong answers**, not only crashes: reflection on a script's own class returned an empty list, and locale formatting fell back to English. A smoke test that only checks "does not crash" would pass. The CLI's smoke test must check values, and the declared-library list should be explicit about locales and reflection.
2. `providedProperties` with generic types (`Map<String, String>`) lose their type arguments in the command-line compiler (`Map<K, V>`), so scripts did not compile. Non-generic wrapper types (`Params`, `Steps`, `Response`) fix it.
3. The `kotlinc` wrapper script cannot be used for this: it does not pass the plugin options. The CLI must start the compiler class (`K2JVMCompiler`) itself, so the CLI ships or downloads the Kotlin compiler (about 80 MB). Colon-separated Windows class paths with drive letters broke the first attempt.
4. Windows build of the same compile step works (JDK 17, kotlinc 2.2.20); the native step is Linux-only (container).
5. `kotlin-reflect` is the one common-looking library that cannot be made to work. Scripts must not use full Kotlin reflection (`KClass.members`, `memberProperties`, ...). `::class.simpleName` and Java reflection on declared classes do work.

## 6. Open questions for a human

1. Where should the "what a script may import" declaration live? Proposal: a `libs` section in the project file (Maven coordinates, resolved to jars at CLI build time and on the Dev server's class path at run time); the same list drives both. The multi-script question (test 5) decides the rest.
2. Which locales and resource bundles must the standard binary carry? Proposal: `en`, plus the locales a project lists.
3. Is it acceptable that `kotlin-reflect` is unsupported in scripts? I propose to document it and have the validator warn on `kotlin.reflect.full`.

## 7. Recommended change to the R2 Next spec

"Scripts are Kotlin. A script sees `params`, `steps`, `response`, `request`. The CLI compiles scripts ahead of time with the Kotlin compiler and a declared library list, links them through a generated registry, and builds the native binary in a Mandrel container; the build smoke-tests the binary and fails on a wrong value. Full Kotlin reflection, unknown imports and dynamic code loading are not supported in the native binary."
