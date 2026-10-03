// Re-shapes the backlog after the decision "scripts are the one code mechanism of v1; plugins are compatibility only"
// (docs/decisions.md). Run once; keeps the history of the change reviewable.
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'issues2-'));
let n = 0;
const body = (text) => { const f = path.join(tmp, `${++n}.md`); fs.writeFileSync(f, text + '\n'); return f; };
const gh = (...args) => { try { console.log(execFileSync('gh', args).toString().trim()); } catch (e) { console.log('FAILED gh ' + args.slice(0, 3).join(' ') + ': ' + e.message.split('\n')[0]); } };

const note = 'Decision: scripts are the one code mechanism of v1; plugins are compatibility only (docs/decisions.md, docs/spec/project-spec.md section 5).';

// 7: the mtg-api migration becomes an optional compatibility adapter
gh('issue', 'edit', '7', '--title', 'Compatibility adapter for com.metamug:mtg-api plugins (only if real R2 plugins turn up)',
  '--body-file', body(note + '\n\nmtg-api 1.7 is on Maven Central, Apache 2.0, no dependencies. If real R2 plugins that implement `RequestProcessable`, `ResultProcessable` or `ResponseProcessable` turn up, support them with an adapter instead of a new API: build a mtg `Request` from the request context, turn the `Response` into status, headers and payload (JSON-ready check stays), scan declared jars at build time to register them for native.\n\nEvidence so far: of 35 distinct real resources only a parser test fixture uses `Execute`; no real application does. Not planned until an actual plugin is found.\n\nAbsorbs the earlier issue about R2 plugins that return custom objects.'),
  '--add-label', 'future', '--remove-label', 'v1');
// 8: processors are not needed
gh('issue', 'close', '8', '--reason', 'not planned', '--comment', 'Not needed: a `Sql` step passes all its rows to the next step (`steps["id"]` is a list of maps), so `<Sql id="raw" output="false">` followed by a `<Script>` that reshapes and outputs does what a result processor did. If that pattern repeats in real projects, add a small sugar (see the transform issue), not a class-loading API. Reopen if real R2 `classname` processors turn up (see the compatibility issue).');
// 45: duplicate
gh('issue', 'close', '45', '--reason', 'not planned', '--comment', 'Merged into the compatibility adapter issue (#7).');
// 21: plugin kinds become script-backed extension points
gh('issue', 'edit', '21', '--title', 'Script-backed extension points: auth provider, custom Param types, start and stop hooks',
  '--body-file', body(note + '\n\nThings that are not request steps are scripts too, declared in `mq.yaml`, so they hot-reload like everything else:\n\n- `auth: { providers: { x: { type: script, file: auth/x } } }`: the script gets the request and returns the principal or refuses (401/403);\n- `paramTypes: { isbn: { type: script, file: types/isbn } }` for `<Param type="isbn">`;\n- `on: { start: [hooks/warmup], stop: [...] }`.\n\nAcceptance: the bookstore login and a custom `isbn` type are scripts; changes apply without a restart in the Dev server and are compiled ahead of time by the CLI.'),
  '--add-label', 'v1,scripts', '--remove-label', 'plugins');
gh('issue', 'comment', '29', '--body', 'Low priority: plugins are compatibility only now (docs/decisions.md). Fix when touching the plugin loader.');

const create = (title, labels, text) => gh('issue', 'create', '--title', title, '--body-file', body(note + '\n\n' + text), '--label', labels);

create('Scripts get a db object: query, update and transaction with typed binding', 'enhancement,v1,scripts,engine',
  'The main thing a plugin could do that a script cannot is use the database: loop over queries, run its own transaction. Give scripts `db.query(sql, params)` (list of row maps), `db.update(sql, params)` (update count and generated keys), and `db.transaction { ... }`, with the same typed parameter binding as `Sql` (`SqlRunner`), the datasource of the step (or a named one), and the same error mapping (400/409/500 with `errorId`).\n\nMust work in the Dev loader, in compiled scripts and in native. Contract goes in the Java classes of `mq-engine` (`io.mq.script`), like `Params` and `Steps`.\n\nAcceptance: a script that inserts an order and its lines in one transaction and returns the new id; rolled back when the script throws; same results on the JVM and in native.');
create('Raw responses from scripts: files, images and other content types', 'enhancement,v1,scripts,engine',
  'Every response is JSON built from step results. A script needs to answer a file, an image or text with its own content type and status: `response.raw(contentType, bytes)` plus `response.status(...)` and headers. Streaming for large bodies later. Interacts with `Upload` and with `http.maxBodyBytes`.\n\nAcceptance: a script answers a PNG and a CSV with correct headers on the JVM and in native; HEAD returns no body.');
create('Editor support for scripts: published script definition and a project stub', 'enhancement,v1,scripts,spec',
  'Plugin authors get an IDE, a debugger and JUnit; script authors must get at least completion. Publish the script definition (`mq-script`, with the `META-INF/kotlin/script/templates` registration) so IntelliJ understands `params`, `steps`, `response`, `request` and `db` in `.kts` files, and have the CLI generate a small stub project (`mq init`) that puts `lib/` and the declared `libs` on the editor class path. Include how to unit-test `lib/` classes.\n\nAcceptance: opening a new project in IntelliJ gives completion and no red `params`.');
create('The lib object pattern for state that outlives a request: document and test', 'documentation,enhancement,v1,scripts',
  'Clients, caches and pools belong in a Kotlin `object` in `lib/`. In the Dev server the state is lost when the lib recompiles (new class loader); in production it lives for the process. Write the pattern into the spec with an example (a cached HTTP client, a lookup cache), test both behaviours, and decide whether a lib recompile should be able to keep selected state.');
create('Capabilities for scripts: mail, queue, cache as objects declared in mq.yaml', 'enhancement,future,spec,scripts',
  'Neither scripts nor plugin jars can inject Quarkus beans in the native binary. Platform services should be built into MQ and offered to scripts as objects declared in `mq.yaml` (`capabilities: { mail: { smtp: ${SMTP_URL} } }` then `mail.send(...)` in a script), each with its native settings handled by the CLI recipe. Start with the one real projects ask for first.');
create('Sugar for "reshape this step result with a script" (transform)', 'enhancement,future,spec',
  '`<Sql id="raw" output="false">` followed by `<Script id="orders" file="shape"/>` replaces what result processors did. If real projects repeat that pair often, a short form such as `<Sql id="orders" transform="shape">` (script receives the rows, its response becomes the step output) would save an element. Wait for real usage before adding it.');
