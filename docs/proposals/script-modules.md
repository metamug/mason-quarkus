# Proposal (test 5): scripts that share code, and extra libraries. No code written; waiting for your choice.

## The question

Today every `.kts` file is independent. Real projects will want shared helpers (one hash function, one date format, one error shape) and sometimes a library (a JSON, date or crypto library). Three ways to allow it, and how extra libraries are declared in each.

What I know from the tests so far (observed): the CLI compiles all scripts in one compiler run and links them with a generated registry; the Dev server compiles each file on first use and again when it changes; Kotlin does not let ordinary Kotlin sources refer to classes compiled from scripts; `kotlinx.serialization`, coroutines, `javax.crypto` and others work in native when they are on the compile class path (Kotlin-native report); the Dev server class path is what a script may import; Kotlin versions must match the Quarkus BOM (2.4.10 now).

## Option 1: no cross-script use

Each script is self-contained. Shared code lives in a **Java or Kotlin library jar** declared in the project file (`libs`), compiled by the project's own build (Maven), like the `Execute` plugin jars.

| | |
|---|---|
| Hot reload (Dev server) | A script change recompiles that one script (seconds). A library change needs the jar rebuilt and the `libs` entry reloaded: not instant, but the same as plugins today (tested: new class loader). |
| Native (CLI) | Nothing new: scripts compiled as now, the jar on the native build class path. |
| Declaring extra libraries | One list, `libs:` Maven coordinates or jar paths, used by both the Dev server class path and the CLI compile and native class path. |
| Cost / risk | No new mechanism. Duplicated helper code in scripts is the price. |

## Option 2: a shared source folder of ordinary Kotlin files (`lib/*.kt`)

Plain `.kt` files (classes, functions, objects, not scripts) in a `lib` folder; scripts import them by package like any Kotlin code.

| | |
|---|---|
| Hot reload | The Dev server compiles `lib/` to classes (the Kotlin compiler, in process) when any file there changes, then recompiles the scripts that depend on it (simplest: all of them; seconds each, a few seconds in total for a typical project, **assumed, not measured**). A script change alone recompiles only that script. |
| Native | The CLI compiles `lib/` first, then the scripts against it, then the native build. Ordinary classes, no reflection, same boundary as scripts today. |
| Declaring extra libraries | Same `libs:` list. `lib/` code and scripts see the same libraries. |
| Cost / risk | Two compile stages in the Dev server and a dependency rule ("a lib change recompiles every script"). Needs the Kotlin compiler as a library in the Dev server (already there for scripts). Lib code has no `params`, `steps` or `response`: it receives them as arguments. |

## Option 3: scripts import scripts (`@file:Import("common.kts")`)

Kotlin script-to-script import; the imported script body runs as part of the importer.

| | |
|---|---|
| Hot reload | The host must track imports and recompile importers when an imported script changes; the Kotlin scripting API supports resolving imports but MQ must implement the tracking. |
| Native | The scripts are compiled together in one compiler run; supported, but imported scripts are classes with constructors and run their top-level code each time (side effects at import), and an imported script needs the provided properties passed along. |
| Declaring extra libraries | Same `libs:` list. |
| Cost / risk | Surprising semantics (top-level code of an imported script runs on every import), and import behaviour differs between the scripting host and the command-line compiler in subtle ways (the command-line compiler already needed a different setup from the host in test 1). Highest risk of the Dev server and the CLI differing. |

## Comparison

| | Option 1 | Option 2 | Option 3 |
|---|---|---|---|
| Shared code between scripts | via a jar | yes, as classes | yes, as scripts |
| Dev server and CLI behave the same | most likely | likely (same compiler, two stages) | least likely |
| Hot reload of shared code | rebuild a jar | automatic, seconds | automatic if imports are tracked |
| Native risk | lowest | low | medium |
| Work to build | none | medium | medium to high |

## Recommendation

Start with **Option 1** (what the decisions already allow: libraries declared once for both products), and add **Option 2** if projects show a real need for shared script code; skip Option 3. The `libs` declaration is the same in all three, so choosing Option 1 now loses nothing.

## What I will test once you choose

One extra library in both modes, with the shop scripts: a script that imports it, compiled by the CLI, built into the native binary, and run on the Dev server loader. Candidate library (small, pure Kotlin/Java, common): `kotlinx-datetime` or `org.apache.commons:commons-text`. Tell me if you prefer another. For Option 2 the test would add a `lib/` helper used by two scripts and a change to it on the Dev server.

## Decision needed

Which option (1, 2, or 3), and which library to use for the test.
