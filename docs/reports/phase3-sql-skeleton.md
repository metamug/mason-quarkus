# Phase 3: MQ skeleton with Sql and Transaction executors (build order phase 3)

Quarkus 3.40.1, JVM Temurin 25, native Mandrel 25, `ubuntu-latest` 4 vCPU 15,989 MB RAM, PostgreSQL 16 (service container) and HSQLDB 2.7.4 (in memory). Runs: `37096114388` (engine tests), `37096313306` (HTTP, JVM and native).

## 1. Outcome

**Gate met.** Item read, item-plus-joins read and order POST work end to end with typed values: in plain JUnit tests on HSQLDB (Linux, macOS, Windows) and PostgreSQL, and over HTTP against the Quarkus application on the JVM **and as a native binary** (31 of 31 checks pass in both). This is the SQL-only part of R2's shop scenario; scripts, XRequest and Execute are Phase 4.

## 2. What exists

- `mq-engine` (plain Java, JDBC `DataSource`): `Routes` (`/v1.0/customer`, `/v1.0/customer/7`, parent resources), `Dispatcher` (method, path, query, body in; status, headers, JSON out), `Engine` (step loop, `when`, `requires`, response assembly, errors with `errorId`), `SqlRunner` (typed binding), `Inputs` (the `<Param>` declarations: required, default, type, min/max, length, pattern), `Json`.
- `mq-core` gained `Expr`: the `when` language (`eq ne lt gt le ge`, `and or not empty`, mpath and request variables). The **validator now rejects a `when` that does not parse**, with file and line (new golden cases, 185 in total).
- `quarkus-mq`: the extension serves `/v<version>/...` through the Vert.x router on a worker thread and uses the application's default Agroal datasource.
- `samples/shop-sql`: the sample project (customer, product, order, txtest XML; schema for HSQLDB and PostgreSQL). `examples/shop`: the Quarkus application around it.

## 3. Numbers (observed)

| | |
|---|---|
| Engine tests (HSQLDB; PostgreSQL in CI) | 5 scenarios, green on `ubuntu`, `macos`, `windows`; green on PostgreSQL 16 |
| HTTP checks, JVM application | 31 / 31 |
| HTTP checks, native binary | 31 / 31 |
| Native build of the shop example | 2:38.6 wall, 3.25 GB peak, binary 60.8 MB |
| Resident memory after the scenario | JVM 131 MB, native 65.6 MB |

## 4. Typed binding (the JSTL `sql:param` defect)

`$name` and `$[id].path` become JDBC parameters, never text in the SQL. The value is converted to the type the database reports for that parameter (`ParameterMetaData`), so the path value "7" is bound as an integer for `WHERE id = $id` and the string "abc" there is a **400 naming the parameter**, not a 500. Values from a JSON body keep their JSON type; form values arrive as strings and are converted the same way. `'%$name%'` becomes `CONCAT('%', ?, '%')`. A bound decimal arithmetic expression needs the other operand to give the parameter its type (`(SELECT price ...) * $qty` works; `$a * $b` alone is ambiguous in HSQLDB and PostgreSQL): note for the spec.

## 5. Evidence

Observed: `mq-engine/src/test/java/io/mq/engine/ShopSqlTest.java`, `examples/shop/http-test.mjs`, workflows `mq-engine.yml` and `shop-conformance.yml`. Read: R2's `parser/.../InvocableElement.java` (variable patterns, `eq/ne/...` translation, `$id/$pid/$uid`), `Sql.java` (`output`, `requires`, `limit`, `offset`, `onerror`), `docs/mpath.md`, `docs/resource-file.md`, the shop scenario `mcp/scenarios/shop`.

## 6. Differences from the plan and decisions that need a human

1. **Executors are plain Java over `DataSource`**, not bound to Quarkus classes. The brief said "bind directly to Quarkus APIs"; Agroal *is* the `DataSource` in production, and the plain form lets the same tests run without Quarkus. XRequest will use the JDK `HttpClient` unless you want the Quarkus REST client.
2. **Semantics I had to choose** (R2 does not document them; each is covered by a test): output of an `Sql` defaults to true for queries and false for updates; `requires` means "these request parameters must be present, else 400"; `[n]` in an mpath is a 0-based row index (R2's doc is inconsistent); an `item` request is any `Request` with an `item` attribute; a failed statement gives `{"errorId","message"}` with 409 for constraint violations (SQLSTATE 23) and 500 otherwise, details kept by `errorId` (`Engine.errorDetail`), 400 for bad input, 404/405 for routes.
3. **Not implemented yet** (validated but ignored): `onblank`, `ref` (SQL catalog), `Param exists`, `classname` on Sql, request `Header` elements beyond setting response headers, `datasource` names other than the default, `Upload`. A rejected order (`insufficient stock`) answers with the declared 201 and a `rejected` text, because only a script can change the status; R2's shop scenario used a script there.
4. Datasources come from standard `quarkus.datasource.*` (build-time kind). Spike 3's `backend.yaml` programmatic datasources are not wired in yet.

## 7. Surprises

1. The first full run was green on both runtimes: no native-only failure, no reflection configuration. The JDBC path needed nothing from MQ (the PostgreSQL extension covers the driver, as in Spike 3).
2. HSQLDB cannot infer the type of `? * ?`; any engine that binds values has this limit.
3. Stale backend JVMs from my own earlier measurement locked a jar and failed a local Windows build; not a product issue.

## 8. Recommended change to the R2 Next spec

State the semantics in section 6.2 as the contract, and say that the `when` language is parsed by the validator and evaluated by the engine from the same code.
