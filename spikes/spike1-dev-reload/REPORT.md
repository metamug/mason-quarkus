# Spike 1: dev-mode reload of resource XML (runtime model)

THROWAWAY spike. Quarkus 3.40.1, JDK 17.0.17 (Temurin), Maven 3.9.16, Windows 10, H2 in memory. Runtime model: the XML is parsed at runtime
(StAX) into an in-memory registry; nothing is baked. `restartNeeded=false` for `mq/*.xml`.

## 1. Outcome

**Partial for Quarkus' own mechanism, pass for a small file watcher inside MQ.** Quarkus' no-restart notification works for edits and creates but takes about
2.0 s (request-triggered scan) or about 30 ms (watcher calling `doScan`), and it never reports deletes or renames of files that were created after the
app started. A plain `WatchService` in MQ that re-reads the folder passes every scenario in about 30 ms, for any folder path, without a restart.

## 2. Numbers (observed, ms from file write to the change being served; edit/create/delete are 5 runs each)

| Scenario | A: Quarkus, request-triggered | B: watcher calls `doScan` | C: own watcher re-reads folder |
|---|---|---|---|
| Edit existing | 1995-2026 | 30 | 19-31 |
| Create new | 1992-2020 | 27-31 | 26-31 |
| Delete (file created after start) | **not detected** | **not detected** | 29-32 |
| Rename (file created after start) | **not detected** | **not detected** | 32 |
| 20 files at once | 1102 (states seen: 0, 20) | 76 (states seen: 0, **14**, 20: not atomic) | 37 (states seen: 0, 20: atomic) |
| Invalid XML saved | 2037; old model kept; error reported | 30; same | 30; same |
| Recovery after the fix | 1971 | 26 | 27 |
| Two edits 50 ms apart | 1970, last write wins | 32, last wins | 35, last wins |
| Restarts during any run | 0 | 0 | 0 |

Delete of a file that **existed when the app started** is detected by Quarkus (about 2 s in mode A; checked in the earlier session, see Evidence).
Reload work itself (parse of the folder, swap of the model): 2.5-5.5 ms.

Folder outside `src/main/resources` (scenario 8):

| Configuration | Result |
|---|---|
| Own watcher, `-Dmq.dir=<any folder>` | everything works, about 30 ms, nothing else to configure |
| Quarkus watches it only if the folder is a **resource root**: `<resources><resource><directory>...</directory></resource></resources>` in the pom (profile `extra-root` in the sample app) | edit/create 30 ms with `doScan`, about 2 s without; delete/rename still undetected |
| Folder not a resource root, Quarkus scan only | **nothing is ever detected** (edit/create timed out at 15 s) |

## 3. Evidence

Read (Quarkus 3.40.1, tag `3.40.1`, raw files from github.com/quarkusio/quarkus):
- `core/deployment/.../builditem/HotDeploymentWatchedFileBuildItem.java` (location, glob or predicate, `restartNeeded`; only files of reloadable modules).
- `core/deployment/.../dev/RuntimeUpdatesProcessor.java`: `doScan` (about lines 470-630), `checkForFileChange` (about 1088-1230: new files are found by walking the module resource roots; **deletes are reported only for paths in `watchedPaths`, which are collected when the app (re)starts**), `TimestampSet.isRestartNeeded` (about 1517).
- `extensions/vertx-http/runtime/.../devmode/VertxHttpHotReplacementSetup.java:38` `HOT_REPLACEMENT_INTERVAL = 2000` and lines 143-173: a request triggers a scan at most every 2000 ms; `HotReplacementContext.doScan` itself is not throttled.
Observed: `./matrix.sh` (modes A, B, C) and `./outside.sh` (scenario 8); raw JSON in `results/`, raw dev-mode logs are not committed (local paths).

## 4. Surprises

1. The flat 2.0 s of Quarkus dev mode is a throttle in the HTTP handler, not file detection. Calling `doScan` from our own thread gives 30 ms.
2. `doScan` is not enough: files created after start are never reported as deleted or renamed, so the model keeps stale routes. The earlier note that this affects only the "no restart" path is confirmed.
3. In mode B the 20-file burst is **not atomic**: a request during the reload saw 14 (or 9) of 20 resources. An agent writing a project would see a half-applied project. The own watcher swaps the whole model once.
4. A folder that is not a resource root is invisible to Quarkus; the fix is a pom change, which an end-user project should not need.

## 5. Open questions for a human

1. Accept the recommendation: dev reload = MQ's own `WatchService` (debounce about 15 ms, full re-read, atomic swap, old model kept on error), no dependence on Quarkus' file watching or `doScan`?
2. `WatchService` on macOS is poll-based in the JDK; I did not measure it. Verify on a macOS GitHub runner before relying on the 30 ms figure there?
3. Repeat this on Quarkus 4.0 once stable (per the brief).

## 6. Recommended change to the R2 Next spec

Replace "Quarkus's file-watch mechanism (Spike 1) notices the changed file and calls into MQ" with "in dev mode MQ watches the configured resource folder itself (a debounced file-system watcher), re-parses it and swaps the model atomically; Quarkus' file watching is not used".
