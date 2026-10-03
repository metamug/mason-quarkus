# A shared scripting host across backends: measured first, as decided

Quarkus 3.40.1, Kotlin 2.4.10, Temurin 25, `ubuntu-latest` 4 vCPU 15,989 MB RAM, run `37107857996` (workflow `shared-host.yml`, harness `spikes/shared-host/measure.mjs`, raw JSON in `spikes/shared-host/results/`). Code (experimental): `ScriptHost` and `DevScriptLoader.exportJar` in `mq-script`, `RemoteScriptLoader` in `mq-engine`, config `quarkus.mq.script-host`.

## 1. Outcome

**Worth building, with a break-even at about 2 backends.** One host process that holds the Kotlin compiler, and backends that carry none, cuts the memory of 20 Dev-server backends from **10.2 GB to 4.0 GB (-61 %)**, makes a backend about 150 MB instead of 510 to 660 MB, and makes warm script calls faster. It costs one extra process of 470 to 930 MB and about the same cold-start latency. JVM flags alone do not fix the problem: capping the heap barely changes the in-process figure.

## 2. What was measured

Each backend is the shop application on the JVM with the five sample scripts that need no database (token, legacy, format with commons-text, pricing quote and receipt using the shared `lib/`), each backend with **its own copy of the project** (so the host compiles 20 different projects, the worst case), PostgreSQL service up but unused. "In-process" = the compiler inside every backend (what exists today). "Remote" = the shared host compiles, the backend loads the classes (no compiler on its class path). "Capped" = `-XX:+UseSerialGC`, `-Xmx400m` in-process or `-Xmx160m` remote, metaspace limited; the host `-Xmx700m`. Memory is resident size from `ps` after every backend ran all five scripts.

| Backends | Mode | Flags | Per backend | Host | **Total** |
|---|---|---|---|---|---|
| 1 | in-process | default | 662 MB | 0 | 662 MB |
| 1 | in-process | capped | 514 MB | 0 | 514 MB |
| 1 | remote | default | 151 MB | 494 MB | 646 MB |
| 1 | remote | capped | 156 MB | 468 MB | 624 MB |
| 5 | in-process | default | 693 MB | 0 | 3,466 MB |
| 5 | in-process | capped | 515 MB | 0 | 2,577 MB |
| 5 | remote | default | 148 MB | 1,421 MB | 2,160 MB |
| 5 | remote | capped | 165 MB | 927 MB | **1,752 MB** |
| 20 | in-process | capped | 512 MB | 0 | **10,245 MB** |
| 20 | remote | capped | 162 MB | 748 MB | **3,994 MB** |

An idle backend (before any script) is 116 to 163 MB in all modes: the 350 to 540 MB difference is the compiler loading on the first script call, and it stays resident.

Latency (backend 0, milliseconds for token, legacy, format, quote, receipt):

| | First call of each script | Second call |
|---|---|---|
| 1 backend, in-process | 4,637 / 479 / 822 / 405 / 431 (the first includes loading the compiler) | 10 to 17 |
| 1 backend, remote | 5,140 / 430 / 720 / 489 / 400 | **2 to 4** |
| 5 backends starting at once, in-process | 19,198 / 2,333 / 3,757 / 2,327 / 1,768 | 9 to 25 |
| 5 backends starting at once, remote | 6,926 / 958 / 1,643 / 833 / 926 | 2 to 12 |
| 20 backends starting at once, in-process | **80,413** / 8,842 / 14,980 / 7,488 / 6,172 | 12 to 47 |
| 20 backends starting at once, remote | 7,581 / 8,614 / 3,343 / 3,661 / 3,332 | 2 to 5 |

All requests returned 200 in every configuration (no failures).

## 3. Why it works, and what it costs

- A compiled script is an ordinary class. The backend only needs the Kotlin runtime to run it; the compiler (about 400 MB resident once loaded) is needed only to produce it. The host returns one jar (script classes plus the compiled `lib/` classes) and the backend keeps it in memory with a class loader of its own, so there are no files to lock on Windows.
- Warm calls are 3 to 5 times faster remote, because the backend calls the class constructor directly instead of going through the scripting evaluator.
- The 20-backend start-up storm is the clearest gain: with 20 compilers running at once on 4 cores the first call took 80 seconds in-process; the one host serialises per project and answered in 8 seconds.
- Costs: one more process to start, supervise and size (470 MB at 1 project, 750 to 930 MB at 5 to 20 projects with its heap capped at 700 MB, **1.4 GB uncapped** at 5 projects, so the host must be capped); the host needs file access to the project folders (same machine, fine for a Dev server); a backend whose host is down cannot compile a changed script (it keeps running the version it has).

## 4. Surprises

1. **Capping the heap does little for the in-process case** (662 to 514 MB): most of the cost is the compiler's loaded classes, not a lazy heap.
2. A single backend is *worse* with a host (624 vs 514 to 662 MB total), so the Dev server should start the host only when a second backend needs scripts, or share it from the start and accept 470 MB.
3. The first script call in a fresh JVM costs 4.6 to 5.2 s either way (the compiler warms up once): hiding that behind "compile all scripts when the project opens" (together with the background backend start already decided) removes it from the user's view.

## 5. Open questions for a human

1. Build the host into the Dev server as a managed child process (started with the first backend that has scripts, stopped when no backend has been active for the idle time)? I recommend yes.
2. Compile all scripts of a project in the background when the project opens (host does the work, backend stays small)? Recommended: it removes the 5 s first call.
3. Cap for the host heap: 700 MB worked for 20 projects; tune with real projects.

## 6. Recommended change to the R2 Next spec

"The Dev server runs one scripting host process for all backends; a backend loads compiled script classes and carries no Kotlin compiler. The host is capped, started on demand and stopped when idle."
