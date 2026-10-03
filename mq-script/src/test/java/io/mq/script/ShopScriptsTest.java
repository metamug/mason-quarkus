package io.mq.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.mq.core.reload.ResourceStore;
import io.mq.engine.Dispatcher;
import io.mq.engine.Engine;
import io.mq.engine.Reply;
import io.mq.engine.ScriptHandler;

/**
 * Script -> mpath -> Sql chains of R2's shop scenario, with the scripts evaluated by the Dev server's loader (Kotlin on the JVM).
 * The same checks run over HTTP against the compiled scripts (JVM and native) in the shop-conformance workflow.
 */
class ShopScriptsTest {

    private static final Path SAMPLE = Path.of(System.getProperty("mq.sample", "../samples/shop"));

    private Dispatcher mq;
    private DataSource ds;

    @BeforeEach
    void setUp() throws Exception {
        org.hsqldb.jdbc.JDBCDataSource h = new org.hsqldb.jdbc.JDBCDataSource();
        h.setUrl("jdbc:hsqldb:mem:shopkt" + System.nanoTime());
        h.setUser("SA");
        h.setPassword("");
        ds = h;
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            for (String q : Files.readString(SAMPLE.resolve("schema.sql")).split(";\\s*\\n")) {
                if (!q.isBlank()) {
                    st.execute(q);
                }
            }
        }
        ResourceStore store = new ResourceStore();
        var set = store.reload(SAMPLE.resolve("mq"));
        assertTrue(set.problems().isEmpty(), "the shop sample must validate: " + set.problemSummary());
        ScriptLoader loader = new DevScriptLoader(SAMPLE.resolve("scripts"));
        mq = new Dispatcher(store::current, new Engine(name -> ds, new ScriptHandler(() -> loader), null, null));
    }

    private Reply call(String method, String path, String query, String body) {
        return mq.handle(method, path, query, body, body == null ? null : "application/json");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Reply r, String key) {
        return (Map<String, Object>) ((Map<String, Object>) r.body()).get(key);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Reply r, String key) {
        return (List<Map<String, Object>>) ((Map<String, Object>) r.body()).get(key);
    }

    private static Object col(Map<String, Object> row, String name) {
        return row.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name)).map(Map.Entry::getValue).findFirst().orElse(null);
    }

    private String scalar(String sql) throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement(); var rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    @Test
    void registrationHashesInAScriptAndFeedsTheHashToSqlThroughMpath() throws Exception {
        Reply reg = call("POST", "/v1.0/customer", null, "{\"name\":\"Ada\",\"email\":\"ada@example.com\",\"password\":\"secret\"}");
        assertEquals(201, reg.status(), reg.json());
        assertEquals("ada@example.com", col(rows(reg, "created").get(0), "email"));
        assertTrue(!reg.json().contains("secret"), "the password is not echoed");
        String stored = scalar("SELECT pw_hash FROM customer WHERE email = 'ada@example.com'");
        assertTrue(stored.matches("[0-9a-f]{8}\\$[0-9a-f]{64}"), "salted sha-256 stored, not the password: " + stored);

        Reply dup = call("POST", "/v1.0/customer", null, "{\"name\":\"Ada2\",\"email\":\"ada@example.com\",\"password\":\"x\"}");
        assertTrue(dup.status() >= 400, "duplicate email is rejected: " + dup.json());

        call("POST", "/v1.0/customer", null, "{\"name\":\"Grace\",\"email\":\"grace@example.com\",\"password\":\"p2\"}");
        call("POST", "/v1.0/customer", null, "{\"name\":\"Linus\",\"email\":\"linus@example.com\",\"password\":\"p3\"}");
        assertEquals(3, rows(call("GET", "/v1.0/customer", null, null), "all").size());
        assertEquals(2, rows(call("GET", "/v1.0/customer", "q=recent", null), "recent").size());
        assertEquals(1, rows(call("GET", "/v1.0/customer/1", null, null), "one").size());
        assertEquals(0, rows(call("GET", "/v1.0/customer/999", null, null), "one").size());
    }

    @Test
    void oneScriptServesAnItemRequestAndACollectionRequest() {
        Reply item = call("GET", "/v1.0/token/hello", null, null);
        Map<String, Object> d = map(item, "digest");
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", d.get("sha256"));
        assertEquals("5d41402abc4b2a76b9719d911017c592", d.get("md5"));
        assertEquals("aGVsbG8=", d.get("base64"));
        assertEquals(d.get("uuid"), map(call("GET", "/v1.0/token/hello", null, null), "digest").get("uuid"), "deterministic");

        Reply many = call("GET", "/v1.0/token", "count=5", null);
        @SuppressWarnings("unchecked")
        List<String> items = (List<String>) map(many, "many").get("items");
        assertEquals(5, items.stream().distinct().count());
    }

    @Test
    void theKotlinRewriteOfTheGroovyScriptSeesItsParameter() {
        Reply r = call("GET", "/v1.0/legacy", "who=R2", null);
        assertEquals("Hello R2", map(r, "g").get("message"));
        assertEquals(2L, ((Number) map(r, "g").get("length")).longValue());
    }

    @Test
    void stateMachineScriptDecidesWhetherTheUpdateRuns() throws Exception {
        call("POST", "/v1.0/customer", null, "{\"name\":\"Ada\",\"email\":\"ada@example.com\",\"password\":\"s\"}");
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO orders (customer_id, total, status, token) VALUES (1, 13.5, 'NEW', 't')");
        }
        Reply ship = call("PUT", "/v1.0/order/1", null, "{\"status\":\"SHIPPED\"}");
        assertTrue(ship.json().contains("cannot go from NEW to SHIPPED"), ship.json());
        assertEquals("NEW", scalar("SELECT status FROM orders WHERE id = 1"), "the update step was skipped by when");
        Reply pay = call("PUT", "/v1.0/order/1", null, "{\"status\":\"PAID\"}");
        assertEquals(202, pay.status(), pay.json());
        assertEquals("PAID", scalar("SELECT status FROM orders WHERE id = 1"));
    }

    @Test
    void aChangedScriptIsPickedUpWithoutRestart() throws Exception {
        Path dir = Files.createTempDirectory("mqscripts");
        Files.writeString(dir.resolve("hello.kts"), "response[\"v\"] = 1\n");
        DevScriptLoader l = new DevScriptLoader(dir);
        Response r1 = new Response();
        l.run("hello", new Params(Map.of()), new Steps(Map.of()), r1, new RequestInfo(null, null, null, "GET"));
        assertEquals(1L, ((Number) r1.get("v")).longValue());
        Files.writeString(dir.resolve("hello.kts"), "response[\"v\"] = 2\n");
        Files.setLastModifiedTime(dir.resolve("hello.kts"), java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000));
        Response r2 = new Response();
        l.run("hello", new Params(Map.of()), new Steps(Map.of()), r2, new RequestInfo(null, null, null, "GET"));
        assertEquals(2L, ((Number) r2.get("v")).longValue());
        Files.writeString(dir.resolve("hello.kts"), "response[\"v\" = oops\n");
        Files.setLastModifiedTime(dir.resolve("hello.kts"), java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 10000));
        RuntimeException e = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> l.run("hello", new Params(Map.of()), new Steps(Map.of()), new Response(), new RequestInfo(null, null, null, "GET")));
        assertTrue(e.getMessage().contains("hello.kts:1"), "compile error names file and line: " + e.getMessage());
    }
}
