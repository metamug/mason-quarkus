# Mason for Quarkus (MQ)

MQ is a Quarkus-native way to build web APIs from a small XML language plus Kotlin scripts. It started from [R2](https://github.com/metamug/R2) resource XML and is now free of R2 and old Mason compatibility (see the project specification). The XML is parsed once
(StAX), validated, held as an in-memory model, and executed by plain Java executors, replacing the XML to JSP to Tomcat
translation layer of the original Mason. MQ is built on Quarkus (Apache 2.0); it is not affiliated with or endorsed by the Quarkus project.

A web API in MQ is a folder with four kinds of artifact; code is Kotlin (scripts are the one extension mechanism):

| | |
|---|---|
| **XML** | what the API is: routes, SQL, steps, parameters, conditions (`mq/*.xml`) |
| **Scripts** | small logic between steps, in Kotlin (`scripts/*.kts`, shared code in `lib/*.kt`) |
| **Libraries** | heavy or reusable logic kept testable: Kotlin classes in `lib/`, jars declared in `libs` (plugins exist only as a compatibility feature) |
| **`application.yaml`** | where and how it runs, in Quarkus syntax with MQ settings under `mq:` (datasources, libraries, auth); Quarkus provides profiles, secrets, security, CORS, metrics and health |

Two products come out of it: a **Dev server** (JVM; reads the folder, hot-deploys, compiles scripts) and a **CLI** (validates, compiles
scripts ahead of time, builds a native binary). The project layout and every setting are in [`docs/spec/project-spec.md`](docs/spec/project-spec.md) (draft 2, Quarkus-native; the engine still speaks the older XML syntax until the redesign lands).

## Status

Working and tested (JVM and native, PostgreSQL and HSQLDB; Linux, macOS, Windows in CI):

- Parser and validator (`mq-core`): all of R2's `resource.xsd` plus MQ rules, every error with file and line, identical results on the JVM and in native (189 generated cases and 35 real R2 resources as golden cases).
- Executors (`mq-engine`): `Sql` with typed parameter binding, `Transaction`, `XRequest`, `Script` (Kotlin), `Execute` (compatibility plugin classes), `Text`, `when` conditions, mpath, request routing including item and parent resources.
- Quarkus extension (`quarkus-mq`): serves the resources over HTTP; dev-mode reload in 20 to 90 ms with an atomic swap and the last good version kept; the XML parser's native settings are built in.
- Kotlin scripts interpreted by the Dev server and compiled ahead of time for production and native; shared `lib/` code; libraries such as commons-text.
- The R2 shop scenario runs end to end: 40 HTTP checks give the same result on the Dev server loader, on compiled scripts (JVM) and in the native binary.

Not built yet: the redesigned XML syntax, configuration in `application.yaml` under `mq:`, authentication, database migrations, response shaping (nesting), the CLI and the Dev server as products, the shared scripting host in the Dev server (measured, experimental code exists), and a `db` object, raw responses and script-backed auth for scripts. See the open [issues](https://github.com/metamug/mason-quarkus/issues).

## Where to read

| | |
|---|---|
| [`docs/spec/project-spec.md`](docs/spec/project-spec.md) | the project folder, `mq.yaml`, plugin kinds: what MQ is meant to be |
| [`docs/MQ-findings-and-conclusions.md`](docs/MQ-findings-and-conclusions.md) | every experiment, number and decision in one document |
| [`docs/reports/`](docs/reports/README.md) | one report per experiment, with outcome, numbers, evidence, surprises, open questions |
| [`docs/decisions.md`](docs/decisions.md) | decisions taken by the owner |
| [`docs/validator-gap-analysis.md`](docs/validator-gap-analysis.md) | what the validator checks beyond the schema |

## Try it

Needs JDK 17 or newer and Maven 3.9. Native builds run in CI (Mandrel 25); nothing needs GraalVM locally.

```bash
mvn install                                     # builds every module and runs the tests (golden cases, engine, scripts)
cd mq-script && mvn test -Dtest=BookstoreTest   # a bookstore API written with only XML, a script and a datasource
```

The runnable examples are `examples/reload` (dev reload), `examples/shop` (SQL over HTTP) and `examples/shop-kt` (scripts, XRequest and plugins); the workflows in `.github/workflows/` show how each is built and driven, on the JVM and as a native binary.

## Layout of the repository

| | |
|---|---|
| `mq-core/` | parser, validator, model, `when`/mpath expressions, folder store and watcher (plain Java, no Quarkus) |
| `mq-engine/` | routing and the step executors (plain Java over JDBC) |
| `mq-plugin-api/` | the compatibility plugin interface (not part of v1) |
| `mq-script/` | Kotlin script definition, Dev server script loader, experimental shared scripting host |
| `quarkus-mq/` | the Quarkus extension (runtime and deployment) |
| `examples/`, `samples/` | runnable applications and sample projects (`shop`, `shop-sql`, `bookstore`, `shop-plugin`) |
| `golden/`, `tools/` | the golden validation cases (including real R2 resources), the XSD oracle, the script compile tool |
| `spikes/` | throwaway code that answered one risky question each, with measurements (not maintained) |

## Licence

Apache License 2.0, see `LICENSE`. R2 material is used with attribution, see `NOTICE`.
