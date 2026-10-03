# Decision log

Decisions taken by the owner after the spikes and Phase 3. Newest at the bottom of each section. Evidence is in `docs/reports/` and `docs/MQ-findings-and-outcomes.md`.

## Architecture

- Two products. **Dev server**: JVM, builds and hot-deploys, interprets Kotlin scripts. **CLI**: validates and builds the final native binary, compiles scripts at build time. One artifact does not need both hot deploy and native.
- Dev reload by MQ's own debounced watcher, JVM Dev server only; no watcher in the native binary. `directory-watcher` + JNA is accepted on the Dev server's classpath (dev-only, optional); the native binary never gets it.
- One process per backend, started lazily on first request, stopped when idle. Many backends in one process only as a fallback.
- No baking of the model. Quarkus 3.40.1, Mandrel 25 (JDK 25) for native. Native validation in GitHub Actions on Linux.
- Executors are plain Java over a JDBC `DataSource` (Agroal in production) rather than bound to Quarkus classes, so the same tests run without Quarkus.

## Operating values

- Lazy stop idle time: **10 to 15 minutes** in practice (1.5 s was only for measuring). The Dev server starts a backend in the background when its project is opened, so the user rarely sees the first-request delay (about 1 s on the JVM).
- 2.5 GB for 20 JVM backends is acceptable on a dev machine because lazy stop keeps few running.

## Behaviour

- A multi-file change: apply the valid files and keep the last good version of the invalid one, with a clear error. **The CLI build refuses on any error.**
- MQ rejects duplicate step ids inside a Transaction (stricter than the XSD). **Kept**, because all real resources pass (78 of 80 files found in the repositories; the 2 failures are old-dialect test fixtures that R2's own schema rejects). Re-check whenever a real resource is added to `golden/real/`.
- Semantics chosen in Phase 3 (Sql output default, `requires`, mpath `[n]` index, item requests, error mapping) are **provisional**: the shop acceptance suite decides. The 0-based mpath row index must match what R2's real scripts expect. Evidence so far: no real resource uses a numeric mpath index; the shop scripts read rows with Kotlin list access (0-based); only R2's documentation uses `[1]`, inconsistently.

## CI

- Windows is part of CI for `mq-core`, `mq-engine` and the reload scenarios (the owner's development machine is Windows). Status: all three run green on `windows-latest`.
