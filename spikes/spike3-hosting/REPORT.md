# Spike 3: hosting several backends (A) and datasources from backend.yaml (B)

THROWAWAY spike. Quarkus 3.40.1, Mandrel 25.0.4.1 / Temurin 25 on GitHub Actions `ubuntu-latest` (4 vCPU, 15,989 MB RAM), PostgreSQL 16 as a job service, HSQLDB 2.7.4 in memory.
Question C (the baking variant) is reported in `../spike2-native-parse/REPORT.md` (boot comparison) and `../spike3-baking-variant/REPORT.md`.

## 1. Outcome

- **A: pass for option 1 (one MQ process per backend)**; option 2 (many backends in one process) also works and is a fallback.
- **B: pass in JVM and native**, with one native setting for HSQLDB (none for PostgreSQL). One image serves both database kinds at once.

## 2. Numbers (observed; CI runs `37083172121` and `37083564331`, raw files in `results/`)

**A, option 1: N separate processes**, each hosting one minimal backend (two idle HSQLDB pools), 4 vCPU, all started at once:

| N | JVM total RSS (avg each) | JVM all ready | Native total RSS (avg each) | Native all ready |
|---|---|---|---|---|
| 1 | 102-106 MB | 0.84-1.1 s | 56 MB | 0.12 s |
| 5 | 631-632 MB (126 MB) | 2.7-3.8 s | 277 MB (55 MB) | 0.08-0.11 s |
| 20 | 2.51-2.53 GB (126 MB) | 11.1-15.0 s | 1.10 GB (55 MB) | 0.30-0.42 s |

Reference: about 1 GB for 20 apps on Tomcat (R2 assessment); the spec's idle target is 150 MB (from the brief). Per process: native 55 MB and JVM 126 MB are both below it. 20 native processes use about as much as 20 Tomcat apps did; 20 JVM processes use about 2.5 times as much.

**A, option 2: one JVM process, 20 backends added and removed at runtime** (each with its own classloader and route prefix `/b/<name>/ping`):

| Step | Result |
|---|---|
| Add 20 backends without a restart | all answer (`hi from b7`); heap 15.5/33.1 MB to 104.4/84.8 MB, RSS 163/157 MB to 283/301 MB (about 6-7 MB per backend; the test compiles a class per backend with javac, so this is an upper bound) |
| Remove 20 | route returns 404; **20 of 20 classloaders unloaded after GC**; heap back to 13 MB; RSS stays at 225-234 MB (not returned to the OS) |

**B: datasources from `backend.yaml`, created at boot with Agroal's programmatic API** (no build-time datasource config; secrets as `${DB_PASSWORD}`, resolved from the environment, a missing variable is an error):

| | JVM | Native |
|---|---|---|
| PostgreSQL backend (`quarkus-jdbc-postgresql` extension on the classpath) | works | works, no extra setting |
| HSQLDB backend (plain driver jar, no Quarkus extension), 2 datasources | works | first run: `SQLException: NullPointerException` from `LobManager.createSchema` (driver reads `org/hsqldb/resources/*` from its jar); **works with `quarkus.native.resources.includes=org/hsqldb/resources/*`** |
| PostgreSQL and HSQLDB backends in the same process/image | yes | yes (`rssKb` 76 MB with both) |
| Native build | n/a | 2:05 wall, 3.60 GB peak, binary 65.5 MB |

## 3. Evidence

Observed: `.github/workflows/native-spikes.yml` job `spike3-hosting`; `ci/processes.sh`; `src/main/java/io/mq/hosting/Backends.java` (datasource creation, add/remove, unload check). The first native HSQLDB failure is kept in
`evidence/run1-native-hsqldb-npe-trace.log`.
Read: Agroal programmatic API (`AgroalDataSourceConfigurationSupplier`, used as written in `Backends.create`).

## 4. Surprises

1. The `quarkus-jdbc-postgresql` extension is what makes the PostgreSQL driver native-ready; HSQLDB has no extension and needed a resource include. Drivers for other kinds (Oracle, SQL Server) will each have their own requirements; there are Quarkus extensions for them.
2. A backend whose datasource fails to start must not stop the process (the loader records the error per backend): the native HSQLDB failure showed this is needed.
3. Removing a backend frees its classes but the JVM keeps the memory: RSS after removal is far above the starting point.
4. Option 2 was measured for add/remove and unloading only. Failure isolation and script isolation between backends in one process were **not** tested.

## 5. Open questions for a human

1. Is 20 JVM processes at 2.5 GB acceptable for the Dev server (dev mode runs on the JVM)? Typical developer use (1-5 backends) is 0.1-0.63 GB. If not, option 2 is about 10 times cheaper per backend but gives up process isolation.
2. Which databases must the standard image carry? One image with PostgreSQL + HSQLDB works; a backend that needs another driver needs an image built with it (the driver set is fixed at build time, the choice per backend is runtime).

## 6. Recommendation for hosting backends

Host each backend as its own MQ process (option 1); the Dev server is the launcher and proxy. It matches production (one pod per backend), isolates failures and scripts, and the per-process cost is within the 150 MB target (native 55 MB, JVM 126 MB). Keep option 2 as a documented fallback for memory-constrained development machines, since runtime add/remove and classloader unloading work.

## 7. Recommended change to the R2 Next spec

Add: "Datasources are created at boot from backend.yaml with Agroal's programmatic API; the JDBC drivers a backend may use are fixed when the image is built (PostgreSQL via its Quarkus extension, HSQLDB with `quarkus.native.resources.includes=org/hsqldb/resources/*`)."
