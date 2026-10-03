// THROWAWAY spike (spikes/shared-host): memory and latency of script compilation in each backend vs one shared scripting host.
//   node measure.mjs <label> <inprocess|remote> <N> <default|capped>
// env: APP_JAR (quarkus-run.jar of the matching build), HOST_CP (class path of the scripting host), JAVA (java binary)
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawn, execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.join(here, '..', '..');
const [label, mode, nArg, flagsKind] = process.argv.slice(2);
const N = Number(nArg);
const JAVA = process.env.JAVA || 'java';
const APP_JAR = process.env.APP_JAR;
const HOST_CP = process.env.HOST_CP;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const rss = (pid) => { try { return Number(execFileSync('ps', ['-o', 'rss=', '-p', String(pid)]).toString().trim()); } catch { return -1; } };
const base = 8100;
const work = fs.mkdtempSync(path.join(process.env.RUNNER_TEMP || os.tmpdir(), 'shared-host-'));
const capped = flagsKind === 'capped';
// in-process needs room for the Kotlin compiler; a backend without the compiler needs far less
const heap = mode === 'inprocess' ? '-Xmx400m' : '-Xmx160m';
const jvmFlags = capped ? ['-XX:+UseSerialGC', heap, '-XX:MaxMetaspaceSize=256m'] : [];
const procs = [];

let host = null;
if (mode === 'remote') {
  host = spawn(JAVA, ['-cp', HOST_CP, ...(capped ? ['-XX:+UseSerialGC', '-Xmx700m'] : []), 'io.mq.script.ScriptHost', '9500'], { stdio: 'inherit' });
  for (let i = 0; i < 100; i++) { try { await fetch('http://127.0.0.1:9500/stats'); break; } catch { await sleep(100); } }
}
const t0 = Date.now();
for (let i = 0; i < N; i++) {
  const proj = path.join(work, 'p' + i);
  fs.cpSync(path.join(root, 'samples', 'shop', 'scripts'), path.join(proj, 'scripts'), { recursive: true });
  fs.cpSync(path.join(root, 'samples', 'shop', 'lib'), path.join(proj, 'lib'), { recursive: true });
  const args = [...jvmFlags, `-Dquarkus.http.port=${base + i}`, `-Dquarkus.mq.dir=${path.join(root, 'samples', 'shop', 'mq')}`,
    `-Dquarkus.mq.scripts-dir=${path.join(proj, 'scripts')}`, `-Dquarkus.mq.lib-dir=${path.join(proj, 'lib')}`,
    mode === 'remote' ? '-Dquarkus.mq.script-host=http://127.0.0.1:9500' : '-Dquarkus.mq.scripts=interpreted', '-jar', APP_JAR];
  procs.push(spawn(JAVA, args, { stdio: ['ignore', fs.openSync(path.join(work, `b${i}.log`), 'w'), 'inherit'] }));
}
for (let i = 0; i < N; i++) {
  for (let k = 0; k < 300; k++) { try { await fetch(`http://127.0.0.1:${base + i}/v1.0/nothing`); break; } catch { await sleep(100); } }
}
const startupMs = Date.now() - t0;
await sleep(2000);
const idle = procs.map((p) => rss(p.pid));

const calls = ['/v1.0/token/hello', '/v1.0/legacy?who=R2', '/v1.0/format?name=ada', '/v1.0/pricing?price=4.5&tax=18', '/v1.0/pricing/3?price=4.5'];
const timed = async (port, p) => { const t = process.hrtime.bigint(); const r = await fetch(`http://127.0.0.1:${port}${p}`); await r.text(); return { status: r.status, ms: Number(process.hrtime.bigint() - t) / 1e6 }; };
const first0 = [], second0 = [];
let failures = 0;
await Promise.all(procs.map(async (_, i) => {
  for (const c of calls) {
    const r = await timed(base + i, c);
    if (r.status !== 200) { failures++; console.log('FAIL', i, c, r.status); }
    if (i === 0) first0.push(Math.round(r.ms));
  }
}));
for (const c of calls) second0.push(Math.round((await timed(base, c)).ms));
await sleep(2000);
const after = procs.map((p) => rss(p.pid));
const hostRss = host ? rss(host.pid) : 0;
const sum = (a) => a.filter((x) => x > 0).reduce((s, x) => s + x, 0);
const result = {
  label, mode, backends: N, flags: capped ? jvmFlags.join(' ') : 'default', startupMs,
  backendIdleMbEach: Math.round(sum(idle) / N / 1024), backendAfterScriptsMbEach: Math.round(sum(after) / N / 1024),
  backendAfterScriptsMbMax: Math.round(Math.max(...after) / 1024), hostMb: Math.round(hostRss / 1024),
  totalMb: Math.round((sum(after) + hostRss) / 1024), firstCallMsBackend0: first0, secondCallMsBackend0: second0, failures,
};
console.log(JSON.stringify(result));
fs.mkdirSync(path.join(here, 'results'), { recursive: true });
fs.writeFileSync(path.join(here, 'results', label + '.json'), JSON.stringify(result, null, 1));
for (const p of procs) p.kill();
if (host) host.kill();
await sleep(1500);
process.exit(failures ? 1 : 0);
