# MQ project specification (draft 1)

A web API in MQ is a folder with four kinds of artifact and nothing else (plugins are an optional compatibility feature, section 5):

| Artifact | Says | Where |
|---|---|---|
| **XML** | what the API is: routes, SQL, steps, parameters, conditions | `mq/*.xml` |
| **Scripts** | small logic between steps (Kotlin) | `scripts/*.kts`, shared code in `lib/*.kt` |
| **Libraries** | heavy or reusable logic, kept testable | `lib/*.kt` (Kotlin classes), `libs` in `mq.yaml` (jars) |
| **`mq.yaml`** | where and how it runs: datasources, settings, declared libraries and drivers | project root |

Rule: a new need becomes **a section in `mq.yaml`**, **a script or `lib/` class**, or **a capability object offered to scripts**, never a fifth kind of artifact.

Status marks: **[built]** exists and is tested; **[partial]** partly; **[planned]** specified here, not built. Evidence for the built parts is in `docs/reports/`.

## 1. Layout

```
my-api/
  mq.yaml                 the project file (one file; replaces backend.yaml)
  mq/                     resource XML, one file per resource, not recursive          [built]
  scripts/                Kotlin scripts, name.kts                                    [built]
  lib/                    ordinary Kotlin shared by the scripts                       [built]
  plugins/                optional: jars for <Execute classname>, compatibility only    [built]
  db/migrations/          V1__create_tables.sql, V2__...  run in order, once          [planned]
  db/seed/                optional sample data for development                        [planned]
```

## 2. `mq.yaml`

One file, read by the Dev server and the CLI with the same reader **[planned: today the values are system properties and a pom file]**. Secrets are named `${ENV_NAME}` and resolved at start; a missing variable is an error; secrets never enter the image.

```yaml
name: bookstore                 # the backend name (path prefix when several run together)
version: 1                      # of this file's format

datasources:                    # same shape as the former backend.yaml          [built in the spike, not wired in]
  main:
    kind: postgresql            # postgresql | hsqldb (more later)
    url: jdbc:postgresql://${DB_HOST}:5432/books
    user: books
    password: ${DB_PASSWORD}
  reports:                      # a step picks one with datasource="reports"      [planned]
    kind: postgresql
    url: jdbc:postgresql://${REPORT_HOST}:5432/books

properties:                     # {{name}} in an XRequest url                      [built as quarkus.mq.properties.*]
  payments: https://api.payments.example
  self: http://localhost:8080

scripts: scripts                # folders, defaults shown                          [built]
lib: lib
plugins: plugins                # optional, compatibility only                       [built]

libs:                           # libraries scripts may import                     [partial: a pom file]
  - org.apache.commons:commons-text:1.13.1
drivers: [postgresql]           # JDBC drivers, each a known recipe                [partial]

auth:                           # who may call what                                [planned]
paramTypes:                     # custom <Param type="..."> as scripts                [planned]
  isbn: { type: script, file: types/isbn }
on:                             # start and stop hooks as scripts                  [planned]
  start: [ hooks/warmup ]
capabilities:                   # services offered to scripts as objects            [planned]
  mail: { smtp: ${SMTP_URL} }
  default: none                 # none | the name of a provider below
  providers:
    jwt:       { type: jwt, issuer: https://id.example, jwks: https://id.example/.well-known/jwks.json }
    apikey:    { type: apikey, header: X-Api-Key, keys: ${API_KEYS} }
    custom:    { type: script, file: auth/custom }   # a script returns the principal or refuses

http:                           #                                                  [planned]
  cors: { origins: ["https://app.example"], methods: [GET, POST, PUT, DELETE], credentials: false }
  maxBodyBytes: 1048576
  requestTimeoutSeconds: 30
  xrequestTimeoutSeconds: 15    #                                                  [built]

profiles:                       # per-environment overrides, merged over the above [planned]
  dev:  { datasources: { main: { kind: hsqldb, url: "jdbc:hsqldb:mem:dev", user: SA, password: "" } } }
  prod: { http: { cors: { origins: ["https://app.example"] } } }
```

Unknown keys are errors (a typo must not pass silently). The reader reports file and line, like the XML validator.

## 3. XML

The resource XML is defined by `resource.xsd` plus the MQ rules in `docs/validator-gap-analysis.md` and `docs/decisions.md`. What is built: `Resource`, `Request` (method, `item`, `status`), `Param` (typed validation), `Header`, `Sql`, `Transaction`, `XRequest`, `Script` (Kotlin only), `Execute`, `Text`, `when` conditions, mpath. Added by this spec (all **[planned]**):

- **`auth="name"`** on `Resource` and `Request`: the provider from `mq.yaml` that must accept the call (R2 has the attribute; it does nothing today). A request attribute overrides the resource.
- **Response shaping.** A step may nest its rows under another step's rows: `<Sql id="lines" into="orders" on="order_id=id">` puts each order's lines in `orders[].lines` (one query per step, joined in memory, no N+1 SQL).
- **Error mapping.** `onerror="message"` exists on `Sql` (built). Add `<Error when="..." status="404" message="..."/>` inside a `Request` to turn a condition into an HTTP error without a script.
- `onblank`, `ref` (SQL catalog), `Param exists`, `Upload`: validated today, not run.

## 4. Scripts and lib **[built]**

A script sees `params` (text), `steps` (results by id), `response` (map to fill), `request` (`id`, `pid`, `uid`, `method`). `lib/` is ordinary Kotlin (no `params`): pass arguments. The Dev server compiles on change; the CLI compiles ahead of time. Libraries come from `libs`. Not supported in the native binary: full Kotlin reflection (`kotlin-reflect`).

## 5. Code: scripts, lib and libs (plugins are optional)

Decision (after the plugin question, see `docs/decisions.md`): **scripts are the one code mechanism of v1.** A plugin is a separate Maven project, a build and a jar to copy; a script is an edit and a save. Real resources do not use plugins (of 35 distinct R2 resources only a parser test fixture has an `Execute`). So:

| Need | How |
|---|---|
| Glue between steps, reshape rows, decide | a script in `scripts/`; `Sql` already passes every row on: `steps["orders"]` is a list of maps |
| Post-process a query or an API answer | `<Sql id="raw" output="false">` then `<Script id="orders" file="shape"/>` with output on: no processor API needed |
| Heavy or reusable logic, unit tests, IDE, debugger | ordinary Kotlin classes in `lib/`, or a Java/Kotlin library in `libs`; scripts stay thin |
| State that outlives a request (a client, a cache) | a Kotlin `object` in `lib/` (lost when the lib recompiles in the Dev server; kept in production) |
| Business logic that loops over queries or runs its own transaction | `db` in scripts **[planned]**: `db.query(sql, params)`, `db.update(...)`, `db.transaction { }`, with the same typed binding as `Sql` and the datasource of the step (or a named one) |
| A non-JSON response (a file, an image) | `response.raw(contentType, bytes)` **[planned]** |
| Things that are not request steps: authentication, custom `Param` types, start and stop hooks | scripts too, declared in `mq.yaml`: `auth: { type: script, file: auth/jwt }`, `paramTypes:`, `on: { start: ..., stop: ... }` **[planned]**, so they hot-reload like everything else |
| Platform services (mail, a queue, a cache) | a **capability** declared in `mq.yaml` and exposed to scripts as an object (`mail.send(...)`), built into MQ; neither scripts nor jars can inject Quarkus beans in native **[planned]** |

A script sees `params` (text), `steps` (results by id), `response` (map to fill), `request` (`id`, `pid`, `uid`, `method`). The Dev server compiles on change; the CLI compiles ahead of time. Libraries come from `libs`; shared code from `lib/`. Not supported in the native binary: full Kotlin reflection (`kotlin-reflect`). **[built]** (except where marked)

**Setting up an editor [planned]:** publish the script definition (`mq-script`) so an IDE understands `params`, `steps`, `response` and `request` in `.kts` files, and generate a small project stub that puts `lib/` and `libs` on the editor class path.

### Plugins (compatibility only, not part of v1)

MQ has a small plugin loader for `<Execute classname="...">` **[built, `io.mq.plugin.Plugin`, tested on the JVM and in native]**: a public class with a no-argument constructor in a jar in `plugins/`, result must be JSON-ready (else a 500 naming the step, class and path), jars hot-reload in the Dev server. It is not advertised and gets no new features. If real R2 plugins turn up, the path is an adapter for `com.metamug:mtg-api` (`RequestProcessable`, `ResultProcessable`, `ResponseProcessable`), not a new API; see the compatibility issue.

## 6. Database schema **[planned]**

`db/migrations/V<number>__<description>.sql`, applied in order once per datasource, recorded in a table `mq_migrations(version, checksum, applied_at)`. A changed checksum of an applied migration is an error. `db/seed/*.sql` is applied only in the `dev` profile. The Dev server runs migrations at start; the CLI packages them and the binary applies them at start (or an operator runs `mq migrate`). Refuse to serve if a migration fails.

## 7. Tests and documentation **[planned]**

- **`mq test`:** starts the project against a throwaway database, sends the requests the XML implies using each `Param`'s `testvalue`, and checks status and shape; plus the JVM-versus-native comparison of the CLI smoke test.
- **`mq docs`:** an OpenAPI 3 file generated from the XML (R2 generated docs from the same files).

## 8. The two products

- **Dev server (JVM):** reads the project folder, watches it, applies valid files and keeps the last good version of an invalid one with a clear error, compiles scripts through a shared scripting host, hot-deploys plugins. **[partial]**
- **CLI:** validates (refuses on any error), compiles scripts and `lib/`, bundles declared libs, plugins and migrations, builds the native binary in a Mandrel container, smoke-tests it against the JVM results. **[prototype pieces only]**

## 9. Not in this version

Scheduled jobs, caching, rate limiting, webhooks and events, multi-tenancy, Oracle and SQL Server, pagination helpers, observability. Each, when added, must fit the rule above: a `mq.yaml` section, a script or `lib/` class, or a capability.
