// THROWAWAY spike code. Measures lazy start through Lazy.java.
//   node measure.mjs <label> "<backend command with {port} and {dir}>" [idleMs]
// Scenarios: A first request to a stopped backend (5x), B warm requests, C idle stop, D five backends asked at once.
import fs from 'node:fs';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const [label, command, idleArg] = process.argv.slice(2);
const idle = Number(idleArg || 1500);
const port = Number(process.env.LAZY_PORT || 9100);
const base = `http://127.0.0.1:${port}`;
const seed = process.env.SEED || path.join(here, '..', '..', 'examples', 'reload', 'seed', 'first.xml');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const N = 5;

const names = Array.from({ length: N }, (_, i) => `b${i + 1}`);
const work = fs.mkdtempSync(path.join(process.env.RUNNER_TEMP || (await import('node:os')).tmpdir(), 'lazy-'));
const args = [path.join(here, 'Lazy.java'), '--port', String(port), '--idle-ms', String(idle)];
for (const n of names) {
  const d = path.join(work, n);
  fs.mkdirSync(d, { recursive: true });
  fs.copyFileSync(seed, path.join(d, 'first.xml'));
  args.push('--backend', `${n}=${d}=${command}`);
}
const launcher = spawn('java', args, { cwd: work, stdio: ['ignore', 'inherit', 'inherit'] });
const events = async () => (await (await fetch(base + '/_lazy/events')).json());
for (let i = 0; i < 100; i++) {
  try { await events(); break; } catch { await sleep(100); }
}
const ms = (t0) => Number(process.hrtime.bigint() - t0) / 1e6;
const hit = async (n) => {
  const t0 = process.hrtime.bigint();
  const r = await fetch(`${base}/b/${n}/r/first`);
  const text = await r.text();
  if (r.status !== 200 || text !== 'first-v1') throw new Error(`${n}: ${r.status} ${text}`);
  return ms(t0);
};
const waitStopped = async (n) => {
  for (let i = 0; i < 600; i++) {
    const ev = await events();
    const starts = ev.filter((e) => e.backend === n && e.event === 'start').length;
    const stops = ev.filter((e) => e.backend === n && e.event === 'stop').length;
    if (starts === stops) return;
    await sleep(50);
  }
  throw new Error('backend ' + n + ' did not stop');
};
const med = (a) => [...a].sort((x, y) => x - y)[Math.floor(a.length / 2)];
const result = { label, command: command.replace(work, '<dir>'), idleMs: idle };

// A. first request to a stopped backend
{
  const t = [];
  for (let i = 0; i < 5; i++) {
    await waitStopped('b1');
    t.push(Math.round(await hit('b1')));
  }
  const ev = (await events()).filter((e) => e.backend === 'b1' && e.event === 'start');
  result.A_firstRequestMs = t;
  result.A_readyMs = ev.map((e) => Math.round(e.readyMs));
  result.A_rssKb = ev.map((e) => e.rssKb);
}
// B. warm requests through the launcher
{
  const t = [];
  for (let i = 0; i < 200; i++) t.push(await hit('b1'));
  result.B_warmMedianMs = Number(med(t).toFixed(2));
  result.B_warmMaxMs = Number(Math.max(...t).toFixed(2));
}
// C. idle stop: from the last request to the process being gone
{
  const tLast = process.hrtime.bigint();
  await hit('b1');
  const before = (await events()).filter((e) => e.event === 'stop').length;
  await waitStopped('b1');
  const wall = ms(tLast);
  const stop = (await events()).filter((e) => e.backend === 'b1' && e.event === 'stop').pop();
  result.C_lastRequestToStoppedMs = Math.round(wall);
  result.C_stopMs = Math.round(stop.stopMs);
  result.C_exitCode = stop.exit;
}
// D. five stopped backends asked at once
{
  for (const n of names) await waitStopped(n);
  const t0 = process.hrtime.bigint();
  const each = await Promise.all(names.map((n) => hit(n)));
  result.D_allFiveMs = Math.round(ms(t0));
  result.D_eachMs = each.map(Math.round);
  const ev = (await events()).filter((e) => e.event === 'start').slice(-N);
  result.D_rssKbTotal = ev.reduce((s, e) => s + e.rssKb, 0);
  for (const n of names) await waitStopped(n);
}
console.log(JSON.stringify(result, null, 1));
fs.mkdirSync(path.join(here, 'results'), { recursive: true });
fs.writeFileSync(path.join(here, 'results', label + '.json'), JSON.stringify(result, null, 1));
launcher.kill();
await sleep(200);
process.exit(0);
