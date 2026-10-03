# MQ project specification (draft 1)

A web API in MQ is a folder with four kinds of artifact and nothing else:

| Artifact | Says | Where |
|---|---|---|
| **XML** | what the API is: routes, SQL, steps, parameters, conditions | `mq/*.xml` |
| **Scripts** | small logic between steps (Kotlin) | `scripts/*.kts`, shared code in `lib/*.kt` |
| **Plugins** | heavy or reusable Java logic and extension points | `plugins/*.jar` |
| **`mq.yaml`** | where and how it runs: datasources, settings, declared libraries and drivers | project root |

Rule: a new need becomes **a section in `mq.yaml`** or **an extension point in the plugin API**, never a fifth kind of artifact.

Status marks: **[built]** exists and is tested; **[partial]** partly; **[planned]** specified here, not built. Evidence for the built parts is in `docs/reports/`.

## 1. Layout

```
my-api/
  mq.yaml                 the project file (one file; replaces backend.yaml)
  mq/                     resource XML, one file per resource, not recursive          [built]
  scripts/                Kotlin scripts, name.kts                                    [built]
  lib/                    ordinary Kotlin shared by the scripts                       [built]
  plugins/                plugin jars (Execute classes, processors, providers)        [built, own API; mtg-api planned]
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
plugins: plugins                # or a list of jars / Maven coordinates            [partial]

libs:                           # libraries scripts may import                     [partial: a pom file]
  - org.apache.commons:commons-text:1.13.1
drivers: [postgresql]           # JDBC drivers, each a known recipe                [partial]

auth:                           # who may call what                                [planned]
  default: none                 # none | the name of a provider below
  providers:
    jwt:       { type: jwt, issuer: https://id.example, jwks: https://id.example/.well-known/jwks.json }
    apikey:    { type: apikey, header: X-Api-Key, keys: ${API_KEYS} }
    custom:    { type: plugin, class: com.example.MyAuth }

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

## 5. Plugins

One plugin API: **`com.metamug:mtg-api`** (Apache 2.0, no dependencies, already used by R2 plugins) **[planned: today MQ has its own `io.mq.plugin.Plugin`]**. A plugin is a public class with a no-argument constructor in a jar in `plugins/`; it needs no registration file (the CLI scans declared jars at build time, the Dev server loads classes by name).

| Kind | Interface | Used for |
|---|---|---|
| Request processor | `RequestProcessable.process(Request, DataSource, args)` | `<Execute classname="...">`: any business logic |
| Result processor | `ResultProcessable.process(Result)` | `classname` on `Sql`: reshape a query result |
| Response processor | `ResponseProcessable.process(Response)` | `classname` on `XRequest`: reshape what another API returned |
| Database | `DatabaseProcessable.process(DataSource)` | maintenance tasks |
| Application listener | `ApplicationListener` | start and stop hooks |
| Upload listener | `UploadListener` | files received |
| **Auth provider** | MQ addition: `AuthProvider.authenticate(Request)` returns a principal or refuses | `auth: { type: plugin }` **[planned]** |
| **Param type** | MQ addition: `ParamType.validate(String)` | custom `<Param type="...">` **[planned]** |

Rules: a result must be JSON-ready (String, Number, Boolean, null, List, Map with String keys), else a 500 naming the step, the class and the path **[built]**; classes are never loaded by name in the native binary, only registered ones **[built]**; a changed jar is picked up by the Dev server without a restart **[built]**.

## 6. Database schema **[planned]**

`db/migrations/V<number>__<description>.sql`, applied in order once per datasource, recorded in a table `mq_migrations(version, checksum, applied_at)`. A changed checksum of an applied migration is an error. `db/seed/*.sql` is applied only in the `dev` profile. The Dev server runs migrations at start; the CLI packages them and the binary applies them at start (or an operator runs `mq migrate`). Refuse to serve if a migration fails.

## 7. Tests and documentation **[planned]**

- **`mq test`:** starts the project against a throwaway database, sends the requests the XML implies using each `Param`'s `testvalue`, and checks status and shape; plus the JVM-versus-native comparison of the CLI smoke test.
- **`mq docs`:** an OpenAPI 3 file generated from the XML (R2 generated docs from the same files).

## 8. The two products

- **Dev server (JVM):** reads the project folder, watches it, applies valid files and keeps the last good version of an invalid one with a clear error, compiles scripts through a shared scripting host, hot-deploys plugins. **[partial]**
- **CLI:** validates (refuses on any error), compiles scripts and `lib/`, bundles declared libs, plugins and migrations, builds the native binary in a Mandrel container, smoke-tests it against the JVM results. **[prototype pieces only]**

## 9. Not in this version

Scheduled jobs, caching, rate limiting, webhooks and events, multi-tenancy, Oracle and SQL Server, pagination helpers, observability. Each, when added, must fit rule 1: a `mq.yaml` section or a plugin extension point.
