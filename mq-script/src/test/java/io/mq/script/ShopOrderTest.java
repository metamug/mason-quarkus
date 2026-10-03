package io.mq.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.mq.core.reload.ResourceStore;
import io.mq.engine.Dispatcher;
import io.mq.engine.Engine;
import io.mq.engine.Reply;
import io.mq.engine.ScriptHandler;
import io.mq.engine.XRequestHandler;

/**
 * The order scenario of R2's shop: XRequest (a product lookup, answered by MQ itself) then a Kotlin script that reads
 * {@code steps["prod"]}, then a Transaction guarded by {@code $[calc].ok eq true}, then an XRequest notification to an external API
 * (a stub), then a joined Sql. Chained mpath through all four step kinds.
 */
class ShopOrderTest {

    private static final Path SAMPLE = Path.of(System.getProperty("mq.sample", "../samples/shop"));

    private Dispatcher mq;
    private DataSource ds;
    private HttpServer self;
    private HttpServer stub;
    private final List<String> notifications = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        org.hsqldb.jdbc.JDBCDataSource h = new org.hsqldb.jdbc.JDBCDataSource();
        h.setUrl("jdbc:hsqldb:mem:shoporder" + System.nanoTime());
        h.setUser("SA");
        h.setPassword("");
        ds = h;
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            for (String q : Files.readString(SAMPLE.resolve("schema.sql")).split(";\\s*\\n")) {
                if (!q.isBlank()) {
                    st.execute(q);
                }
            }
            st.execute("INSERT INTO customer (name, email, pw_hash) VALUES ('Ada', 'ada@example.com', 'x')");
        }
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/", x -> {
            String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (x.getRequestURI().getPath().equals("/status/500")) {
                reply(x, 500, "{\"error\":\"boom\"}");
            } else {
                notifications.add(x.getRequestMethod() + " " + x.getRequestURI().getPath() + " " + body);
                reply(x, 200, "{\"ok\":true}");
            }
        });
        stub.start();

        ResourceStore store = new ResourceStore();
        var set = store.reload(SAMPLE.resolve("mq"));
        assertTrue(set.problems().isEmpty(), set.problemSummary());
        ScriptLoader loader = new DevScriptLoader(SAMPLE.resolve("scripts"));
        self = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        XRequestHandler xr = new XRequestHandler(Map.of(
                "self", "http://127.0.0.1:" + self.getAddress().getPort(), "stub", "http://127.0.0.1:" + stub.getAddress().getPort()),
                Duration.ofSeconds(5));
        mq = new Dispatcher(store::current, new Engine(name -> ds, new ScriptHandler(() -> loader), xr, null));
        self.createContext("/", x -> {
            String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Reply r = mq.handle(x.getRequestMethod(), x.getRequestURI().getPath(), x.getRequestURI().getRawQuery(), body, x.getRequestHeaders().getFirst("Content-Type"));
            reply(x, r.status(), r.json());
        });
        self.start();
    }

    @AfterEach
    void tearDown() {
        self.stop(0);
        stub.stop(0);
    }

    private static void reply(HttpExchange x, int status, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().add("Content-Type", "application/json");
        x.sendResponseHeaders(status, b.length);
        x.getResponseBody().write(b);
        x.close();
    }

    private Reply call(String method, String path, String body) {
        return mq.handle(method, path, null, body, body == null ? null : "application/json");
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
    void placeOrderChainsXRequestScriptTransactionXRequestAndSql() throws Exception {
        Reply o = call("POST", "/v1.0/order", "{\"customer_id\":\"1\",\"product_id\":\"1\",\"qty\":\"3\"}");
        assertEquals(201, o.status(), o.json());
        assertEquals(0, new java.math.BigDecimal("13.5").compareTo(new java.math.BigDecimal(String.valueOf(map(o, "calc").get("total")))),
                "the script priced the order from the XRequest product (3 x 4.50)");
        String token = String.valueOf(map(o, "calc").get("token"));
        assertTrue(token.matches("[0-9a-f]{40}"), token);
        assertEquals("Ada", col(rows(o, "placed").get(0), "customer"));
        assertEquals("NEW", col(rows(o, "placed").get(0), "status"));
        assertEquals("97", scalar("SELECT stock FROM product WHERE id = 1"), "the transaction decremented the stock");
        assertEquals("1", scalar("SELECT COUNT(*) FROM order_line"));
        assertEquals(1, notifications.size(), "the notification reached the external API");
        assertTrue(notifications.get(0).contains(token), "...with the script's token: " + notifications.get(0));
        assertTrue(notifications.get(0).startsWith("POST /post"), notifications.get(0));
    }

    @Test
    void theScriptRejectsAndTheGuardedStepsDoNotRun() throws Exception {
        Reply bad = call("POST", "/v1.0/order", "{\"customer_id\":\"1\",\"product_id\":\"1\",\"qty\":\"1000\"}");
        assertTrue(bad.json().contains("insufficient stock"), bad.json());
        assertTrue(!bad.json().contains("\"placed\""), bad.json());
        assertEquals("100", scalar("SELECT stock FROM product WHERE id = 1"));
        assertEquals(0, notifications.size(), "no notification when the order was rejected");
        assertTrue(call("POST", "/v1.0/order", "{\"customer_id\":\"1\",\"product_id\":\"999\",\"qty\":\"1\"}").json().contains("unknown product"));
    }

    @Test
    void anExternalApiFailureDoesNotHangTheRequest() {
        long t0 = System.nanoTime();
        Reply r = call("GET", "/v1.0/xfail", null);
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 10_000);
        assertEquals(200, r.status(), r.json());
        assertEquals(2, ((Number) col(rows(r, "after").get(0), "n")).intValue(), "the Sql step after the failed XRequest still ran");
    }
}
