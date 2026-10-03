# Is it enough? A bookstore API from scratch with only XML, a script and a datasource

The exercise behind `docs/spec/project-spec.md`: write a small web API the way a developer would, with only the artifacts the spec names, and write down what hurts. `samples/bookstore/` (3 resources, 1 script, a schema) and `mq-script/.../BookstoreTest.java` (3 tests, all pass on HSQLDB). Nothing was added to the engine for it.

## 1. Outcome

**The core is enough; five things are missing for a real API.** CRUD with validation, search, filtering, limit and offset from the request, a joined read, a parent resource (`/book/{pid}/review`), a script that averages the reviews, correct statuses (201, 202, 410, 400, 409) all worked with XML, one script and a datasource. 10 minutes of writing, 6 resource requests, no engine change.

## 2. What worked (observed)

- `POST /author`, `/book`, `/book/{pid}/review` with typed `Param` validation: isbn pattern, price minimum, rating range, a default value (`stock`).
- Search `LOWER(title) LIKE LOWER('%$q%')`, a filter, `limit="size" offset="from"` paging, and `when` choosing which of three queries runs.
- `GET /book/{id}`: the book with its author (a join), its reviews, and a script that turns the reviews into `{count, average}`.
- Unique and foreign-key violations are 409; a bad parameter is 400; `DELETE` answers the declared 410.

## 3. What hurt (each is a GitHub issue)

| # | Friction | Why it matters | Planned answer |
|---|---|---|---|
| 1 | **No way to get the id an insert generated.** Every create is two steps, and the second finds the new row with `SELECT ... WHERE id = (SELECT MAX(id) ...)`, which is wrong under concurrent requests. | Correctness, and the most common thing an API does | An update step exposes its generated keys: `$[ins].id` and in the output |
| 2 | **No nesting.** An author with their books, a book with its reviews come back as sibling flat lists the client must stitch together. | Every client wants nested JSON | `into`/`on` on a step (spec section 3) |
| 3 | **Column names differ by database.** HSQLDB reports `ID`, PostgreSQL `id`: the same API answers differently, and every test needs a case-insensitive lookup. | A real bug: an app developed on HSQLDB breaks on PostgreSQL | Normalise to the label as written in the SQL (lowercase for unquoted) |
| 4 | **No authentication or ownership**, so no login, no "my reviews". | Blocks any public API | `auth` providers (spec) |
| 5 | **The schema is created outside the project** (the test runs a SQL file by hand). | Nobody can ship the project | `db/migrations` (spec section 6) |
| 6 | **Partial updates need one `Sql` per optional field** with a `when` each. | Verbose, easy to get wrong | document `SET price = COALESCE($price, price)`; consider optional fragments |
| 7 | **Errors do not say which field or constraint.** A 409 is "the database refused the change". | Hard for a client to act on | map constraint names to messages in the project |
| 8 | **Query parameters of list requests are not declared**, so a misspelled `autor_id` is silently ignored. | Silent wrong results | optional strict mode: reject parameters not declared with `Param` |
| 9 | **No total count with paging.** | Clients need page counts | helper step or `count` output |

Not a problem: nothing in the exercise needed a plugin or a new kind of artifact.

## 4. Recommended change to the spec

Items 1 to 5 are v1 blockers (generated keys, nesting, column-name normalisation, auth, migrations). Items 6 to 9 are polish. The spec already lists 2, 4 and 5; this report adds 1, 3, 7, 8, 9.
