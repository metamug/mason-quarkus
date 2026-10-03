# Spikes (throwaway)

Everything under `spikes/` is **throwaway code** written to answer one question each, measured before anything is built
on it. It is not the product, it is not maintained, and it does not follow the project's conventions. Each spike has a
`REPORT.md` in the format of the build brief (outcome, numbers, evidence, surprises, open questions, recommended change).

| Folder | Question | Status |
|---|---|---|
| `spike1-dev-reload/` | Can a change to a resource XML file reach the running app in Quarkus dev mode without a restart, fast enough? | done: Quarkus' own mechanism partial, own watcher passes (see REPORT.md) |
| `spike2-native-parse/` | Do the StAX parser and validator build into a native image and give identical results? Also boot cost of parsing vs baking | done: pass with one native setting (REPORT.md) |
| `spike3-hosting/` | How does the Dev server host several backends, and can datasources come from backend.yaml (JVM and native)? | done: one process per backend; datasources pass (REPORT.md) |
| `spike3-baking-variant/` | Question C: build-time object model recorded into the startup bytecode, compared with parsing at boot | done: works, not recommended (REPORT.md) |

Environment of the first measurements: Quarkus 3.40.1, JDK 17.0.17 (Temurin), Maven 3.9.16, Windows 10, H2 in memory.
Paths in the scripts are relative or taken from environment variables.

`shared-parser/` is the StAX parser and validator used by all spikes; `shared-resources/` holds the golden cases (20 valid, 17 invalid). Native measurements run in GitHub Actions (`.github/workflows/native-spikes.yml`); nothing needs GraalVM installed locally.
