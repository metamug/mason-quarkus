# Test 6: real R2 resources through the validator

Source: every resource XML (namespace `http://xml.metamug.net/resource/1.0`) found in the R2, Mason and spike working folders: 80 files. They are copies and variants of R2's shop scenario, its parser test files and the benchmark apps; 35 are distinct by content. All distinct files are now golden cases in `golden/real/` (`valid/` 31, `invalid/` 4), run by `RealResourcesTest` on Linux, macOS and Windows.

## 1. Outcome

**Pass.** 31 distinct real resources are accepted, including the stricter MQ rules. The 4 rejected files are rejected for a reason that is stated and wanted:

| File | Why it is rejected |
|---|---|
| `persist.xml`, `result.xml` (R2 parser tests) | old dialect: an `XRequest persist` attribute, a `Query` element. R2's own `resource.xsd` rejects them too (checked with the XSD oracle). |
| `shop-legacy-groovy.xml`, `shopg-legacy-groovy.xml` | `file="legacy.groovy"` / `hello.groovy`: Groovy is dropped. The shop scenario's version was rewritten in Kotlin (`samples/shop/mq/legacy.xml`, accepted). |

## 2. The Transaction id rule

MQ rejects a step id used twice **inside a Transaction**, or a Transaction statement id that is already used by another step in the resource (the XSD checks neither). Check against reality: 12 of the 31 accepted real resources contain a `Transaction`; **none uses a duplicate id**, so the rule rejects no real resource and **stays**. (The instruction text for this item ended after "duplicate step ids inside a Transaction are"; I read it as "still rejected, provided no real resource breaks". If you meant something else, tell me.)

## 3. What real files taught the validator

1. `xsi:schemaLocation` on the root (found in the first parse-time run; fixed).
2. Groovy script references (now an explicit error with the file and line).
3. Nothing else: no real resource uses a numeric mpath index (`$[x][0]`), so the 0-based choice is untested by real data; the shop scripts index rows with Kotlin list access.

## 4. Evidence

`golden/real/`, `mq-core/src/test/java/io/mq/core/RealResourcesTest.java`, `docs/decisions.md`.
