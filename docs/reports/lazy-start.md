# Lazy start of a backend: first-request latency and idle stop (Next work item 5)

THROWAWAY launcher (`spikes/lazy-start/Lazy.java`, a stand-in for the Dev server's launcher and proxy: one process per backend, started on the first request, stopped after an idle time). Backend: the `examples/reload` app (one valid resource). Quarkus 3.40.1; JVM = Temurin 25.0.4, native = Mandrel 25; `ubuntu-latest`, 4 vCPU, 15,989 MB RAM (run `37093534453`); Windows 10 JVM 17 local. Idle time 1.5 s.

## 1. Outcome

**Pass for the one-process-per-backend design, with a clear split.** A lazily started **native** backend answers its first request in **38-52 ms** (136 ms on a cold disk cache) and costs 55 MB while running. A lazily started **JVM** backend needs **1.0-1.2 s** on Linux (1.4 s on Windows) and 100 MB. An idle backend stops in 3 ms (native) or 40 ms (JVM) and then costs nothing. Starting five JVM backends at once takes 4.4 s on 4 vCPU; five native ones take 0.1 s.

## 2. Numbers (observed, Linux; five runs where a list is shown, ms from the request leaving the client to the answer)

| | Native | JVM (Linux) | JVM (Windows) |
|---|---|---|---|
| First request to a stopped backend | 136, 52, 48, 43, 38 | 1,208, 1,099, 1,034, 1,050, 1,089 | 1,422-1,456 |
| of which: process start until `/status` answers | 118, 37, 36, 32, 27 | 1,175, 1,072, 1,011, 1,027, 1,063 | 1,366-1,400 |
| Warm request through the launcher (median of 200, max) | 1.5 (5.4) | 2.4 (6.9) | 2.5 (6.2) |
| Resident memory of the running backend | 54-56 MB | 100-102 MB | not measured |
| Last request to the backend gone (idle 1.5 s) | 1,535 | 1,591 | 1,566 |
| of which: `SIGTERM` to exit | 3 (exit 143) | 40 (exit 143) | 1 |
| Five stopped backends asked at once: all answered | 105 (85-105 each) | 4,356 (3,693-4,356 each) | 7,066 |
| Resident memory of those five | 226 MB | 527 MB | not measured |
| Warm launcher overhead | ~1 ms | ~1 ms | ~1 ms |

The extra cost of the launcher on a warm request is the proxy hop (about 1-2 ms with a plain JDK HTTP client).

## 3. Evidence

Observed: workflow `.github/workflows/lazy-start.yml` (artifact `lazy-start-results`), raw JSON in `spikes/lazy-start/results/`. The launcher records its own start and stop events at `/_lazy/events`; the driver `measure.mjs` times requests from the outside.
Assumed: a real Dev server adds some work to start (read `backend.yaml`, create datasources); not measured here, the example has no datasource.

## 4. Surprises

1. The first native start was 136 ms, then 27-37 ms: the binary was read from disk once. A freshly built image on a cold node will look like the first figure.
2. JVM stop is 40 ms and native 3 ms; neither is a problem. Stopping is not the cost, starting is.
3. Five JVM starts at once are 4x slower per backend than one (4 vCPU shared); the launcher should limit how many it starts in parallel.
4. Port assignment by "bind port 0, release, start" raced under five parallel starts (one Quarkus saw its port taken). The spike uses fixed ports; a real launcher must reserve ports or retry.

## 5. Open questions for a human

1. Is about 1 s for the first request to a lazily started JVM backend acceptable in the Dev server? Native avoids it but needs a native build per backend change (2.5 min), so the Dev server will most likely run on the JVM. A warm-up on project open (start the backends before the first request) would hide it.
2. What idle time? 1.5 s was used to measure; a real value is minutes.

## 6. Recommended change to the R2 Next spec

"The Dev server starts a backend's MQ process on the first request (about 1 s on the JVM, under 0.1 s native) and stops it after the idle time (milliseconds); it limits parallel starts and reserves ports before starting."
