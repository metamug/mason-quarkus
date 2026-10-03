# Quarkus features MQ can lean on: do they work together, on the JVM and in native?

THROWAWAY spike `spikes/quarkus-leverage/`, workflow `quarkus-leverage.yml`, runs `37120491675` to `37121251600` (the last is the one quoted). Quarkus 3.40.1, Temurin 25, Mandrel 25, PostgreSQL 16, `ubuntu-latest` 4 vCPU 15,989 MB RAM. One small application with routes added by code on the Vert.x router (as MQ does) and 16 HTTP checks, run against the JVM application and the native binary.

## 1. Outcome

**15 of 16 checks pass, identically on the JVM and in native; the one failure is a Flyway behaviour that MQ should not rely on.** The features MQ wants to lean on work together in one native binary: YAML configuration, a default and a named datasource, Flyway migrations (classpath and a plain folder), JWT security on code-added routes, CORS and body limits from configuration, Micrometer/Prometheus metrics, health, an OpenAPI document built from a model in code, the scheduler and the cache.

| Check | JVM | Native |
|---|---|---|
| Route added by code, open | pass | pass |
| `/secure/*` without a token: 401; valid JWT: 200 and the principal is known; wrong issuer: 401; expired: 401 | pass | pass |
| Default datasource migrated by Flyway from the classpath; named datasource `reports` chosen **by name at run time** and migrated from a **folder on disk** | pass | pass |
| Cache: second call does not compute again | pass | pass |
| Prometheus metrics with a custom labelled counter; scheduler counter increasing; health UP with the datasources | pass | pass |
| OpenAPI document built from a model in code | pass | pass |
| CORS preflight answered from configuration; body over 1 MB answered 413 | pass | pass |
| **Named Flyway locations do not leak into the default datasource** | **fail** | **fail** |

## 2. Numbers (observed)

| | JVM | Native |
|---|---|---|
| Start ("started in") | 2.4 s | 0.136 s |
| Resident memory after the checks | 210 MB | 85 MB |
| Features installed | agroal, cache, config-yaml, flyway, jdbc-postgresql, micrometer, narayana-jta, scheduler, security, smallrye-health, smallrye-jwt, smallrye-openapi, vertx | same |
| Native build | | 2:56 wall, 3.79 GB peak, binary 70.8 MB |

Compared with MQ today (shop example, native: 64.8 MB binary, 77 MB resident, 0.03 s start): all of the above costs about 6 MB of binary and 8 MB of memory more, and the start is 0.136 s because of the extensions' start-up work (Flyway, the pools, the scheduler).

## 3. Findings

1. **Flyway: a migration folder given for the named datasource also ran on the default one.** With the default datasource on `classpath:db/migration` and `reports` on `filesystem:<folder>`, the default database received both sets (version 101 from the reports folder appeared in `main`). Same result with YAML nesting, YAML lists and properties syntax, on the JVM and in native, so it is not a syntax slip. A migration applied to the wrong database is dangerous. MQ will not use the extension's Flyway configuration for several datasources; it runs the Flyway API itself, once per datasource, with that datasource's own folder (`db/migrations/<datasource>/`). Single-datasource projects could still use the extension.
2. **JWT works on routes that code adds to the router**: the `quarkus.http.auth.permission.*` rules cover them like any other. The principal is not in `rc.user()`; it comes from `QuarkusHttpUser.getSecurityIdentity(rc, null)`, which returns a `Uni` and must be awaited in a blocking handler.
3. **Annotations such as `@CacheResult` block while they load, so the route must run on a worker thread** (a blocking handler); on the event loop the request fails with "The current thread cannot be blocked". MQ handlers already are blocking.
4. **A client proxy hides fields** (the same trap as in the first spike): reading `squares.calls` through the CDI proxy returned 0. Use accessor methods.
5. **Configuration key names changed between Quarkus versions**: `quarkus.http.cors` must be `quarkus.http.cors.enabled` in 3.40; an unknown key is only a warning ("Unrecognized configuration key"). MQ should fail on unknown keys under its own `mq:` namespace, which Quarkus' `@ConfigMapping` validation does.
6. Quarkus fixes the database kind at build time. The spike used one kind (PostgreSQL) for both datasources; kinds that differ per project need either a build per project (the CLI does that) or programmatic datasources (Spike 3).

## 4. What this means for the design

- `application.yaml` with an `mq:` section replaces the custom `mq.yaml` reader: profiles (`%dev`, `%prod`), environment variables, secrets and Kubernetes ConfigMaps come with it.
- Security, CORS, limits, metrics, health, OpenAPI, scheduling and caching are configuration, not MQ code.
- Migrations and named datasources need MQ's own code (finding 1 and 6); everything else can be borrowed.

## 5. Not covered

OIDC against a real identity provider (only JWT verification with a static key), Dev Services (container start in dev mode), OpenTelemetry export, fault tolerance annotations, virtual threads, Quarkus CLI codestarts and the Kubernetes and container-image extensions. They are listed in the spec as candidates and need their own native check before MQ depends on them.
