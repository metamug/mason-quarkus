// HTTP-level conformance check of the SQL-only shop scenario (samples/shop-sql), the same checks as mq-engine's ShopSqlTest.
// usage: node http-test.mjs <base url>      the database must hold samples/shop-sql/schema.postgresql.sql, freshly loaded
// Runs the same way against the JVM application and the native binary.
const base = process.argv[2] || 'http://localhost:8080';
let failed = 0;
let passed = 0;
const check = (name, ok, detail = '') => {
  if (ok) passed++;
  else {
    failed++;
    console.log(`FAIL ${name} ${String(detail).slice(0, 300)}`);
  }
};
const call = async (method, path, { query, body, form } = {}) => {
  const url = base + path + (query ? '?' + new URLSearchParams(query) : '');
  const init = { method, headers: {} };
  if (body !== undefined) {
    init.headers['Content-Type'] = 'application/json';
    init.body = JSON.stringify(body);
  } else if (form) {
    init.headers['Content-Type'] = 'application/x-www-form-urlencoded';
    init.body = new URLSearchParams(form).toString();
  }
  const r = await fetch(url, init);
  const text = await r.text();
  let json = null;
  try { json = JSON.parse(text); } catch { /* not json */ }
  return { status: r.status, text, json };
};
const col = (row, name) => Object.entries(row).find(([k]) => k.toLowerCase() === name)?.[1];
const num = (v) => Number(v);

// customers
const ada = await call('POST', '/v1.0/customer', { body: { name: 'Ada', email: 'ada@example.com' } });
check('register returns the declared 201', ada.status === 201, ada.text);
check('register returns the created row', col(ada.json?.created?.[0] ?? {}, 'email') === 'ada@example.com', ada.text);
check('output=false hides the insert', !('ins' in (ada.json ?? {})), ada.text);
check('duplicate email is 409', (await call('POST', '/v1.0/customer', { body: { name: 'Ada2', email: 'ada@example.com' } })).status === 409);
const badEmail = await call('POST', '/v1.0/customer', { body: { name: 'X', email: 'nope' } });
check('invalid email is 400 and names the parameter', badEmail.status === 400 && badEmail.text.includes('email'), badEmail.text);
check('missing name is 400', (await call('POST', '/v1.0/customer', { body: { email: 'x@example.com' } })).status === 400);
await call('POST', '/v1.0/customer', { body: { name: 'Grace', email: 'grace@example.com' } });
await call('POST', '/v1.0/customer', { body: { name: 'Linus', email: 'linus@example.com' } });
const all = await call('GET', '/v1.0/customer');
check('collection lists 3', all.json?.all?.length === 3 && !('recent' in all.json), all.text);
const recent = await call('GET', '/v1.0/customer', { query: { q: 'recent' } });
check('?q=recent branch returns 2 and skips the other step', recent.json?.recent?.length === 2 && !('all' in recent.json), recent.text);

// item read, typed binding
const one = await call('GET', '/v1.0/customer/1');
check('item read binds the path value as an integer', one.status === 200 && one.json?.one?.length === 1, one.text);
check('missing item is an empty result', (await call('GET', '/v1.0/customer/999')).json?.one?.length === 0);
const abc = await call('GET', '/v1.0/customer/abc');
check('text where an integer is expected is 400', abc.status === 400 && abc.text.includes('whole number'), abc.text);
check('unknown path is 404', (await call('GET', '/v1.0/nothing')).status === 404);
check('wrong method is 405', (await call('DELETE', '/v1.0/customer')).status === 405);
check('LIKE with a variable inside quotes', (await call('GET', '/v1.0/product', { query: { name: 'Note' } })).json?.found?.length === 1);

// orders
const o1 = await call('POST', '/v1.0/order', { body: { customer_id: 1, product_id: 1, qty: 3 } });
check('order POST returns the declared 201', o1.status === 201, o1.text);
const placed = o1.json?.placed?.[0] ?? {};
check('placed order is joined with the customer and priced 3 x 4.50', col(placed, 'customer') === 'Ada' && col(placed, 'status') === 'NEW' && num(col(placed, 'total')) === 13.5, o1.text);
check('transaction decremented the stock to 97', num(col((await call('GET', '/v1.0/product/1')).json?.one?.[0] ?? {}, 'stock')) === 97);
const o2 = await call('POST', '/v1.0/order', { form: { customer_id: '2', product_id: '2', qty: '10' } });
check('form-encoded values are bound as numbers', o2.status === 201 && num(col((await call('GET', '/v1.0/product/2')).json?.one?.[0] ?? {}, 'stock')) === 490, o2.text);
const many = await call('POST', '/v1.0/order', { body: { customer_id: 1, product_id: 1, qty: 1000 } });
check('insufficient stock is reported and nothing is placed', many.text.includes('insufficient stock') && !('placed' in (many.json ?? {})), many.text);
check('stock unchanged after the rejected order', num(col((await call('GET', '/v1.0/product/1')).json?.one?.[0] ?? {}, 'stock')) === 97);
check('unknown product is rejected', (await call('POST', '/v1.0/order', { body: { customer_id: 1, product_id: 999, qty: 1 } })).text.includes('unknown product'));
check('qty below the declared min is 400', (await call('POST', '/v1.0/order', { body: { customer_id: 1, product_id: 1, qty: 0 } })).status === 400);
check('qty is required', (await call('POST', '/v1.0/order', { body: { customer_id: 1, product_id: 1 } })).status === 400);
const item = await call('GET', '/v1.0/order/1');
check('item read joins order, customer and lines', col(item.json?.order?.[0] ?? {}, 'customer') === 'Ada' && item.json?.lines?.length === 1 && col(item.json.lines[0], 'name') === 'Notebook', item.text);
check('?customer_id branch', (await call('GET', '/v1.0/order', { query: { customer_id: '1' } })).json?.byCustomer?.length === 1);
const byStatus = await call('GET', '/v1.0/order', { query: { status: 'PAID' } });
check('?status branch (none yet)', byStatus.json?.byStatus?.length === 0 && !('all' in byStatus.json), byStatus.text);
check('no filter returns all 2 orders', (await call('GET', '/v1.0/order')).json?.all?.length === 2);
const pay = await call('PUT', '/v1.0/order/1', { body: { status: 'PAID' } });
check('PUT returns the declared 202', pay.status === 202, pay.text);
check('status is now PAID', col((await call('GET', '/v1.0/order/1')).json?.order?.[0] ?? {}, 'status') === 'PAID');

// failure semantics
const tx = await call('POST', '/v1.0/txtest', { body: { customer_id: 1 } });
check('failing transaction is 409 with an errorId and no SQL in the answer', tx.status === 409 && tx.json?.errorId && !tx.text.includes('INSERT'), tx.text);
const txRows = await call('GET', '/v1.0/order', { query: { status: 'TX' } });
check('the first statement was rolled back', txRows.json?.byStatus?.length === 0, txRows.text);

console.log(`${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
