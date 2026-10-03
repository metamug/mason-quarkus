// HTTP-level acceptance of the script parts of R2 shop scenario (mcp/scenarios/shop/run.mjs): Script -> mpath -> Sql chains.
// usage: node http-test.mjs <base url>    database: samples/shop/schema.postgresql.sql loaded, plus one order (id 1, customer 1, status NEW)
import crypto from "node:crypto";
const base = process.argv[2] || "http://localhost:8080";
let passed = 0, failed = 0;
const check = (name, ok, detail = "") => { if (ok) passed++; else { failed++; console.log("FAIL " + name + " " + String(detail).slice(0, 300)); } };
const call = async (method, path, { query, body } = {}) => {
  const r = await fetch(base + path + (query ? "?" + new URLSearchParams(query) : ""), body === undefined ? { method } : { method, headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
  const text = await r.text(); let json = null; try { json = JSON.parse(text); } catch {}
  return { status: r.status, text, json };
};
const col = (row, name) => Object.entries(row ?? {}).find(([k]) => k.toLowerCase() === name)?.[1];
const has = (r, t) => r.text.toLowerCase().includes(t.toLowerCase());
const rows = (r, k) => r.json?.[k] ?? [];
const sleep = (ms) => new Promise((x) => setTimeout(x, ms));

console.log("== customer: script -> mpath -> sql ==");
const reg = await call("POST", "/v1.0/customer", { body: { name: "Ada", email: "ada@example.com", password: "secret" } });
check("register returns declared 201", reg.status === 201, reg.text);
check("register returns the created row", has(reg, "ada@example.com"), reg.text);
check("the password is not echoed", !has(reg, "secret"), reg.text);
const dup = await call("POST", "/v1.0/customer", { body: { name: "Ada2", email: "ada@example.com", password: "x" } });
check("duplicate email is rejected (not 2xx)", dup.status >= 400, dup.text);
for (const [name, email, password] of [["Grace", "grace@example.com", "p2"], ["Linus", "linus@example.com", "p3"]]) await call("POST", "/v1.0/customer", { body: { name, email, password } });
check("collection lists all 3", rows(await call("GET", "/v1.0/customer"), "all").length === 3);
const recent = await call("GET", "/v1.0/customer", { query: { q: "recent" } });
check("?q=recent branch returns the 2 newest", rows(recent, "recent").length === 2 && !recent.json?.all, recent.text);
check("item request returns customer 1", rows(await call("GET", "/v1.0/customer/1"), "one").length === 1);
check("missing item returns empty result", rows(await call("GET", "/v1.0/customer/999"), "one").length === 0);

console.log("== script-only resource: one script for item and collection requests ==");
const item1 = await call("GET", "/v1.0/token/hello");
check("item request: sha256 of the id", item1.json?.digest?.sha256 === crypto.createHash("sha256").update("hello").digest("hex"), item1.text);
check("item request: md5, base64 and a deterministic uuid", item1.json?.digest?.md5 === crypto.createHash("md5").update("hello").digest("hex") && item1.json?.digest?.base64 === Buffer.from("hello").toString("base64") && /^[0-9a-f-]{36}$/.test(item1.json?.digest?.uuid || ""), item1.text);
const item2 = await call("GET", "/v1.0/token/hello");
check("item request is deterministic", item2.json?.digest?.uuid === item1.json?.digest?.uuid, item2.text);
const coll = await call("GET", "/v1.0/token", { query: { count: "5" } });
check("collection request: 5 distinct uuids", new Set(coll.json?.many?.items || []).size === 5, coll.text);

console.log("== backwards compatibility: the former Groovy script, in Kotlin ==");
const legacy = await call("GET", "/v1.0/legacy", { query: { who: "R2" } });
check("the Kotlin script sees its request parameter", legacy.json?.g?.message === "Hello R2" && legacy.json?.g?.length === 2, legacy.text);

console.log("== order: state machine script decides whether the update runs ==");
const ship = await call("PUT", "/v1.0/order/1", { body: { status: "SHIPPED" } });
check("NEW -> SHIPPED is refused by the state machine script", has(ship, "cannot go from NEW to SHIPPED"), ship.text);
check("...and the update step did not run", col((await call("GET", "/v1.0/order/1")).json?.order?.[0], "status") === "NEW");
const pay = await call("PUT", "/v1.0/order/1", { body: { status: "PAID" } });
check("NEW -> PAID accepted, declared 202", pay.status === 202, pay.text);
check("status is now PAID", has(await call("GET", "/v1.0/order/1"), "PAID"));
const unknown = await call("PUT", "/v1.0/order/999", { body: { status: "PAID" } });
check("unknown order is reported by the script", has(unknown, "unknown order"), unknown.text);

console.log(passed + " passed, " + failed + " failed");
process.exit(failed ? 1 : 0);
