// Creates the project backlog as GitHub issues (gh must be logged in). Run once: node tools/issues/issues.mjs
// Kept in the repository so that the backlog and its reasons stay reviewable; edit the list, do not run twice.
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const labels = {
  'v1': ['b60205', 'needed for a first usable version'],
  'future': ['c5def5', 'not needed for a first version'],
  'spec': ['5319e7', 'defined in docs/spec/project-spec.md'],
  'dev-server': ['0e8a16', 'the Dev server product'],
  'cli': ['0e8a16', 'the CLI product'],
  'engine': ['1d76db', 'mq-engine executors and routing'],
  'validator': ['1d76db', 'mq-core parser and validator'],
  'plugins': ['fbca04', 'plugin API and loading'],
  'scripts': ['fbca04', 'Kotlin scripts'],
  'native': ['5319e7', 'native binary'],
  'security': ['d93f0b', 'security'],
};
for (const [name, [color, description]] of Object.entries(labels)) {
  try { execFileSync('gh', ['label', 'create', name, '--color', color, '--description', description]); } catch { /* exists */ }
}

const R = 'docs/reports/';
const issues = [
  // ---------------------------------------------------------------- features: version 1
  ['feature', 'enhancement', ['v1', 'spec', 'dev-server', 'cli'], 'Read mq.yaml: one project file for the Dev server and the CLI',
    'Decided: `mq.yaml` replaces `backend.yaml` (the datasource file of the hosting spike); its `datasources:` section moves in unchanged, a lone `backend.yaml` is read for one release with a warning.\n\nBuild one reader module (SnakeYAML, native-safe) used by both products: sections `name`, `datasources`, `properties`, `scripts`, `lib`, `plugins`, `libs`, `drivers`, `auth`, `http`, `profiles` (see docs/spec/project-spec.md section 2). `${ENV}` secrets resolved at start, a missing variable is an error, unknown keys are errors, every error with file and line.\n\nToday these values are system properties (`quarkus.mq.*`) and a pom file (`samples/shop/libs-pom.xml`).\n\nAcceptance: the shop and bookstore samples run from an `mq.yaml` alone; golden-style tests for good and bad files.'],
  ['feature', 'enhancement', ['v1', 'engine', 'dev-server'], 'Datasources from mq.yaml and named datasources per step',
    'Spike 3 showed Agroal programmatic datasources created at boot from a YAML file work on the JVM and in native (`' + R + '../spikes/spike3-hosting/REPORT.md`). Wire them into the extension instead of the single `quarkus.datasource.*`, and honour `datasource="name"` on `Sql`, `Transaction` and `Execute`.\n\nA backend whose datasource fails to start must not stop the process (record the error per backend).\n\nAcceptance: two datasources (PostgreSQL and HSQLDB) in one project, steps choosing between them, on the JVM and in native.'],
  ['feature', 'enhancement', ['v1', 'engine'], 'Expose the keys an update generated ($[ins].id)',
    'Found by the bookstore exercise (' + R + 'bookstore-exercise.md): after an `INSERT` the only way to read the new row is `WHERE id = (SELECT MAX(id) ...)`, which is wrong under concurrent requests.\n\nMake an update step return its generated keys (JDBC `getGeneratedKeys`) so `$[ins].id` works in later steps and in the output. Today an update returns `{updated: n}`.\n\nAcceptance: create-then-read works under 50 concurrent inserts without a MAX(id) subquery, on HSQLDB and PostgreSQL.'],
  ['feature', 'enhancement', ['v1', 'spec', 'engine'], 'Response shaping: nest one step result under another (into/on)',
    'Every response is flat `{stepId: rows}`; an author with books, a book with reviews, an order with lines must be stitched together by every client (' + R + 'bookstore-exercise.md, item 2).\n\nSpec (docs/spec/project-spec.md section 3): `<Sql id="lines" into="orders" on="order_id=id">` puts each order lines under `orders[].lines`, joined in memory, one query per step (no N+1).\n\nAcceptance: bookstore author/books and book/reviews responses nested; golden cases for invalid `into`/`on`; works in native.'],
  ['feature', 'enhancement', ['v1', 'spec', 'engine', 'plugins'], 'Authentication and authorization: auth providers and the auth attribute',
    'R2 has `auth` on `Resource`; it does nothing in MQ. Spec: providers declared in `mq.yaml` (`jwt`, `apikey`, `plugin`), `auth="name"` on `Resource` or `Request` (request overrides resource), `default` provider, 401 and 403 with clear messages, the principal available to scripts and plugins (`request.uid`).\n\nPlugin provider: `AuthProvider.authenticate(Request)`.\n\nAcceptance: bookstore gets a login and "my reviews"; JWT verification against a JWKS works in native.'],
  ['feature', 'enhancement', ['v1', 'spec', 'dev-server', 'cli'], 'Database migrations and seed data (db/migrations, db/seed)',
    'The schema of every sample is loaded by hand in tests and CI (' + R + 'bookstore-exercise.md, item 5). Spec section 6: `db/migrations/V<n>__<name>.sql` applied in order once per datasource, recorded in `mq_migrations(version, checksum, applied_at)`, a changed checksum of an applied migration is an error, refuse to serve if a migration fails. `db/seed` only in the `dev` profile.\n\nThe Dev server migrates at start; the CLI packages the files; the binary applies them at start or `mq migrate`.\n\nAcceptance: a project with only XML, `mq.yaml` and migrations starts against an empty database on PostgreSQL and HSQLDB, JVM and native.'],
  ['feature', 'enhancement', ['v1', 'plugins'], 'Replace the MQ plugin API with com.metamug:mtg-api',
    'mtg-api 1.7 is on Maven Central, Apache 2.0, no dependencies, and existing R2 plugins use it. Replace `mq-plugin-api` (`io.mq.plugin.Plugin`) with `RequestProcessable` (Execute), `ResultProcessable` (`classname` on Sql), `ResponseProcessable` (`classname` on XRequest) and `DatabaseProcessable`, plus the listener interfaces.\n\nAdapter builds a mtg `Request` from the request context and turns the `Response` into status, headers and payload; the JSON-ready check stays (decided). Discovery: Dev server loads classes by name; the CLI scans declared jars at build time and generates the registration for native (authors write no service file).\n\nAcceptance: a plugin written the R2 way runs on the Dev server and in native; the existing ExecuteTest and the shop plugin test pass.'],
  ['feature', 'enhancement', ['v1', 'plugins', 'engine'], 'classname on Sql and XRequest: result and response processors',
    'R2 documents `classname` on `Sql` (result processor) and `XRequest` (response processor). MQ validates the attribute and ignores it. Implement with the mtg-api interfaces (see the mtg-api issue); the output is JSON-ready checked like Execute results.'],
  ['feature', 'enhancement', ['v1', 'cli', 'native'], 'The CLI: validate, compile scripts and lib, bundle, build the native binary, smoke-test it',
    'Prototype pieces exist: `tools/mq-compile-scripts` (scripts and lib, class path as the declaration), the Mandrel container build and the value-checking selftest of `spikes/kotlin-native`, the validator. Build the product:\n\n1. validate everything, **refuse on any error** (decided);\n2. compile `scripts/` and `lib/` against the declared libs;\n3. scan declared plugin jars and generate the registrations;\n4. apply the driver recipes (`postgresql` = the Quarkus extension, `hsqldb` = driver plus resource includes);\n5. build the native binary in a Mandrel builder container (Linux binary; other systems need the container);\n6. smoke-test the binary and compare values with the JVM result (silent differences exist: locales, reflection; see ' + R + 'kotlin-native.md).\n\nThe Kotlin compiler version must follow the Quarkus BOM version (2.4.10 now). Distribution of the compiler (about 80 MB) with the CLI is open.\n\nAcceptance: `mq build` on samples/shop gives a binary that passes the 40 HTTP checks.'],
  ['feature', 'enhancement', ['v1', 'dev-server'], 'The Dev server: launcher and proxy, lazy start, idle stop',
    'Decisions: one process per backend, started on the first request and in the background when its project opens, stopped after 10 to 15 minutes idle; limit parallel JVM starts; reserve ports (bind-and-release raced under five parallel starts). Spike numbers in ' + R + 'lazy-start.md (native 40 ms, JVM 1 s first request).\n\nThe Dev server reads the project folder, watches it (JDK watcher; file-system events on macOS, never in the native binary), applies valid files and keeps the last good version of an invalid one with a clear error.\n\nAcceptance: 20 projects, a few active at once, on a 16 GB machine.'],
  ['feature', 'enhancement', ['v1', 'dev-server', 'scripts'], 'Shared scripting host in the Dev server (managed child process)',
    'Measured (' + R + 'shared-host.md): the Kotlin compiler in every backend costs 510 to 660 MB each (10.2 GB for 20); one host with backends that load compiled classes gives 148 to 170 MB each plus 470 to 930 MB for the host (4.0 GB for 20, -61 %); break-even about 2 backends; warm calls 3 to 5 times faster.\n\nProductise `ScriptHost` and `RemoteScriptLoader` (experimental today): start on demand with the first backend that has scripts, heap cap (700 MB worked), stop when idle, compile all scripts of a project in the background when it opens (hides the 5 s first call), hand over the project class path from `mq.yaml`. See also the security issue about the host.'],
  ['feature', 'enhancement', ['v1', 'engine'], 'Map errors to HTTP statuses and messages without a script',
    'A constraint violation answers "the database refused the change" (' + R + 'bookstore-exercise.md, item 7). Add `<Error when="..." status="404" message="..."/>` inside a `Request`, and project-level messages for named constraints; allow a script to set the status (today an order rejected by a script still answers the declared 201).\n\nAcceptance: the bookstore answers "isbn already exists" for the unique violation and 404 for a missing book without a script.'],
  ['feature', 'enhancement', ['v1', 'engine'], 'Strict parameters: reject query parameters a request does not declare',
    'A misspelled `autor_id` is silently ignored (' + R + 'bookstore-exercise.md, item 8). Optional `mq.yaml` setting that rejects parameters without a `Param` declaration with a 400 naming it; system parameters (`id`, `pid`) excepted.'],
  ['feature', 'enhancement', ['v1', 'spec'], 'HTTP settings in mq.yaml: CORS, body size, timeouts, profiles',
    'Spec section 2: `http.cors`, `http.maxBodyBytes`, `http.requestTimeoutSeconds`, `xrequestTimeoutSeconds` (built as `quarkus.mq.xrequest-timeout-seconds`), and `profiles` (dev, prod) merged over the base file. Depends on the mq.yaml reader.'],
  ['feature', 'enhancement', ['v1', 'cli', 'spec'], 'mq test: run the API with the testvalue of each Param',
    '`Param` already has `testvalue`. The CLI starts the project against a throwaway database, sends the requests the XML implies, and checks status and shape. Also the home of the JVM-versus-native comparison. Spec section 7.'],
  ['feature', 'enhancement', ['spec', 'cli'], 'mq docs: OpenAPI 3 from the XML',
    'R2 generated API documentation from the same files (`parser/.../apidocs`). Generate an OpenAPI 3 file from the model: routes, methods, `Param` types and limits, statuses, `Desc` texts.'],
  ['feature', 'enhancement', ['engine', 'validator'], 'Run what is validated but ignored: onblank, ref (SQL catalog), Param exists, Sql classname',
    'These attributes validate but have no effect (docs/spec section 3). Find the R2 behaviour in the Mason runtime, specify it, implement it, add golden and executor tests. `Upload` is a separate issue.'],
  ['feature', 'enhancement', ['engine'], 'File upload (Upload)',
    'R2 has an `Upload` element and an `UploadListener` in mtg-api. Specify and implement multipart handling, size limits (`http.maxBodyBytes`), storage location and the listener hook; native-safe.'],
  ['feature', 'enhancement', ['cli', 'native'], 'JDBC driver recipes and Maven resolution for libs and plugins',
    'Each known driver name is a recipe applied by the CLI: `postgresql` (tested), `hsqldb` (observed in Spike 3: needs `quarkus.native.resources.includes=org/hsqldb/resources/*`), later MySQL. An unknown driver is an error naming the missing recipe. Resolve `libs` and `plugins` Maven coordinates (not tested: the tests use paths and a pom file).'],
  ['feature', 'enhancement', ['engine'], 'Paging helpers: total count and page metadata',
    'limit/offset from the request work, but a client cannot know the number of pages (' + R + 'bookstore-exercise.md, item 9). Option: `count="true"` on a query step adds a total, or a helper step. (Pagination was out of scope in the brief; this records the need.)'],
  ['feature', 'enhancement', ['plugins', 'spec'], 'Plugin kinds beyond Execute: auth provider and custom Param types, starter project',
    'Spec section 5: `AuthProvider` and `ParamType` as MQ additions to the mtg-api interfaces. Plus a Maven archetype / starter project so a new plugin builds, loads and hot-reloads in minutes, and clear errors that name the step, class and file.'],
  // ---------------------------------------------------------------- bugs and known problems
  ['bug', 'bug', ['engine'], 'Column names differ by database: HSQLDB answers ID, PostgreSQL answers id',
    'The same API returns different JSON keys on different databases because the engine uses `getColumnLabel` as reported (' + R + 'bookstore-exercise.md, item 3). An application developed on HSQLDB breaks on PostgreSQL; every test needs a case-insensitive lookup.\n\nFix: normalise to the label as written in the SQL (lowercase for unquoted identifiers) and test the same responses on both databases. mpath and scripts already match keys case-insensitively; keep that.'],
  ['bug', 'bug', ['engine'], 'A step with datasource="name" silently uses the default datasource',
    'The extension maps every datasource name to the default one (`name -> dataSource...` in MqHttp), so a step that asks for another database runs on the wrong one without any message. Until named datasources exist (see that issue) the engine must fail with a clear error naming the step.'],
  ['bug', 'bug', ['engine', 'security'], 'XRequest body and url substitution is not JSON-escaped or url-encoded',
    'Variables are pasted into the body text as they are (as R2 did). A parameter value containing a quote or a brace breaks, or alters, the JSON sent to the other API. Values pasted into the url path are not encoded either. Decide the rule: escape according to the Content-Type of the body (JSON), encode in the url; keep a raw form for authors who need it. Add tests with hostile values.'],
  ['bug', 'bug', ['engine', 'security'], 'XRequest can be pointed at internal addresses (SSRF)',
    'The url may contain request parameters and mpath values, so a caller can steer the server to internal hosts. Add an allow-list of hosts in `mq.yaml` (`properties` already name the targets) and refuse other hosts by default in production; keep a development switch.'],
  ['bug', 'bug', ['security', 'scripts'], 'The experimental ScriptHost accepts any file path from any local process',
    '`ScriptHost` listens on 127.0.0.1 and compiles whatever directory a request names, with the class path the request names. Any local process (or a browser, through a cross-site request to localhost) can make it read and compile arbitrary files. Before it is productised: random per-run token shared with the backends, project roots registered by the Dev server instead of paths in requests, no CORS.'],
  ['bug', 'bug', ['scripts', 'dev-server'], 'A lib that does not compile stops every script instead of keeping the last good classes',
    'The Dev loader throws for every script until the lib compiles again, while the XML reload keeps the last good version of an invalid file (decided behaviour). Align them: keep serving the last good compiled lib and scripts, and report the error with file and line (' + R + 'script-modules.md, rule 4).'],
  ['bug', 'bug', ['dev-server'], 'FolderWatcher stops working if the watched folder is deleted and created again',
    'The JDK watch key becomes invalid when the folder is removed (for example by a git checkout that replaces it) and nothing re-registers. Detect the invalid key and watch the parent until the folder returns; add a reload test.'],
  ['bug', 'bug', ['plugins', 'dev-server'], 'PluginDirectory leaves temporary jar copies behind',
    'Jars are loaded from temporary copies so Windows cannot lock the originals. The previous copy is deleted on the next reload, but the last copy is never removed when the Dev server stops (and the class loader stays open). Delete on shutdown and name the temp folder by backend.'],
  ['bug', 'bug', ['engine'], 'Error details are kept in memory only and the errorId restarts at 1',
    '`Engine.errorDetail(errorId)` keeps the last 500 failures; after a restart the same id means a different failure, and a support ticket quoting an id cannot be resolved. Use ids that do not repeat (time-based) and log the detail (the 5xx path already logs); decide how long to keep them.'],
  ['bug', 'bug', ['engine', 'validator'], 'item attribute: any value, even item="false", makes an item request',
    'R2 treats any non-null `item` as an item request and so does MQ; the golden case name even says item="false" is still an item. Either document it loudly or make the validator accept only `true`. Decide and add golden cases.'],
  ['bug', 'bug', ['engine'], 'HEAD is answered like GET, including the body',
    '`Routes` maps HEAD to a GET request and the response writes the JSON body. A HEAD response must not carry a body. Add a test for status, headers and an empty body.'],
  ['bug', 'bug', ['engine'], 'A bound parameter expression like $a * $b has no type and fails on HSQLDB and PostgreSQL',
    'Two bound values multiplied have no inferable parameter type ("data type cast needed"). The shop order uses `(SELECT price ...) * $qty` to avoid it. Options: detect and add a CAST from the declared `Param` types, or give a clear error that names the expression. Document the workaround until then.'],
  ['bug', 'bug', ['engine', 'validator'], 'mpath row index base is documented inconsistently',
    'R2 documentation uses `[1]` and `[0]` for the first row in different places. MQ uses 0-based (decided; no real resource uses a numeric index). Write it into the spec and add a validator warning for `[n]` on a query result where the intent may be 1-based.'],
  ['bug', 'bug', ['cli', 'scripts'], 'Kotlin version is coupled to the Quarkus BOM',
    'Mixing Kotlin 2.2.20 compiler classes with the 2.4.10 libraries the Quarkus 3.40.1 BOM manages broke the Dev loader inside Quarkus with a `NoSuchMethodError` (' + R + 'script-mpath-sql.md). Pin the scripting module and the CLI compiler download to the BOM Kotlin version, fail the build when they differ, and re-check on every Quarkus upgrade.'],
  ['bug', 'bug', ['validator', 'scripts'], 'The validator does not warn about kotlin-reflect in scripts',
    'Full Kotlin reflection (`KClass.members`, `memberProperties`) cannot work in the native binary and nothing fixes it (' + R + 'kotlin-native.md). Warn when a script imports `kotlin.reflect.full`.'],
  // ---------------------------------------------------------------- future development
  ['future', 'enhancement', ['future'], 'Scheduled jobs', 'A plugin or script run on a schedule (cron expression in mq.yaml), with the same datasources. Must fit the rule: a section in mq.yaml or a plugin extension point.'],
  ['future', 'enhancement', ['future'], 'Response caching', 'Cache GET results by key with a time to live, declared per request; invalidate on updates of named tables or by a plugin.'],
  ['future', 'enhancement', ['future'], 'Rate limiting and quotas', 'Per client or per key limits declared in mq.yaml, with 429 and Retry-After.'],
  ['future', 'enhancement', ['future'], 'Webhooks and events', 'Publish an event after a step (to a URL or a queue) and receive events; plugin listener points exist in mtg-api (`ApplicationListener`).'],
  ['future', 'enhancement', ['future'], 'Multi-tenancy', 'One project served for many tenants with a datasource or schema per tenant, chosen from the auth principal or a header.'],
  ['future', 'enhancement', ['future'], 'More databases: MySQL, then Oracle and SQL Server', 'Driver recipes and native settings per database. Oracle and SQL Server were out of scope; this records them.'],
  ['future', 'enhancement', ['future'], 'Observability: metrics, tracing, request logs', 'Out of scope for the first version. When added: Micrometer metrics and OpenTelemetry through the Quarkus extensions, an access log, `errorId` correlation.'],
  ['future', 'enhancement', ['future', 'dev-server'], 'MCP server and console on top of the Dev server', 'The earlier R2 work (19 MCP tools) sat on the old runtime. Rebuild on the Dev server API once it exists; out of scope until then.'],
  ['future', 'enhancement', ['future'], 'Adapter for old R2 plugins that return custom objects', 'Not now (decided): of 35 real resources only a parser test fixture uses Execute. Revisit if real plugins appear that return POJOs; the JSON-ready rule rejects them today.'],
  ['future', 'enhancement', ['future', 'native'], 'Native binaries for macOS and Windows', 'The Mandrel container builds a Linux binary. Native builds on the other systems need runners with Mandrel installed; decide whether the CLI supports them or only produces Linux images.'],
  ['future', 'enhancement', ['future', 'dev-server'], 'Repeat the reload and hosting measurements on Quarkus 4', 'The brief asks to repeat Spike 1 (and the version-coupled Kotlin findings) when Quarkus 4.0 is stable.'],
];

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'issues-'));
let n = 0;
for (const [kind, label, extra, title, body] of issues) {
  const file = path.join(tmp, `${++n}.md`);
  fs.writeFileSync(file, body + '\n\n_Created from the planning work; see docs/MQ-findings-and-conclusions.md._\n');
  const args = ['issue', 'create', '--title', title, '--body-file', file, '--label', [label, ...extra].filter((v, i, a) => a.indexOf(v) === i).join(',')];
  try {
    const out = execFileSync('gh', args).toString().trim();
    console.log(`${n} ${out}`);
  } catch (e) {
    console.log(`${n} FAILED ${title}: ${e.message.split('\n')[0]}`);
  }
}
