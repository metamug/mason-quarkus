// A stand-in for the external API the shop scenario calls (postman-echo in R2's version): POST /post records the call,
// GET /status/500 answers 500, GET /_requests lists what was recorded.   usage: node stub.mjs [port]
import http from 'node:http';
const port = Number(process.argv[2] || 9999);
const seen = [];
http.createServer((req, res) => {
  let body = '';
  req.on('data', (c) => (body += c));
  req.on('end', () => {
    const send = (status, obj) => { res.writeHead(status, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(obj)); };
    if (req.url === '/_requests') return send(200, seen);
    if (req.url.startsWith('/status/500')) return send(500, { error: 'boom' });
    seen.push({ method: req.method, url: req.url, body });
    send(200, { ok: true });
  });
}).listen(port, '127.0.0.1', () => console.log('stub on ' + port));
