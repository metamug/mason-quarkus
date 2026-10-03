# Parse time of the real validator next to boot time (Next work item 6)

Quarkus 3.40.1, `examples/reload` app with `mq-core` (the real StAX validator), `ubuntu-latest` 4 vCPU 15,989 MB RAM, Temurin 25.0.4 (JVM) and Mandrel 25 (native), 7 starts each (run `37094258840`). Input: the 37 shared golden files (20 valid, 17 invalid); all 37 are parsed and validated at start, the 17 invalid ones produce 17 reported problems.

## 1. Outcome

**Parse time is small but no longer negligible in native: about 9 ms of a 27-35 ms start.** On the JVM it is 78 ms of 0.86-1.0 s. Both are still small in absolute terms; the decision to keep the code and not bake stands (recommendation: do not bake). It would only change if a project has far more than 37 files.

## 2. Numbers (observed; median of 7 starts, range in brackets)

| | Native | JVM |
|---|---|---|
| Parse + validate 37 files (`ResourceStore.reload`) | **9.0 ms** (7.1-11.8) | **78 ms** (74-98) |
| Quarkus "started in" | 27 ms (24-35) | 861 ms (855-926) |
| Process start to first answer | 35 ms | 1,000 ms |
| Parse as a share of "started in" | about 33 % | about 9 % |
| Resident memory after start | 60.8 MB | 124 MB |

Per file: about 0.24 ms native, 2.1 ms JVM (a cold JVM, JIT not warm). For comparison, Spike 2 measured 4-8 ms (native) with the subset validator on the same files; the real validator with its full rule set and line tracking costs roughly 1.5 times that.

## 3. Evidence

Observed: `spikes/lazy-start/parse-time.mjs` (driver), workflow `.github/workflows/lazy-start.yml`, raw rows in `spikes/lazy-start/results/parse-linux-{native,jvm}.json`. `parseMicros` is the first `ResourceStore.reload` as reported by the app's `/status`.

## 4. Surprises

1. **The real validator found a bug in my golden set.** The first run loaded only 13 of the 20 valid files: the shop resources carry `xsi:schemaLocation`, which the XSD always allows and my validator rejected. Fixed, with three golden cases added (valid: `resource-xsi-schema-location`, `resource-xsi-no-namespace-schema-location`, `request-xsi-schema-location`; invalid: `resource-xsi-nil`). The golden suite, built from the XSD, had never used a real resource that way.
2. The first start of a series is the slowest (11.8 ms native, 98 ms JVM): file cache and class loading.

## 5. Open questions for a human

1. Is 9 ms of a 27 ms native start worth removing? Removing it needs baking, which gives up the thin-image route (XML added after the image is built) and breaks dev reload (Spike 3, variant C). I recommend no.

## 6. Recommended change to the R2 Next spec

None to the design. Note that the validator must accept `xsi:schemaLocation` / `xsi:noNamespaceSchemaLocation` on any element.
