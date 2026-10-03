// Generates the golden cases (golden/valid, golden/invalid) and golden/manifest.tsv.
// kinds:  valid            R2 accepts it (the XSD oracle says VALID)
//         invalid-xsd      R2's schema rejects it (the XSD oracle says INVALID)
//         invalid-semantic R2's Java code rejects it although the schema accepts it (mpath references, limit/offset on updates)
//         invalid-mq       accepted by R2's schema, rejected by MQ on purpose (documented in docs/validator-gap-analysis.md)
// usage: node golden/gen.mjs
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const NS = 'http://xml.metamug.net/resource/1.0';
const cases = [];

/** one resource file: the <Resource> element is on line 2, every child on its own line */
const res = (children = [], attrs = 'v="1.0"', ns = NS, decl = '<?xml version="1.0" encoding="UTF-8"?>') =>
  `${decl}\n<Resource ${ns ? `xmlns="${ns}" ` : ''}${attrs}>\n${children.map((c) => '  ' + c).join('\n')}\n</Resource>\n`;
const req = (inner = [], attrs = 'method="GET"') => [`<Request ${attrs}>`, ...inner.map((c) => '  ' + c), '</Request>'];
const get = (inner) => req(inner);
const add = (name, kind, rule, xml) => cases.push({ name, kind, rule, xml });
const V = (name, rule, xml) => add(name, 'valid', rule, xml);
const X = (name, rule, xml) => add(name, 'invalid-xsd', rule, xml);
const S = (name, rule, xml) => add(name, 'invalid-semantic', rule, xml);
const M = (name, rule, xml) => add(name, 'invalid-mq', rule, xml);
const text = (id = 't') => `<Text id="${id}">x</Text>`;
const one = (stepXml, attrs) => res(req([stepXml], attrs));
const R = (...lines) => lines.flat();

// ---------- document level ----------
V('resource-minimal', 'smallest valid resource', res(get([text()])));
V('resource-empty-request', 'a Request may be empty', res(req([])));
V('resource-xml-declaration-latin1', 'xml declaration with another encoding', res(get([text()]), 'v="1.0"', NS, '<?xml version="1.0" encoding="ISO-8859-1"?>'));
V('resource-no-xml-declaration', 'the xml declaration is optional', res(get([text()]), 'v="1.0"', NS, ''));
const XSI = 'xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"';
V('resource-xsi-schema-location', 'xsi:schemaLocation is allowed on any element (R2 shop resources use it)', res(get([text()]), `v="1.0" ${XSI} xsi:schemaLocation="${NS} http://xml.metamug.net/schema/resource.xsd"`));
V('resource-xsi-no-namespace-schema-location', 'xsi:noNamespaceSchemaLocation is allowed', res(get([text()]), `v="1.0" ${XSI} xsi:noNamespaceSchemaLocation="resource.xsd"`));
V('request-xsi-schema-location', 'xsi attributes are allowed on a child element too', res([`<Request method="GET" ${XSI} xsi:schemaLocation="${NS} x.xsd">`, '  ' + text(), '</Request>']));
X('resource-xsi-nil', 'xsi:nil on an element that is not nillable', res(get([text()]), `v="1.0" ${XSI} xsi:nil="true"`));
V('resource-comments', 'comments and processing instructions anywhere', `<?xml version="1.0"?>\n<!-- top -->\n<Resource xmlns="${NS}" v="1.0">\n  <!-- inside -->\n  <?pi data?>\n  <Request method="GET"><Text id="t">x</Text></Request>\n</Resource>\n<!-- end -->\n`);
V('resource-sql-cdata', 'CDATA in Sql', res(get([`<Sql id="a"><![CDATA[SELECT a FROM t WHERE b < 1 AND c > 2]]></Sql>`])));
V('resource-sql-entities', 'predefined entities in Sql', res(get([`<Sql id="a">SELECT a FROM t WHERE b &lt; 1 AND c &gt; 2 AND d = &apos;x&apos;</Sql>`])));
V('resource-two-requests', 'several requests', res([...get([text('a')]), ...req([text('b')], 'method="POST"')]));
X('document-wrong-root', 'root element must be Resource', `<?xml version="1.0"?>\n<Thing xmlns="${NS}" v="1.0">\n  <Request method="GET"/>\n</Thing>\n`);
X('document-no-namespace', 'the resource namespace is required', res(get([text()]), 'v="1.0"', ''));
X('document-wrong-namespace', 'wrong namespace', res(get([text()]), 'v="1.0"', 'http://example.com/other'));
X('document-child-without-namespace', 'child elements must be in the namespace', `<?xml version="1.0"?>\n<Resource xmlns="${NS}" v="1.0">\n  <Request xmlns="" method="GET"/>\n</Resource>\n`);
X('document-not-well-formed-truncated', 'truncated document', `<?xml version="1.0"?>\n<Resource xmlns="${NS}" v="1.0">\n  <Request method="GET">\n    <Sql id="a">SELECT 1`);
X('document-not-well-formed-mismatched', 'mismatched end tag', `<?xml version="1.0"?>\n<Resource xmlns="${NS}" v="1.0">\n  <Request method="GET">\n  </Resource>\n`);
X('document-not-xml', 'plain text', 'this is not xml at all\n');
X('document-empty-file', 'empty file', '');
X('document-prolog-only', 'only an xml declaration', '<?xml version="1.0"?>\n');
X('document-text-in-resource', 'character data between elements of Resource', `<?xml version="1.0"?>\n<Resource xmlns="${NS}" v="1.0">\n  stray text\n  <Request method="GET"/>\n</Resource>\n`);
X('document-text-in-request', 'character data between elements of Request', `<?xml version="1.0"?>\n<Resource xmlns="${NS}" v="1.0">\n  <Request method="GET">\n    stray text\n  </Request>\n</Resource>\n`);
X('document-duplicate-attribute', 'the same attribute twice (not well-formed)', `<?xml version="1.0"?>\n<Resource xmlns="${NS}" v="1.0" v="2.0">\n  <Request method="GET"/>\n</Resource>\n`);

// ---------- Resource element ----------
V('resource-version-integer', 'v may be an integer', res(get([text()]), 'v="2"'));
V('resource-version-decimal', 'v decimal', res(get([text()]), 'v="1.10"'));
V('resource-version-fraction', 'v below 1', res(get([text()]), 'v="0.5"'));
V('resource-parent', 'parent attribute', res(get([text()]), 'v="1.0" parent="movie"'));
V('resource-auth', 'auth attribute starting with a letter', res(get([text()]), 'v="1.0" auth="supplier"'));
X('resource-version-missing', 'v is required', res(get([text()]), ''));
X('resource-version-zero', 'v must be greater than 0', res(get([text()]), 'v="0"'));
X('resource-version-negative', 'v must be greater than 0', res(get([text()]), 'v="-1.0"'));
X('resource-version-text', 'v must be a number', res(get([text()]), 'v="one"'));
X('resource-version-three-parts', 'v must be a number (1.0.1 is not)', res(get([text()]), 'v="1.0.1"'));
X('resource-version-empty', 'v must not be empty', res(get([text()]), 'v=""'));
X('resource-parent-empty', 'parent must not be empty', res(get([text()]), 'v="1.0" parent=""'));
X('resource-auth-digit-first', 'auth must start with a letter', res(get([text()]), 'v="1.0" auth="1abc"'));
X('resource-auth-empty', 'auth must start with a letter (empty has no first letter)', res(get([text()]), 'v="1.0" auth=""'));
X('resource-unknown-attribute', 'unknown attribute on Resource', res(get([text()]), 'v="1.0" colour="red"'));
X('resource-no-request', 'at least one Request', res([], 'v="1.0"'));
X('resource-only-desc', 'Desc alone is not enough', res(['<Desc>nothing else</Desc>'], 'v="1.0"'));
X('resource-unknown-child', 'unknown child of Resource', res([...get([text()]), '<Thing/>']));

// ---------- Desc and Tag ----------
V('desc-text', 'Desc with text', res(['<Desc>A resource</Desc>', ...get([text()])]));
V('desc-with-tag', 'Desc with Tag', res([`<Desc>A resource <Tag name="customers" color="#CAFE00"/></Desc>`, ...get([text()])]));
V('desc-empty', 'empty Desc', res(['<Desc/>', ...get([text()])]));
V('desc-in-request', 'Desc inside Request', res(req(['<Desc>get it</Desc>', text()])));
X('desc-after-request', 'Desc must come before the Requests', res([...get([text()]), '<Desc>too late</Desc>']));
X('desc-twice', 'only one Desc before the Requests', res(['<Desc>a</Desc>', '<Desc>b</Desc>', ...get([text()])]));
X('desc-tag-unknown-attribute', 'unknown attribute on Tag', res([`<Desc><Tag size="big"/></Desc>`, ...get([text()])]));
X('desc-unknown-child', 'unknown child in Desc', res(['<Desc><b>bold</b></Desc>', ...get([text()])]));

// ---------- Request ----------
for (const m of ['HEAD', 'GET', 'POST', 'PUT', 'DELETE']) V(`request-method-${m.toLowerCase()}`, `method ${m}`, res(req([text()], `method="${m}"`)));
V('request-item-true', 'item="true"', res(req([text()], 'method="GET" item="true"')));
V('request-item-any-string', 'item is any string, not a boolean', res(req([text()], 'method="GET" item="action"')));
V('request-item-false-is-still-an-item', 'item="false" is accepted by the schema (Mason treats any non-blank value as an item request)', res(req([text()], 'method="GET" item="false"')));
V('request-status-lower-bound', 'status 100', res(req([text()], 'method="GET" status="100"')));
V('request-status-upper-bound', 'status 599', res(req([text()], 'method="GET" status="599"')));
V('request-status-created', 'status 201', res(req([text()], 'method="POST" status="201"')));
X('request-method-missing', 'method is required', res(req([text()], '')));
X('request-method-patch', 'PATCH is not in the method list', res(req([text()], 'method="PATCH"')));
X('request-method-lowercase', 'methods are upper case', res(req([text()], 'method="get"')));
X('request-method-unknown', 'unknown method', res(req([text()], 'method="FETCH"')));
X('request-method-empty', 'empty method', res(req([text()], 'method=""')));
X('request-status-too-low', 'status below 100', res(req([text()], 'method="GET" status="99"')));
X('request-status-too-high', 'status 600', res(req([text()], 'method="GET" status="600"')));
X('request-status-text', 'status must be a number', res(req([text()], 'method="GET" status="ok"')));
X('request-status-decimal', 'status must be an integer', res(req([text()], 'method="GET" status="201.5"')));
X('request-unknown-attribute', 'unknown attribute on Request', res(req([text()], 'method="GET" cache="true"')));
X('request-unknown-child', 'unknown child of Request', res(req(['<Frobnicate id="f"/>'])));
X('request-method-item-duplicate', 'same method and item twice (xsd:unique)', res([...req([text('a')], 'method="GET" item="true"'), ...req([text('b')], 'method="GET" item="true"')]));
X('request-method-item-duplicate-named', 'same method and the same item string twice', res([...req([text('a')], 'method="GET" item="x"'), ...req([text('b')], 'method="GET" item="x"')]));
V('request-method-item-different', 'same method, different item strings', res([...req([text('a')], 'method="GET" item="x"'), ...req([text('b')], 'method="GET" item="y"')]));
V('request-collection-and-item', 'GET collection and GET item', res([...req([text('a')], 'method="GET"'), ...req([text('b')], 'method="GET" item="true"')]));
V('request-duplicate-method-without-item-is-not-checked', 'xsd:unique ignores requests without item: two plain GETs pass the schema', res([...req([text('a')], 'method="GET"'), ...req([text('b')], 'method="GET"')]));

// ---------- element ids (xsd:unique over Request/*/@id) ----------
X('id-duplicate-across-requests', 'ids are unique across all requests', res([...req([`<Sql id="a">SELECT 1</Sql>`], 'method="GET"'), ...req([`<Sql id="a" type="update">UPDATE t SET x = 1</Sql>`], 'method="POST"')]));
X('id-duplicate-sql-and-text', 'a Sql and a Text with the same id', res(get([`<Sql id="a">SELECT 1</Sql>`, text('a')])));
X('id-duplicate-same-request', 'two steps with the same id in one request', res(get([text('a'), text('a')])));
X('id-duplicate-script-xrequest', 'a Script and an XRequest with the same id', res(get([`<Script id="a" file="f"/>`, `<XRequest id="a" url="http://x" method="GET"/>`])));
M('id-duplicate-inside-transaction', 'MQ rejects what the schema misses: the same id inside a Transaction twice (mpath could not tell them apart)', res(get([`<Transaction>`, `  <Sql id="a" type="update">UPDATE t SET x = 1</Sql>`, `  <Sql id="a" type="update">UPDATE t SET x = 2</Sql>`, `</Transaction>`])));
M('id-transaction-sql-collides-with-step', 'MQ rejects a Sql inside a Transaction that reuses the id of a step outside', res(get([text('a'), `<Transaction>`, `  <Sql id="a" type="update">UPDATE t SET x = 1</Sql>`, `</Transaction>`])));
V('id-100-characters', 'id of exactly 100 characters', res(get([`<Text id="${'a'.repeat(100)}">x</Text>`])));
X('id-101-characters', 'id longer than 100 characters', res(get([`<Text id="${'a'.repeat(101)}">x</Text>`])));
X('id-empty', 'empty id', res(get([`<Text id="">x</Text>`])));

// ---------- Sql ----------
V('sql-query-default', 'Sql without type', one('<Sql id="a">SELECT 1</Sql>'));
V('sql-type-query', 'type query', one('<Sql id="a" type="query">SELECT 1</Sql>'));
V('sql-type-update', 'type update', one('<Sql id="a" type="update">UPDATE t SET x = 1</Sql>'));
V('sql-empty-body', 'empty Sql element', one('<Sql id="a"/>'));
V('sql-all-attributes', 'every Sql attribute together', one('<Sql id="a" type="query" datasource="main" requires="x,y" ref="saved" when="$q eq 1" onblank="none" onerror="oops" verbose="true" output="true" limit="count" offset="skip" classname="a.B" status="200">SELECT 1</Sql>'));
V('sql-boolean-numeric', 'xsd:boolean accepts 1 and 0', one('<Sql id="a" verbose="1" output="0">SELECT 1</Sql>'));
V('sql-limit-offset-query', 'limit and offset on a query', one('<Sql id="a" limit="count" offset="skip">SELECT 1</Sql>'));
X('sql-id-missing', 'id is required', one('<Sql>SELECT 1</Sql>'));
X('sql-type-delete', 'type is query or update', one('<Sql id="a" type="delete">DELETE FROM t</Sql>'));
X('sql-type-uppercase', 'type is lower case', one('<Sql id="a" type="UPDATE">UPDATE t SET x = 1</Sql>'));
X('sql-verbose-not-boolean', 'verbose must be a boolean', one('<Sql id="a" verbose="yes">SELECT 1</Sql>'));
X('sql-output-not-boolean', 'output must be a boolean', one('<Sql id="a" output="maybe">SELECT 1</Sql>'));
X('sql-limit-starts-with-digit', 'limit must start with a letter', one('<Sql id="a" limit="10">SELECT 1</Sql>'));
X('sql-offset-starts-with-digit', 'offset must start with a letter', one('<Sql id="a" offset="5">SELECT 1</Sql>'));
X('sql-requires-empty', 'requires must not be empty', one('<Sql id="a" requires="">SELECT 1</Sql>'));
X('sql-classname-empty', 'classname must not be empty', one('<Sql id="a" classname="">SELECT 1</Sql>'));
X('sql-status-out-of-range', 'status 600', one('<Sql id="a" status="600">SELECT 1</Sql>'));
X('sql-unknown-attribute-persist', 'persist is not an attribute (old dialect)', one('<Sql id="a" persist="true">SELECT 1</Sql>'));
X('sql-with-child-element', 'Sql holds text only', one('<Sql id="a"><b>x</b></Sql>'));
S('sql-limit-on-update', 'limit/offset are only allowed on queries (R2 Java throws SAXException)', one('<Sql id="a" type="update" limit="count">UPDATE t SET x = 1</Sql>'));
S('sql-offset-on-update', 'offset on an update', one('<Sql id="a" type="update" offset="skip">UPDATE t SET x = 1</Sql>'));

// ---------- Transaction ----------
V('transaction-two-sql', 'Transaction with two Sql', res(req([`<Transaction>`, `  <Sql id="a" type="update">UPDATE t SET x = 1</Sql>`, `  <Sql id="b" type="update">UPDATE t SET y = 2</Sql>`, `</Transaction>`], 'method="POST"')));
V('transaction-attributes', 'when and datasource', res(req([`<Transaction when="$q eq 1" datasource="main"><Sql id="a" type="update">UPDATE t SET x = 1</Sql></Transaction>`], 'method="POST"')));
V('transaction-empty', 'empty Transaction', res(req(['<Transaction/>'], 'method="POST"')));
X('transaction-with-script', 'only Sql inside Transaction', res(req([`<Transaction><Script id="s" file="f"/></Transaction>`], 'method="POST"')));
X('transaction-with-text', 'only Sql inside Transaction', res(req([`<Transaction><Text id="t">x</Text></Transaction>`], 'method="POST"')));
X('transaction-unknown-attribute', 'unknown attribute on Transaction', res(req([`<Transaction isolation="serializable"><Sql id="a" type="update">UPDATE t SET x = 1</Sql></Transaction>`], 'method="POST"')));
X('transaction-sql-without-id', 'Sql inside Transaction still needs an id', res(req([`<Transaction><Sql type="update">UPDATE t SET x = 1</Sql></Transaction>`], 'method="POST"')));

// ---------- XRequest ----------
V('xrequest-minimal', 'id, url, method', one('<XRequest id="x" url="http://example.com/a" method="GET"/>'));
V('xrequest-with-children', 'Param, Header and Body', one('<XRequest id="x" url="http://example.com/a" method="POST" output="true">\n    <Header name="Accept" value="application/json"/>\n    <Param name="p" value="$q"/>\n    <Body>{"a": 1}</Body>\n  </XRequest>'));
V('xrequest-output-headers', 'output="headers"', one('<XRequest id="x" url="http://x" method="GET" output="headers"/>'));
V('xrequest-all-attributes', 'when, verbose, classname', one('<XRequest id="x" url="http://x" method="GET" when="$q eq 1" verbose="false" output="false" classname="a.B"/>'));
V('xrequest-method-delete', 'method DELETE', one('<XRequest id="x" url="http://x" method="DELETE"/>'));
X('xrequest-url-missing', 'url is required', one('<XRequest id="x" method="GET"/>'));
X('xrequest-method-missing', 'method is required', one('<XRequest id="x" url="http://x"/>'));
X('xrequest-id-missing', 'id is required', one('<XRequest url="http://x" method="GET"/>'));
X('xrequest-method-patch', 'PATCH is not allowed', one('<XRequest id="x" url="http://x" method="PATCH"/>'));
X('xrequest-output-maybe', 'output is true, false or headers', one('<XRequest id="x" url="http://x" method="GET" output="maybe"/>'));
X('xrequest-param-without-value', 'Param needs name and value', one('<XRequest id="x" url="http://x" method="GET"><Param name="p"/></XRequest>'));
X('xrequest-header-without-name', 'Header needs a name', one('<XRequest id="x" url="http://x" method="GET"><Header value="v"/></XRequest>'));
X('xrequest-header-empty-name', 'Header name must not be empty', one('<XRequest id="x" url="http://x" method="GET"><Header name="" value="v"/></XRequest>'));
X('xrequest-unknown-attribute-persist', 'persist is not an attribute', one('<XRequest id="x" url="http://x" method="GET" persist="true"/>'));
X('xrequest-unknown-child', 'unknown child of XRequest', one('<XRequest id="x" url="http://x" method="GET"><Cookie name="a"/></XRequest>'));

// ---------- Script, Text, Execute ----------
V('script-minimal', 'id and file', one('<Script id="s" file="hello"/>'));
V('script-all-attributes', 'output and when', one('<Script id="s" file="hello.kts" output="false" when="$q eq 1"/>'));
M('script-file-groovy', 'Groovy is dropped: only Kotlin scripts', one('<Script id="s" file="legacy.groovy"/>'));
M('script-file-javascript', 'only Kotlin scripts', one('<Script id="s" file="tool.js"/>'));
M('script-file-path', 'a script is a name, not a path', one('<Script id="s" file="../scripts/x.kts"/>'));
M('script-file-dashed-name', 'the name becomes a class name', one('<Script id="s" file="my-script"/>'));
X('script-file-missing', 'file is required', one('<Script id="s"/>'));
X('script-id-missing', 'id is required', one('<Script file="hello"/>'));
X('script-file-empty', 'file must not be empty', one('<Script id="s" file=""/>'));
X('script-output-maybe', 'output must be a boolean', one('<Script id="s" file="f" output="maybe"/>'));
X('script-unknown-attribute', 'unknown attribute on Script', one('<Script id="s" file="f" language="kotlin"/>'));
X('script-with-child', 'Script is empty', one('<Script id="s" file="f"><Arg name="a"/></Script>'));
V('text-minimal', 'Text', one('<Text id="t">hello</Text>'));
V('text-attributes', 'when and output', one('<Text id="t" when="$q eq 1" output="false">hello</Text>'));
V('text-empty', 'empty Text', one('<Text id="t"/>'));
X('text-id-missing', 'id is required', one('<Text>hello</Text>'));
X('text-with-child', 'Text holds text only', one('<Text id="t"><b>x</b></Text>'));
V('execute-minimal', 'Execute with classname', one('<Execute id="e" classname="com.example.Run"/>'));
V('execute-with-args', 'Arg children', one('<Execute id="e" classname="com.example.Run" requires="a" when="$q eq 1" onerror="x" verbose="true" output="true" status="200">\n    <Arg name="a" value="1"/>\n    <Arg name="b" path="$q"/>\n  </Execute>'));
X('execute-id-missing', 'id is required', one('<Execute classname="com.example.Run"/>'));
X('execute-arg-without-name', 'Arg needs a name', one('<Execute id="e"><Arg value="1"/></Execute>'));
X('execute-unknown-child', 'unknown child of Execute', one('<Execute id="e"><Param name="a"/></Execute>'));

// ---------- Param and Header (request level) ----------
for (const t of ['date', 'datetime', 'email', 'number', 'text', 'time', 'url']) V(`param-type-${t}`, `Param type ${t}`, res(req([`<Param name="p" type="${t}"/>`])));
V('param-all-attributes', 'every Param attribute', res(req([`<Param name="p" type="number" min="1" max="5.5" minlength="0" maxlength="10" pattern="[a-z]+" exists="abc" value="1" testvalue="2" required="true"/>`])));
V('header-minimal', 'request-level Header', res(req([`<Header name="X-Total" value="1"/>`])));
X('param-type-missing', 'type is required', res(req([`<Param name="p"/>`])));
X('param-type-string', 'string is not a Param type', res(req([`<Param name="p" type="string"/>`])));
X('param-name-missing', 'name is required', res(req([`<Param type="text"/>`])));
X('param-name-empty', 'name must not be empty', res(req([`<Param name="" type="text"/>`])));
X('param-max-not-a-number', 'max must be a number', res(req([`<Param name="p" type="number" max="lots"/>`])));
X('param-minlength-negative', 'minlength must be 0 or more', res(req([`<Param name="p" type="text" minlength="-1"/>`])));
X('param-maxlength-decimal', 'maxlength must be an integer', res(req([`<Param name="p" type="text" maxlength="1.5"/>`])));
X('param-exists-too-short', 'exists needs at least 3 characters', res(req([`<Param name="p" type="text" exists="ab"/>`])));
X('param-required-not-boolean', 'required must be a boolean', res(req([`<Param name="p" type="text" required="sometimes"/>`])));
X('param-unknown-attribute', 'unknown attribute on Param', res(req([`<Param name="p" type="text" default="x"/>`])));
X('header-value-missing', 'Header needs a value', res(req([`<Header name="X-Total"/>`])));
X('header-name-missing', 'Header needs a name', res(req([`<Header value="1"/>`])));

// ---------- the when condition language ----------
V('when-eq-string', 'when with a string comparison', one('<Sql id="a" when="$q eq \x27recent\x27">SELECT 1</Sql>'));
V('when-and-or-not-empty', 'when with and, or, not, empty and parentheses', one('<Sql id="a" when="(not empty $customer_id or empty $status) and $n ge 1">SELECT 1</Sql>'));
V('when-symbols', 'when with symbolic operators', one('<Sql id="a" when="$n &gt;= 1 and $n != 3 and $m == 2">SELECT 1</Sql>'));
V('when-mpath-boolean', 'when comparing a step result with true', res(get([`<Script id="calc" file="c"/>`, `<Transaction when="$[calc].ok eq true"><Sql id="o" type="update">UPDATE t SET x = 1</Sql></Transaction>`])));
S('when-unbalanced-paren', 'parenthesis not closed', one('<Sql id="a" when="($q eq 1">SELECT 1</Sql>'));

// ---------- semantic checks of R2's Java code (mpath), the schema cannot see these ----------
V('mpath-earlier-step', 'mpath to an earlier step', one('<Sql id="a">SELECT 1</Sql>\n  <Sql id="b">SELECT $[a].x</Sql>'));
V('mpath-in-when-earlier', 'mpath in a when condition to an earlier step', res(get([`<Script id="calc" file="c"/>`, `<Sql id="b" when="$[calc].ok eq true">SELECT 1</Sql>`])));
V('mpath-in-xrequest-body', 'mpath in an XRequest body', res(get([`<Script id="calc" file="c"/>`, `<XRequest id="x" url="http://x" method="POST"><Body>{"t": "$[calc].token"}</Body></XRequest>`])));
V('mpath-in-text', 'a request parameter that looks like an mpath-free variable', res(get([`<Sql id="a">SELECT $id, $name</Sql>`])));
V('mpath-in-transaction-earlier', 'mpath inside a Transaction to a step before it', res(req([`<Script id="calc" file="c"/>`, `<Transaction><Sql id="o" type="update">INSERT INTO t (a) VALUES ($[calc].total)</Sql></Transaction>`], 'method="POST"')));
S('mpath-unknown-id', 'mpath to an id that does not exist (R2: "Could not find element with ID")', one('<Sql id="a">SELECT $[nosuch].x</Sql>'));
S('mpath-forward-reference', 'mpath to a step declared later', one('<Sql id="a">SELECT $[b].x</Sql>\n  <Sql id="b">SELECT 1</Sql>'));
S('mpath-self-reference', 'a step cannot read its own result', one('<Sql id="a">SELECT $[a].x</Sql>'));
S('mpath-in-when-unknown', 'mpath in a when condition to an unknown id', one('<Sql id="a" when="$[ghost].ok eq true">SELECT 1</Sql>'));
S('mpath-in-xrequest-url-unknown', 'mpath in an XRequest url to an unknown id', one('<XRequest id="x" url="http://x/$[ghost].id" method="GET"/>'));
S('mpath-in-transaction-unknown', 'mpath in a Transaction Sql to an unknown id', res(req([`<Transaction><Sql id="o" type="update">INSERT INTO t (a) VALUES ($[ghost].total)</Sql></Transaction>`], 'method="POST"')));
S('mpath-other-request', 'mpath to a step of another Request is not available', res([...req([`<Sql id="a">SELECT 1</Sql>`], 'method="GET"'), ...req([`<Sql id="b">SELECT $[a].x</Sql>`], 'method="POST"')]));

// ---------- write ----------
for (const d of ['valid', 'invalid']) fs.rmSync(path.join(here, d), { recursive: true, force: true });
fs.mkdirSync(path.join(here, 'valid'), { recursive: true });
fs.mkdirSync(path.join(here, 'invalid'), { recursive: true });
const seen = new Set();
const rows = ['name\tkind\trule'];
for (const c of cases) {
  if (seen.has(c.name)) throw new Error('duplicate case name ' + c.name);
  seen.add(c.name);
  const dir = c.kind === 'valid' ? 'valid' : 'invalid';
  fs.writeFileSync(path.join(here, dir, c.name + '.xml'), c.xml);
  rows.push(`${c.name}\t${c.kind}\t${c.rule}`);
}
fs.writeFileSync(path.join(here, 'manifest.tsv'), rows.join('\n') + '\n');
const counts = {};
for (const c of cases) counts[c.kind] = (counts[c.kind] || 0) + 1;
console.log(cases.length + ' cases', JSON.stringify(counts));
