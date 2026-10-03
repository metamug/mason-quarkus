# Dev reload on Linux and macOS, and the extension's own native settings (Next work items 3 and 4)

Quarkus 3.40.1, Temurin 17 (JVM runs), Mandrel 25 (native). GitHub Actions runs `37088458745` (final) and earlier ones noted below. Runners: `ubuntu-latest` 4 vCPU, 15,989 MB RAM; `macos-latest` arm64, 3 vCPU, 7 GB RAM. Windows is a local run (Windows 10, JDK 17).

## 1. Outcome

- **Item 4: pass on Linux, macOS and Windows.** MQ's own debounced watcher reloads in 20-30 ms on Linux and Windows with the JDK `WatchService`. On macOS the JDK service **polls and takes about 2 s**, so macOS uses file-system events through `io.methvin:directory-watcher`, which takes **30-90 ms**. All five Spike 1 scenarios hold on every platform: no restart, the 20-file change is atomic (a request sees 0 or 20, never between), the old model is served while the XML is invalid, recovery after the fix works.
- **Item 3: pass.** The extension carries the `XMLMessages` bundle setting itself (a `NativeImageResourceBundleBuildItem` in the deployment module, and `META-INF/native-image` in `mq-core`). The example app has **no native settings**; its native binary serves a valid file and reports a malformed one (`broken.xml:1: not well-formed...`) instead of crashing.

## 2. Numbers (observed; ms from file write to the change being served, 5 runs where a list is shown)

| Scenario | Linux, JDK watcher | macOS, JDK watcher (polls) | macOS, file-system events | Windows, JDK watcher |
|---|---|---|---|---|
| Edit existing | 22-23 | 1,972-2,004 (first run 543) | 29-77 | 26-32 |
| Create new | 18-22 | 1,929-2,117 | 34-69 | 28-31 |
| Delete | 17-24 | 1,946-2,055 | 30-93 | 26-32 |
| Rename | 18-20 | 1,917 | 43 | 32 |
| 20 files at once (visible counts) | 25-27 (0, 20) | 2,069 (0, 20) | 77 (0, 20) | 63 (0, 20) |
| Invalid XML, old model kept | yes | yes | yes | yes |
| Recovery after fix | 17 | 1,931 | 54 | 24 |
| Restarts of the app | 0 | 0 | 0 | 0 |

Reload work itself (parse 1 file, swap): 0.7-1.8 ms. FSEvents' default latency is 0.5 s: with it macOS measured 258-680 ms (run `37087738115`); setting 10 ms gives the figures above.

Native build of the example (Mandrel 25, `ubuntu-latest`): **2:34 wall, 2.98 GB peak resident** (build), binary 56.0 MB, **57 MB resident** after start with one valid and one malformed file. Raw: `examples/reload/results/`.

## 3. Evidence

Observed: workflow `.github/workflows/reload.yml` (jobs `scenarios` on Linux and macOS, `native-extension`); results in `examples/reload/results/*.txt` and `windows.json`; `mq-core/src/test/.../ReloadTest.java` (invalid keeps old model, snapshot immutability, 20-file burst).
Read: `directory-watcher` 0.18.0 API via `javap` (`MacOSXListeningWatchService.Config.latency()`).

## 4. Surprises

1. The JDK watcher on macOS polls every ~2 s here, as Spike 1 feared. Event libraries fix it, but their default latency (0.5 s) still hides most of the gain; it must be set.
2. The first native build with the macOS library **failed**: `Class.forName("literal")` made the native-image analysis include the library, and JNA's `CarbonAPI` cannot initialise at build time on Linux. Loading the class by a name built at run time (`FolderWatcher.class.getPackageName() + ...`) keeps it, and JNA, out of the production binary.
3. Windows git dropped the executable bit of `run.sh`; the CI step passed silently through `| tee`. Fixed with `bash run.sh` and `pipefail`.

## 5. Open questions for a human

1. Is a third-party library (`directory-watcher` + JNA) acceptable in the **dev-mode** classpath of the JVM Dev server? It is absent from the native production binary. If not, accept 2 s reload on macOS.
2. Windows is measured locally only, not in CI. Add `windows-latest` to the matrix?
3. Per-file keep-old-model means one invalid file in a 20-file change applies the other 19. Should an invalid file block the whole change (all-or-nothing)?

## 6. Recommended change to the R2 Next spec

"Dev reload uses MQ's own debounced folder watcher (JDK `WatchService`; file-system events through directory-watcher on macOS), full re-read, one atomic model swap, last valid version kept per file. The JVM Dev server only; the production binary does not watch. The extension ships the native settings the XML parser needs; users add none."
