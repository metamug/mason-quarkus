// THROWAWAY spike. Checks the Quarkus features through HTTP. usage: node check.mjs <base> <privateKeyPemFile>
import crypto from 'node:crypto';
import fs from 'node:fs';
const base = process.argv[2];
const key = crypto.createPrivateKey(fs.readFileSync(process.argv[3]));
const b64 = (o) => Buffer.from(typeof o === 'string' ? o : JSON.stringify(o)).toString('base64url');
const jwt = (claims) => {
  const head = b64({ alg: 'RS256', typ: 'JWT' });
  const now = Math.floor(Date.now() / 1000);
  const body = b64({ iss: 'https://id.example', sub: 'ada', upn: 'ada', groups: ['user'], iat: now, exp: now + 300, ...claims });
  return `${head}.${body}.${crypto.sign('RSA-SHA256', Buffer.from(`${head}.${body}`), key).toString('base64url')}`;
};
let ok = 0, bad = 0;
const check = (name, pass, detail = '') => { if (pass) { ok++; console.log('PASS ' + name); } else { bad++; console.log('FAIL ' + name + ' ' + String(detail).slice(0, 200)); } };
const get = async (p, headers = {}) => { const r = await fetch(base + p, { headers }); return { status: r.status, text: await r.text(), headers: r.headers }; };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

check('open route', (await get('/open/hello')).status === 200);
check('secured route without a token is 401', (await get('/secure/hello')).status === 401);
const good = await get('/secure/hello', { Authorization: 'Bearer ' + jwt({}) });
check('secured route with a valid JWT is 200 and knows the user', good.status === 200 && good.text.includes('"user":"ada"'), good.text);
check('JWT with the wrong issuer is 401', (await get('/secure/hello', { Authorization: 'Bearer ' + jwt({ iss: 'https://evil.example' }) })).status === 401);
check('expired JWT is 401', (await get('/secure/hello', { Authorization: 'Bearer ' + jwt({ exp: 1000 }) })).status === 401);

const main = await get('/db/main');
check('Flyway (classpath) migrated the default datasource', main.text.includes('"rows":3'), main.text);
const rep = await get('/db/reports');
check('Flyway (filesystem folder) migrated the named datasource, chosen by name at run time', rep.text.includes('"rows":2'), rep.text);

const c1 = await get('/cached/7'); const c2 = await get('/cached/7'); const c3 = await get('/cached/8');
check('cache: the second call does not compute again', c1.text.includes('"square":49') && c2.text.includes('"computed":1') && c3.text.includes('"computed":2'), c1.text + c2.text + c3.text);

await sleep(2500);
const metrics = await get('/q/metrics');
check('Prometheus metrics exist', metrics.status === 200 && metrics.text.includes('mq_requests_total'), metrics.text.slice(0, 100));
check('custom counter has the labelled routes', /mq_requests_total\{[^}]*route="secure"[^}]*\} [1-9]/.test(metrics.text), '');
check('scheduler ticked', /mq_ticks_total [1-9]/.test(metrics.text), '');
const health = await get('/q/health');
check('health reports UP including the datasources', health.status === 200 && health.text.includes('UP') && health.text.includes('Database'), health.text.slice(0, 200));
const oa = await get('/openapi');
check('OpenAPI document built from a model in code', oa.status === 200 && oa.text.includes('/db/{name}') && oa.text.includes('generated from the model'), oa.text.slice(0, 200));
const pre = await fetch(base + '/open/hello', { method: 'OPTIONS', headers: { Origin: 'https://app.example', 'Access-Control-Request-Method': 'GET' } });
check('CORS from configuration', pre.headers.get('access-control-allow-origin') === 'https://app.example', JSON.stringify([...pre.headers]));
const big = await fetch(base + '/open/hello', { method: 'POST', body: 'x'.repeat(2 * 1024 * 1024) });
check('body size limit from configuration (413)', big.status === 413, big.status);

console.log(`${ok} passed, ${bad} failed`);
process.exit(bad ? 1 : 0);
