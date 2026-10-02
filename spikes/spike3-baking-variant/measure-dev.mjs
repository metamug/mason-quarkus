import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const here = path.dirname(fileURLToPath(import.meta.url));
const dir = process.argv[2] || path.join(here, 'app', 'src', 'main', 'resources', 'mq');
const base = (process.env.MQ_BASE || 'http://localhost:8096') + '/v1.0';
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const get = async (p) => { try { const r = await fetch(base + p); return { status: r.status, text: await r.text() }; } catch { return { status: 0, text: '' }; } };
async function until(pred, t = 30000) { const t0 = Date.now(); for (;;) { if (await pred()) return Date.now() - t0; if (Date.now() - t0 > t) return -1; await sleep(5); } }
const xml = (txt) => `<Resource xmlns="http://xml.metamug.net/resource/1.0" v="1.0"><Request method="GET"><Text id="g">${txt}</Text></Request></Resource>\n`;
const out = {};
// edit an existing resource
const e = [];
for (let i = 1; i <= 4; i++) {
  fs.writeFileSync(dir + '/hello.xml', xml('edit-' + i));
  e.push(await until(async () => (await get('/hello')).text.includes('edit-' + i)));
}
out.edit_ms = e;
// create a new resource
const c = [];
for (let i = 1; i <= 3; i++) {
  fs.writeFileSync(dir + `/n${i}.xml`, xml('new-' + i));
  c.push(await until(async () => (await get('/n' + i)).text.includes('new-' + i)));
}
out.create_ms = c;
// delete
const d = [];
for (let i = 1; i <= 3; i++) {
  fs.rmSync(dir + `/n${i}.xml`);
  d.push(await until(async () => (await get('/n' + i)).status === 404));
}
out.delete_ms = d;
// invalid xml: what happens to a running app
fs.writeFileSync(dir + '/hello.xml', '<Resource v="1.0"><Request method="GET"><Bogus/></Request></Resource>');
await sleep(2500);
const bad = await get('/hello');
out.invalid_xml = { status: bad.status, bodyStart: bad.text.slice(0, 160).replace(/\s+/g, ' ') };
fs.writeFileSync(dir + '/hello.xml', xml('recovered'));
out.recovery_ms = await until(async () => (await get('/hello')).text.includes('recovered'));
console.log(JSON.stringify(out));
