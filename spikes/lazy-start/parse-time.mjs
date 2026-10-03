// THROWAWAY spike code. Boot time next to the parse time of the real validator on the 37 shared golden files (20 valid, 17 invalid).
//   node parse-time.mjs <label> "<command with {port}>" [runs]    (the folder is passed as QUARKUS_MQ_DIR)
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawn, execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const [label, command, runsArg] = process.argv.slice(2);
const runs = Number(runsArg || 7);
const shared = path.join(here, '..', 'shared-resources');
const dir = fs.mkdtempSync(path.join(process.env.RUNNER_TEMP || os.tmpdir(), 'parse-'));
for (const sub of ['valid', 'invalid']) for (const f of fs.readdirSync(path.join(shared, sub))) fs.copyFileSync(path.join(shared, sub, f), path.join(dir, f));
const files = fs.readdirSync(dir).filter((f) => f.endsWith('.xml')).length;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const rows = [];
for (let i = 0; i < runs; i++) {
  const port = 21000 + i;
  const log = path.join(dir, `run${i}.log`);
  const out = fs.openSync(log, 'w');
  const t0 = process.hrtime.bigint();
  const p = spawn(command.replace('{port}', port).split(/ +/)[0], command.replace('{port}', port).split(/ +/).slice(1), {
    stdio: ['ignore', out, out],
    env: { ...process.env, QUARKUS_MQ_DIR: dir },
  });
  let status = null;
  while (!status) {
    try {
      const r = await fetch(`http://127.0.0.1:${port}/status`);
      if (r.status === 200) status = await r.json();
    } catch { /* not up yet */ }
    if (!status) await sleep(1);
  }
  const toFirstResponseMs = Number(process.hrtime.bigint() - t0) / 1e6;
  let rss = -1;
  try { rss = Number(execFileSync('ps', ['-o', 'rss=', '-p', String(p.pid)]).toString().trim()); } catch { /* no ps */ }
  const text = fs.readFileSync(log, 'utf8');
  const started = /started in ([0-9.]+)s/.exec(text);
  rows.push({
    run: i + 1,
    processStartToFirstResponseMs: Math.round(toFirstResponseMs),
    quarkusStartedInMs: started ? Math.round(Number(started[1]) * 1000) : null,
    parseMicros: status.lastReloadMicros,
    loaded: status.resources.length,
    withProblems: (status.lastError.match(/\.xml:/g) || []).length,
    rssKb: rss,
  });
  p.kill();
  await sleep(300);
}
const med = (k) => [...rows.map((r) => r[k]).filter((v) => v != null)].sort((a, b) => a - b)[Math.floor(rows.length / 2)];
const result = { label, files, runs, median: { processStartToFirstResponseMs: med('processStartToFirstResponseMs'), quarkusStartedInMs: med('quarkusStartedInMs'), parseMicros: med('parseMicros'), rssKb: med('rssKb') }, rows };
console.log(JSON.stringify(result, null, 1));
fs.mkdirSync(path.join(here, 'results'), { recursive: true });
fs.writeFileSync(path.join(here, 'results', 'parse-' + label + '.json'), JSON.stringify(result, null, 1));
process.exit(0);
