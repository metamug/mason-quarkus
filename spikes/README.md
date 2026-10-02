# Spikes (throwaway)

Everything under `spikes/` is **throwaway code** written to answer one question each, measured before anything is built
on it. It is not the product, it is not maintained, and it does not follow the project's conventions. Each spike has a
`REPORT.md` in the format of the build brief (outcome, numbers, evidence, surprises, open questions, recommended change).

| Folder | Question | Status |
|---|---|---|
| `spike1-dev-reload/` | Can a change to a resource XML file reach the running app in Quarkus dev mode without a restart, fast enough? | measuring |
| `spike3-baking-variant/` | Build-time object model recorded into the startup bytecode (no XML parsed at runtime), compared with parsing at boot | JVM measured; native pending |

Environment of the first measurements: Quarkus 3.40.1, JDK 17.0.17 (Temurin), Maven 3.9.16, Windows 10, H2 in memory.
Paths in the scripts are relative or taken from environment variables.
