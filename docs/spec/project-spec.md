# MQ project specification (draft 2: a Quarkus-native product, free of R2 and Mason)

MQ is a new product. It keeps what worked in R2 (a small XML vocabulary of requests and steps, `$param` and `$[step].path`, typed parameters, `when`) and nothing else is binding: the XML schema, URL shapes, response shapes and file names are redesigned where that makes a better product. Where Quarkus already provides a capability, MQ uses it instead of building its own.

Status marks: **[built]** exists and is tested (in the current v1 syntax); **[verified]** works in native in `docs/reports/quarkus-leverage.md`; **[planned]** specified here, not built. This is a draft for review: the choices marked *(proposal)* are mine and need your decision.

## 1. Principles

1. **A project is a folder; the product around it is a Quarkus application.** The Dev server runs the folder in a JVM, the CLI turns it into a native binary. The same engine runs in both.
2. **Four kinds of artifact:** XML (what the API is), Kotlin scripts and `lib/` (logic), `application.yaml` (where and how it runs), SQL migrations (the schema). A new need is a configuration key, a script or `lib/` class, or a capability offered to scripts, never a fifth kind of artifact.
3. **Lean on Quarkus.** Configuration, security, CORS and limits, metrics, health, OpenAPI, scheduling, caching, TLS, container images and Kubernetes manifests are Quarkus features used as they are. MQ code is the XML language, its executors, the script host and the CLI that drives the Quarkus build.
4. **Fail early and say where.** Unknown configuration keys, invalid XML, a script that does not compile, a migration that fails: each stops the build or the start with the file and line.
5. **Same result on the JVM and in native**, enforced by a conformance suite.

## 2. Layout

```
my-api/
  application.yaml        one configuration file, Quarkus syntax, MQ settings under mq:   [verified]
  api/                    the XML, one file per resource                                    [built as mq/]
  scripts/                Kotlin scripts, name.kts                                          [built]
  lib/                    ordinary Kotlin shared by scripts                                 [built]
  db/migrations/          <datasource>/V1__create_tables.sql, V2__...                       [planned]
  db/seed/                sample data for the dev profile                                   [planned]
```

## 3. Configuration: `application.yaml` with an `mq:` section

Quarkus reads it, so profiles (`%dev`, `%prod`), environment variables, secrets and Kubernetes ConfigMaps and Secrets work without MQ code **[verified]**. The `mq:` section is a typed `@ConfigMapping`; an unknown key under `mq:` is an error naming the key *(proposal)*. (This replaces the custom `mq.yaml` and `backend.yaml`.)

```yaml
mq:
  api: api                      # folder with the XML                                       [built as quarkus.mq.dir]
  scripts: scripts
  lib: lib
  libs:                         # libraries scripts may import (Maven coordinates)          [partial]
    - org.apache.commons:commons-text:1.13.1
  properties:                   # {{name}} in an XRequest url                                [built]
    payments: https://api.payments.example
  datasources:                  # created by MQ; the driver set is fixed at build time      [spike 3; planned]
    main:
      kind: postgresql
      url: jdbc:postgresql://${DB_HOST}:5432/books
      user: books
      password: ${DB_PASSWORD}
    reports: { kind: postgresql, url: "jdbc:postgresql://${REPORT_HOST}:5432/books" }
  migrations: db/migrations     # <datasource>/V<n>__<name>.sql, applied by MQ with the Flyway API
  auth:                         # which provider protects what; providers are Quarkus security or scripts
    default: none
    providers:
      jwt: { type: jwt }        # verified by smallrye-jwt: mp.jwt.verify.* below              [verified]
      custom: { type: script, file: auth/custom }
  strictParams: true            # reject query parameters a request does not declare            [planned]
  xrequest: { timeoutSeconds: 15, allowHosts: [api.payments.example] }

quarkus:                        # everything below is plain Quarkus
  http:
    port: 8080
    limits: { max-body-size: 1M }                                                            # [verified]
    cors: { enabled: true, origins: "https://app.example" }                                  # [verified]
    auth: { permission: { secured: { paths: /orders/*, policy: authenticated } } }           # [verified]
  micrometer: { export: { prometheus: { enabled: true } } }                                  # [verified]
mp:
  jwt: { verify: { publickey: ${JWT_PUBLIC_KEY}, issuer: "https://id.example" } }            # [verified]
```

**Datasources (proposal).** MQ creates them from `mq.datasources` with Agroal (Spike 3: JVM and native, secrets from the environment, a failing datasource does not stop the process), so one Dev runtime can host any project whatever its databases. The CLI build includes only the drivers the project lists. Quarkus-managed named datasources would add health and metrics for free but fix the database kind at build time and do not suit a Dev runtime that hosts many projects; MQ registers the pool metrics and the health check for its own datasources.

**Migrations (proposal).** MQ runs the Flyway API once per datasource with that datasource's own folder. The Quarkus Flyway extension is not used for several datasources because its named-datasource locations also ran on the default datasource (finding 1 of the leverage report: migrations applied to the wrong database).

## 4. The XML (redesign, proposal; the engine today speaks the older v1 syntax)

R2's vocabulary stays: `Resource`, `Request`, `Param`, `Sql`, `Transaction`, `XRequest`, `Script`, `Text`, `when`, mpath. What changes:

```xml
<Resource xmlns="urn:mq:api:1" path="/books">
  <Desc>Books.</Desc>

  <Request method="GET" path="/">                        <!-- explicit paths: no implicit /v1.0/<name>/<id> -->
    <Param name="q"      in="query" type="text"/>        <!-- where a value comes from: query | path | body | header -->
    <Param name="author" in="query" type="integer"/>
    <Sql id="books" paging="size,from">SELECT ... WHERE title ILIKE '%$q%'</Sql>
    <Respond body="$[books]"/>                            <!-- the response is stated, not "every step as a key" -->
  </Request>

  <Request method="GET" path="/{id}">
    <Param name="id" in="path" type="integer"/>
    <Sql id="book">SELECT ... WHERE id = $id</Sql>
    <Sql id="reviews">SELECT ... WHERE book_id = $id</Sql>
    <Error when="empty $[book]" status="404" message="no such book"/>
    <Respond status="200">{ "book": $[book][0], "reviews": $[reviews] }</Respond>
  </Request>

  <Request method="POST" path="/" status="201" auth="admin">
    <Param name="title" in="body" type="text" required="true" maxlength="200"/>
    <Sql id="ins" type="update">INSERT INTO book (title) VALUES ($title)</Sql>   <!-- $[ins].id: the generated key -->
    <Respond status="201" body="$[ins]"/>
  </Request>
</Resource>
```

- **Explicit routes.** `path` on `Resource` and `Request` replace `item`, `parent` and the version attribute. A version is part of the path (`/v1/books`) or a configuration prefix.
- **Declared sources.** `Param in=` says where a value comes from, so the OpenAPI document and the validation are exact and a misspelled query parameter can be refused.
- **A stated response.** `<Respond>` replaces "every output step becomes a key". Without it, the last step's result is the response. A template with `$[...]` composes nested JSON; a script does the rest.
- **Errors without a script.** `<Error when status message/>`.
- **Generated keys.** An update step exposes `$[ins].id`, so creating needs no `MAX(id)` read-back.
- **Types.** `text`, `integer`, `decimal`, `boolean`, `date`, `datetime`, `time`, `uuid`, `email`, `url`, plus project types defined by a script.
- **Our own schema.** A published XSD (`urn:mq:api:1`) for editor completion, replacing R2's `resource.xsd`; the golden suite is rewritten against it. A migration tool turns old R2 files into the new syntax once.

## 5. Code: scripts, lib and libs **[built]**

Scripts are the one code mechanism (decision: `docs/decisions.md`). A script sees `params`, `steps` (every row of every earlier step), `response`, `request`; `lib/` holds ordinary Kotlin classes (tested, in an IDE); `libs` are Java/Kotlin libraries. The Dev server compiles on change; the CLI compiles ahead of time; scripts run in native with the boundaries in `docs/reports/kotlin-native.md`.

| Need | How |
|---|---|
| Reshape a result | `Sql` then a `Script` that reads `steps["id"]` |
| Heavy logic, tests, debugger | `lib/` classes or `libs` |
| State across requests | a Kotlin `object` in `lib/` |
| Database access from code | `db.query`, `db.update`, `db.transaction { }` with typed binding **[planned]** |
| A non-JSON answer | `response.raw(contentType, bytes)` **[planned]** |
| Auth provider, custom `Param` type, start/stop hook | scripts declared in `application.yaml` **[planned]** |
| Mail, queue, cache, scheduler | capabilities offered to scripts as objects, wrapping the Quarkus extensions **[planned]** |

A compatibility loader for `<Execute classname>` plugin jars exists **[built]** and gets no new features; an adapter for `com.metamug:mtg-api` only if real plugins appear.

## 6. What Quarkus provides, and the state of each

| Capability | Quarkus feature | MQ's part | Native check |
|---|---|---|---|
| Configuration, profiles, secrets, ConfigMaps | SmallRye Config + config-yaml | `mq:` `@ConfigMapping` | verified |
| Authentication | smallrye-jwt (verified), OIDC (not yet), HTTP permission rules | `auth` attribute to a provider; script providers | JWT verified |
| CORS, limits, TLS, HTTP/2, compression | `quarkus.http.*` | none | verified (CORS, limits) |
| Metrics, health | Micrometer + Prometheus, SmallRye Health | step and request timers; datasource pool metrics and health | verified |
| Tracing | OpenTelemetry | span per request and step | not yet |
| OpenAPI | SmallRye OpenAPI + model reader | build the model from the XML | verified (model in code) |
| Schema migrations | Flyway library | per-datasource runner (extension misapplies named locations) | library verified through the extension; own runner to build |
| Scheduling, caching, mail, queues | scheduler, cache, mailer, Redis, Kafka | capabilities for scripts | scheduler and cache verified |
| Resilience for XRequest | SmallRye Fault Tolerance (retry, timeout, circuit breaker) | attributes on `XRequest` | not yet |
| Local databases in dev | Dev Services (containers) | use for the Dev server | not yet |
| Dev experience | Dev UI, continuous testing, `quarkus` CLI codestarts | an MQ page in Dev UI; `mq init` as a codestart; `mq test` as generated tests | not yet |
| Build and ship | native build in a Mandrel container, container image, Kubernetes manifests (ConfigMap for the XML) | the CLI drives it | native build verified here |
| Concurrency | virtual threads (JDK 21 and later) for blocking JDBC | run steps on them | not yet |

## 7. The two products

- **Dev server (JVM).** Reads the project folder, watches it (debounced, atomic swap, last good version kept on an invalid file), compiles scripts through a shared scripting host (measured: -61 % memory for 20 backends), runs a project lazily and stops it when idle (10 to 15 minutes), starts it in the background when a project opens. Uses Dev Services for databases. **[partial]**
- **CLI (`mq`).** `mq init` (a codestart), `mq validate`, `mq test` (requests from each `Param`'s `testvalue`, JVM versus native comparison), `mq docs` (OpenAPI), `mq build` (validate, refuse on any error, compile scripts and `lib/`, bundle migrations, drive the Quarkus native build in a Mandrel container, smoke-test the binary against the JVM results), `mq migrate`. **[prototype pieces]**

## 8. Conformance and quality gates

The golden validation suite (rewritten for the new schema), the executor tests, and the shop and bookstore acceptance runs execute on the JVM and in native, on PostgreSQL and HSQLDB, on Linux, macOS and Windows. A feature is done when it passes there.

## 9. Not in this version

Multi-tenancy, Oracle and SQL Server (a driver recipe each), rate limiting (no Quarkus core feature), webhooks and events, a console and an MCP server. Each fits the principles when added.
