# Validator gap analysis: Spike subset validator vs R2's XSD

Question (Next work, item 1): which rules or elements does the Spike subset validator (`spikes/shared-parser`) miss compared with R2's real parser and XSD?

## 1. Outcome

The subset validator agrees with the XSD on **114 of 177** golden cases. It **accepts 59 invalid files** and **rejects 4 valid files**. The Phase 2 validator must close all 63. Raw per-case list: `validator-gap-raw.txt`.

## 2. Method (observed)

- `golden/gen.mjs` generates 177 cases (69 valid, 97 invalid-xsd, 9 invalid-semantic, 2 invalid-mq) with a rule description each in `golden/manifest.tsv`.
- `tools/xsd-oracle/Oracle.java` validates them against R2's `resource.xsd` with the JDK schema validator. It classified all 177 as the manifest says (0 mismatches). For the `invalid-semantic` and `invalid-mq` kinds the XSD accepts the file, so the oracle verdict VALID is expected; those rules come from R2's Java code (read, not run): mpath ids, limit/offset on updates.
- The spike validator was run over the same files and compared with the manifest.

## 3. Rules the subset misses (accepts, should reject): 59

| Group | Rule | Golden cases |
|---|---|---|
| Namespace and document | namespace required and exact; children must be in it; no text between elements | `document-no-namespace`, `document-wrong-namespace`, `document-child-without-namespace`, `document-text-in-resource`, `document-text-in-request` |
| Unknown attributes / elements | any unknown attribute or child must be rejected (Resource, Tag, Request, Sql, Transaction, XRequest, Script, Param, Execute, Desc) | `resource-unknown-attribute`, `desc-tag-unknown-attribute`, `desc-unknown-child`, `request-unknown-attribute`, `sql-unknown-attribute-persist`, `transaction-unknown-attribute`, `xrequest-unknown-attribute-persist`, `script-unknown-attribute`, `param-unknown-attribute`, `execute-unknown-child` |
| Resource | `parent` not empty; `auth` starts with a letter and is not empty | `resource-parent-empty`, `resource-auth-digit-first`, `resource-auth-empty` |
| Desc | one Desc, before the Requests | `desc-after-request`, `desc-twice` |
| Request | PATCH is not a method (also for XRequest); unique method + item | `request-method-patch`, `xrequest-method-patch`, `request-method-item-duplicate`, `request-method-item-duplicate-named` |
| ids | 1 to 100 characters, not empty | `id-101-characters`, `id-empty` |
| Sql attributes | `verbose`/`output` boolean; `limit`/`offset`/`requires` name patterns and non-empty; `classname` non-empty; `status` 100-599 | `sql-verbose-not-boolean`, `sql-output-not-boolean`, `sql-limit-starts-with-digit`, `sql-offset-starts-with-digit`, `sql-requires-empty`, `sql-classname-empty`, `sql-status-out-of-range` |
| XRequest / Script | `output` true/false/headers (XRequest) or boolean (Script); `file` non-empty; Param needs name and value; Header needs name (non-empty) and value | `xrequest-output-maybe`, `xrequest-param-without-value`, `xrequest-header-without-name`, `xrequest-header-empty-name`, `header-value-missing`, `header-name-missing`, `script-file-empty`, `script-output-maybe` |
| Param | `type` required and one of the XSD types (`string` is not one); `name` required, non-empty; `max` numeric; `minlength` >= 0; `maxlength` integer; `exists` at least 3 characters; `required` boolean | `param-type-missing`, `param-type-string`, `param-name-missing`, `param-name-empty`, `param-max-not-a-number`, `param-minlength-negative`, `param-maxlength-decimal`, `param-exists-too-short`, `param-required-not-boolean` |
| Semantic (R2 Java, not the XSD) | `limit`/`offset` only on queries | `sql-limit-on-update`, `sql-offset-on-update` |
| Semantic mpath | id must exist, be declared earlier, not be the step itself, same Request only; checked in `when`, XRequest `url` and Transaction Sql | `mpath-unknown-id`, `mpath-forward-reference`, `mpath-self-reference`, `mpath-in-when-unknown`, `mpath-in-xrequest-url-unknown`, `mpath-in-transaction-unknown`, `mpath-other-request` |

Cause: the spike validator checks the elements and attributes it needs to build a route and ignores the rest. It has no attribute whitelist, no datatype checks, no namespace check and no cross-step checks.

## 4. Valid input the subset rejects: 3 (a 4th, duplicate ids inside a Transaction, is valid for the XSD but MQ rejects it on purpose: golden case `id-duplicate-inside-transaction`, kind invalid-mq)

| Case | Why it is valid | Spike behaviour |
|---|---|---|
| `resource-version-integer` | `v` may be an integer such as `2` | requires `\d+\.\d+` |
| `request-method-head` | HEAD is in the method list | rejected |
| `execute-with-args` | `Arg` children of Execute are allowed | rejected |


## 5. Surprises

1. The two kinds the XSD cannot express (mpath references, limit/offset on updates) account for 9 of the 59 misses; they only appear when the Java code is read, not from the XSD. R2's Java throws a `SAXException` for limit/offset on an update.
2. The `xsd:unique` on method+item is easy to miss: two Requests with the same method and no `item` collide.

## 6. Open questions

1. (Settled in Phase 2) MQ rejects duplicate ids inside a Transaction, and a Transaction Sql id that collides with another step: stricter than the XSD, so that an mpath names exactly one step.
2. The golden manifest carries the XSD-derived expectation; the first error message and line for each case still need to be stored as `.expected` files (Phase 2).

## 7. Recommended change to the R2 Next spec

State the validation rules as the XSD plus the two Java rules (mpath references, limit/offset on queries only) in one list, and make the golden cases the contract.
