// Reload scenarios against the example app in Quarkus dev mode: file change -> visible through HTTP (app on :8090).
// Adapted from spikes/spike1-dev-reload/measure.mjs; only the MQ watcher is measured (the Quarkus trigger modes were ruled out by Spike 1).
import fs from 'node:fs';
import path from 'node:path';

import { fileURLToPath } from 'node:url';
const here = path.dirname(fileURLToPath(import.meta.url));
// resource folder being watched (default: the sample app's mq/ folder); label names the results file
const dir = process.argv[2] || path.join(here, 'mq');
const base = process.env.MQ_BASE || 'http://localhost:8090';
const xml = (desc) => `<Resource xmlns="http://xml.metamug.net/resource/1.0" v="1.0"><Desc>${desc}</Desc><Request method="GET"><Text id="t">x</Text></Request></Resource>\n`;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const get = async (p) => {
  const res = await fetch(base + p);
  return { status: res.status, text: await res.text() };
};
const status = async () => JSON.parse((await get('/status')).text);
async function until(pred, timeoutMs = 15000) {
  const t0 = process.hrtime.bigint();
  for (;;) {
    const v = await pred();
    if (v) return { ms: Number(process.hrtime.bigint() - t0) / 1e6, v };
    if (Number(process.hrtime.bigint() - t0) / 1e6 > timeoutMs) return { ms: -1, v: null };
    await sleep(2);
  }
}
const trigger = 'watcher';
const kick = () => {};
const write = (name, content) => { fs.writeFileSync(path.join(dir, name + '.xml'), content); kick(); };
const rm = (name) => { fs.rmSync(path.join(dir, name + '.xml'), { force: true }); kick(); };
const results = [];
const rec = (scenario, obj) => {
  results.push({ scenario, ...obj });
  console.log(JSON.stringify({ scenario, ...obj }));
};

const s0 = await status();
const sameApp = async () => (await status()).appStartId === s0.appStartId;

// 1. edit an existing file (5 runs)
{
  const times = [];
  for (let i = 1; i <= 5; i++) {
    write('first', xml(`first-e${i}`));
    const r = await until(async () => (await get('/r/first')).text === `first-e${i}`);
    times.push(Math.round(r.ms));
  }
  console.log('trigger mode:', trigger);
  rec('1 edit existing', { ms: times, restarted: !(await sameApp()) });
}
// 2. create a new file (5 runs, new names)
{
  const times = [];
  for (let i = 1; i <= 5; i++) {
    write(`new${i}`, xml(`new${i}-v1`));
    const r = await until(async () => (await get(`/r/new${i}`)).text === `new${i}-v1`);
    times.push(Math.round(r.ms));
  }
  rec('2 create new', { ms: times, restarted: !(await sameApp()) });
}
// 3. delete
{
  const times = [];
  for (let i = 1; i <= 5; i++) {
    rm(`new${i}`);
    const r = await until(async () => (await get(`/r/new${i}`)).status === 404);
    times.push(Math.round(r.ms));
  }
  rec('3 delete', { ms: times, restarted: !(await sameApp()) });
}
// 4. rename
{
  write('old', xml('old-v1'));
  await until(async () => (await get('/r/old')).status === 200);
  fs.renameSync(path.join(dir, 'old.xml'), path.join(dir, 'renamed.xml'));
  kick();
  const r = await until(async () => (await get('/r/renamed')).status === 200 && (await get('/r/old')).status === 404);
  rec('4 rename', { ms: Math.round(r.ms), restarted: !(await sameApp()) });
  rm('renamed');
}
// 5. 20 files at once
{
  const t0 = process.hrtime.bigint();
  for (let i = 1; i <= 20; i++) write(`bulk${i}`, xml(`bulk${i}`));
  const partial = new Set();
  const r = await until(async () => {
    const st = await status();
    const n = st.resources.filter((x) => x.startsWith('bulk')).length;
    partial.add(n);
    return n === 20;
  });
  rec('5 twenty files at once', { ms: Math.round(r.ms), writeMs: Math.round(Number(process.hrtime.bigint() - t0) / 1e6 - r.ms), distinctVisibleCounts: [...partial].sort((a, b) => a - b), restarted: !(await sameApp()) });
  for (let i = 1; i <= 20; i++) rm(`bulk${i}`);
  await until(async () => (await status()).resources.every((x) => !x.startsWith('bulk')));
}
// 6. invalid XML keeps the old model
{
  write('first', xml('good-before-invalid'));
  await until(async () => (await get('/r/first')).text === 'good-before-invalid');
  write('first', '<Resource><Desc>broken');
  const r = await until(async () => (await status()).lastError.includes('first'));
  const still = (await get('/r/first')).text;
  const st = await status();
  rec('6 invalid xml', { detectMs: Math.round(r.ms), oldModelStillServed: still === 'good-before-invalid', lastError: st.lastError.slice(0, 120), restarted: !(await sameApp()) });
  write('first', xml('fixed-after-invalid'));
  const f = await until(async () => (await get('/r/first')).text === 'fixed-after-invalid');
  rec('6b recovery after fix', { ms: Math.round(f.ms) });
}
// 7. two edits within 100 ms
{
  write('first', xml('rapid-1'));
  await sleep(50);
  write('first', xml('rapid-2'));
  const r = await until(async () => (await get('/r/first')).text === 'rapid-2');
  await sleep(300);
  rec('7 two edits in 50 ms', { ms: Math.round(r.ms), finalValue: (await get('/r/first')).text, restarted: !(await sameApp()) });
}
// 9. latency of a scan that is not triggered by the file read itself: no polling in between
{
  write('first', xml('quiet-1'));
  await sleep(1500); // nothing requests anything
  const r = await until(async () => (await get('/r/first')).text === 'quiet-1');
  rec('9 first request after 1.5 s of silence', { ms: Math.round(r.ms) });
}

const final = await status();
rec('final', { generation: final.generation, restarted: !(await sameApp()), lastReloadMicros: final.lastReloadMicros });
fs.mkdirSync(path.join(here, 'results'), { recursive: true });
fs.writeFileSync(path.join(here, 'results', (process.argv[3] || 'run') + '.json'), JSON.stringify(results, null, 1));
