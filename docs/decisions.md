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

## Owner decisions after the planning summary (v2)

- **Script modules.** Start with option 1: no cross-script use; shared code goes in a declared jar. Then option 2: a shared `lib/*.kt` source folder, for interdependent scripts. Option 3 (`@file:Import`) is skipped. Test library for script imports: `org.apache.commons:commons-text`.
- **One project file.** `mq.yaml` **replaces** `backend.yaml`. `backend.yaml` existed only as the datasource file of the hosting spike (`datasources:` with `kind`, `url`, `user`, `password: ${ENV}`); that section moves into `mq.yaml` unchanged, so an existing file can be pasted in. For one release, if there is no `mq.yaml`, a `backend.yaml` is read as its `datasources` section with a deprecation warning. New sections: `scripts`, `lib`, `plugins`, `libs`, `drivers`, `properties`. The Dev server and the CLI read the same file with the same reader.
- **Plugin return types.** A plugin that returns anything outside JSON-ready types (String, Number, Boolean, null, List, Map with String keys) is an error naming the step, the class and the offending path. Implemented and tested.
- **R2 plugin adapter.** Not now. Check done: of the 35 distinct real resources, one contains an `Execute` (`execute.xml`, a parser test fixture, not an application) and one more (`persist.xml`, rejected old dialect). No real application resource uses `Execute`, so there is no evidence for an adapter.
- **Provisional semantics accepted** (Sql output default, `requires`, item requests, error mapping), including the 0-based mpath row index. No real resource uses a numeric index.
- **Memory.** About 600 MB per Dev-server backend with interpreted scripts is too heavy at 20 backends (about 12 GB). Build a shared scripting host across backends, but **measure first** (see `docs/reports/shared-host.md`).

## Scripts are the one code mechanism of v1 (plugins are compatibility only)

- **Decision.** Code in a project is Kotlin: scripts for glue, `lib/` classes and declared `libs` for heavy or reusable logic. The plugin API is not part of v1; the existing `io.mq.plugin.Plugin` loader stays as a compatibility feature without new work, and an `mtg-api` adapter is built only if real R2 plugins turn up.
- **Why.** A plugin is a separate project, a build and a jar to copy; a script is an edit and a save (0.4 to 0.8 s to recompile). `Sql` already passes every row on (`steps["id"]` is a list of maps), so result and response processors (`classname` on `Sql` and `XRequest`) are a `Sql` step followed by a `Script`. Of 35 distinct real resources only a parser test fixture uses `Execute`.
- **What scripts need so that this holds** (issues): a `db` object (query, update, transaction with typed binding), raw responses, script-backed extension points (auth, custom parameter types, start/stop hooks), editor support, a documented pattern for state in `lib/` objects, and platform capabilities (mail, queue) declared in `mq.yaml`.
- **Open check.** Whether any real plugins exist outside the repositories searched; if a team has them, the adapter moves toward v1.
- **Backlog changes.** #7 now the optional compatibility adapter (future); #8 and #45 closed; #21 now script-backed extension points; new #48 to #53 (`db` in scripts, raw responses, editor support, lib state pattern, capabilities, transform sugar).
