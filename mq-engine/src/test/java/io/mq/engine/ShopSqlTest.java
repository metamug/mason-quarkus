package io.mq.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.mq.core.reload.ResourceStore;

/**
 * Phase 3 gate: item read, item-plus-joins read and order POST work end to end with typed values, driven only by HTTP-style calls
 * (method, path, query, JSON body) against the sample project in samples/shop-sql. Runs on in-memory HSQLDB; with
 * MQ_TEST_PG_URL (jdbc:postgresql://host:5432/db), MQ_TEST_PG_USER and MQ_TEST_PG_PASSWORD set it runs on PostgreSQL as well.
 */
class ShopSqlTest {

    private static final Path SAMPLE = Path.of(System.getProperty("mq.sample", "../samples/shop-sql"));

    private Dispatcher mq;
    private DataSource ds;

    @BeforeEach
    void setUp() throws Exception {
        String pg = System.getenv("MQ_TEST_PG_URL");
        boolean usePg = pg != null && !pg.isEmpty();
        if (usePg) {
            org.postgresql.ds.PGSimpleDataSource p = new org.postgresql.ds.PGSimpleDataSource();
            p.setUrl(pg);
            p.setUser(System.getenv("MQ_TEST_PG_USER"));
            p.setPassword(System.getenv("MQ_TEST_PG_PASSWORD"));
            ds = p;
            try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
                st.execute("DROP TABLE IF EXISTS order_line, orders, product, customer");
            }
        } else {
            org.hsqldb.jdbc.JDBCDataSource h = new org.hsqldb.jdbc.JDBCDataSource();
            h.setUrl("jdbc:hsqldb:mem:shop" + System.nanoTime());
            h.setUser("SA");
            h.setPassword("");
            ds = h;
        }
        String schema = Files.readString(SAMPLE.resolve(usePg ? "schema.postgresql.sql" : "schema.sql"));
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            for (String q : schema.split(";\\s*\\n")) {
                if (!q.isBlank()) {
                    st.execute(q);
                }
            }
        }
        ResourceStore store = new ResourceStore();
        var set = store.reload(SAMPLE.resolve("mq"));
        assertTrue(set.problems().isEmpty(), "the sample must validate: " + set.problemSummary());
        mq = new Dispatcher(store::current, new Engine(name -> ds));
    }

    // ------------------------------------------------------------ helpers

    private Reply call(String method, String path, String query, String body) {
        return mq.handle(method, path, query, body, body == null ? null : "application/json");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Reply r, String step) {
        return (List<Map<String, Object>>) ((Map<String, Object>) r.body()).get(step);
    }

    /** column value regardless of the case the database reports (HSQLDB upper-cases) */
    private static Object col(Map<String, Object> row, String name) {
        return row.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name)).map(Map.Entry::getValue).findFirst().orElse(null);
    }

    private String scalar(String sql) throws SQLException {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement(); var rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private void register() {
        for (String[] c : new String[][] { { "Ada", "ada@example.com" }, { "Grace", "grace@example.com" }, { "Linus", "linus@example.com" } }) {
            Reply r = call("POST", "/v1.0/customer", null, "{\"name\":\"" + c[0] + "\",\"email\":\"" + c[1] + "\"}");
            assertEquals(201, r.status(), r.json());
        }
    }

    // ------------------------------------------------------------ customers

    @Test
    void customers() {
        Reply ada = call("POST", "/v1.0/customer", null, "{\"name\":\"Ada\",\"email\":\"ada@example.com\"}");
        assertEquals(201, ada.status(), "the declared status is returned");
        assertEquals("ada@example.com", col(rows(ada, "created").get(0), "email"));
        assertTrue(!((Map<?, ?>) ada.body()).containsKey("ins"), "output=false hides the insert");

        assertEquals(409, call("POST", "/v1.0/customer", null, "{\"name\":\"Ada2\",\"email\":\"ada@example.com\"}").status(), "duplicate email");
        Reply bad = call("POST", "/v1.0/customer", null, "{\"name\":\"X\",\"email\":\"not-an-email\"}");
        assertEquals(400, bad.status());
        assertTrue(bad.json().contains("email"), bad.json());
        assertEquals(400, call("POST", "/v1.0/customer", null, "{\"email\":\"x@example.com\"}").status(), "name is required");

        call("POST", "/v1.0/customer", null, "{\"name\":\"Grace\",\"email\":\"grace@example.com\"}");
        call("POST", "/v1.0/customer", null, "{\"name\":\"Linus\",\"email\":\"linus@example.com\"}");
        Reply all = call("GET", "/v1.0/customer", null, null);
        assertEquals(3, rows(all, "all").size());
        assertTrue(!((Map<?, ?>) all.body()).containsKey("recent"), "when skipped the other branch");
        Reply recent = call("GET", "/v1.0/customer", "q=recent", null);
        assertEquals(2, rows(recent, "recent").size());
        assertTrue(!((Map<?, ?>) recent.body()).containsKey("all"));
    }

    // ------------------------------------------------------------ item read, typed binding

    @Test
    void itemReadBindsThePathValueAsAnInteger() {
        register();
        Reply one = call("GET", "/v1.0/customer/1", null, null);
        assertEquals(200, one.status());
        assertEquals(1, rows(one, "one").size());
        assertEquals("ada@example.com", col(rows(one, "one").get(0), "email"));
        assertEquals(0, rows(call("GET", "/v1.0/customer/999", null, null), "one").size(), "a missing item is an empty result");
        Reply text = call("GET", "/v1.0/customer/abc", null, null);
        assertEquals(400, text.status(), "text where the database expects an integer is a 400, not a 500");
        assertTrue(text.json().contains("whole number"), text.json());
        assertEquals(404, call("GET", "/v1.0/nothing", null, null).status());
        assertEquals(405, call("DELETE", "/v1.0/customer", null, null).status());
    }

    @Test
    void likeWithAVariableInsideTheQuotes() {
        Reply r = call("GET", "/v1.0/product", "name=Note", null);
        assertEquals(1, rows(r, "found").size(), r.json());
        assertEquals(2, rows(call("GET", "/v1.0/product", null, null), "all").size());
        Reply one = call("GET", "/v1.0/product/1", null, null);
        assertEquals(0, new java.math.BigDecimal("4.50").compareTo(new java.math.BigDecimal(String.valueOf(col(rows(one, "one").get(0), "price")))));
    }

    // ------------------------------------------------------------ orders

    @Test
    void orderPostReadsJoinsAndStateChange() throws Exception {
        register();
        Reply o1 = call("POST", "/v1.0/order", null, "{\"customer_id\":1,\"product_id\":1,\"qty\":3}");
        assertEquals(201, o1.status(), o1.json());
        Map<String, Object> placed = rows(o1, "placed").get(0);
        assertEquals("Ada", col(placed, "customer"));
        assertEquals("NEW", col(placed, "status"));
        assertEquals(0, new java.math.BigDecimal("13.5").compareTo(new java.math.BigDecimal(String.valueOf(col(placed, "total")))), "3 x 4.50");
        assertEquals("97", scalar("SELECT stock FROM product WHERE id = 1"), "the transaction decremented the stock");
        assertEquals("1", scalar("SELECT COUNT(*) FROM order_line"));

        // the same values as strings, the way a form or query string delivers them
        Reply o2 = mq.handle("POST", "/v1.0/order", null, "customer_id=2&product_id=2&qty=10", "application/x-www-form-urlencoded");
        assertEquals(201, o2.status(), o2.json());
        assertEquals("490", scalar("SELECT stock FROM product WHERE id = 2"));

        Reply tooMany = call("POST", "/v1.0/order", null, "{\"customer_id\":1,\"product_id\":1,\"qty\":1000}");
        assertTrue(tooMany.json().contains("insufficient stock"), tooMany.json());
        assertTrue(!tooMany.json().contains("placed"), "no order is placed");
        assertEquals("97", scalar("SELECT stock FROM product WHERE id = 1"), "stock is unchanged");
        assertTrue(call("POST", "/v1.0/order", null, "{\"customer_id\":1,\"product_id\":999,\"qty\":1}").json().contains("unknown product"));
        assertEquals(400, call("POST", "/v1.0/order", null, "{\"customer_id\":1,\"product_id\":1,\"qty\":0}").status(), "qty below the declared min");
        assertEquals(400, call("POST", "/v1.0/order", null, "{\"customer_id\":1,\"product_id\":1}").status(), "qty is required");

        Reply item = call("GET", "/v1.0/order/1", null, null);
        assertEquals("Ada", col(rows(item, "order").get(0), "customer"));
        assertEquals(1, rows(item, "lines").size());
        assertEquals("Notebook", col(rows(item, "lines").get(0), "name"));

        assertEquals(1, rows(call("GET", "/v1.0/order", "customer_id=1", null), "byCustomer").size());
        Reply byStatus = call("GET", "/v1.0/order", "status=PAID", null);
        assertEquals(0, rows(byStatus, "byStatus").size());
        assertTrue(!((Map<?, ?>) byStatus.body()).containsKey("all"));
        assertEquals(2, rows(call("GET", "/v1.0/order", null, null), "all").size());

        Reply pay = call("PUT", "/v1.0/order/1", null, "{\"status\":\"PAID\"}");
        assertEquals(202, pay.status());
        assertEquals("PAID", col(rows(call("GET", "/v1.0/order/1", null, null), "order").get(0), "status"));
    }

    // ------------------------------------------------------------ failure

    @Test
    void failingStatementRollsBackTheTransaction() throws Exception {
        register();
        Reply r = call("POST", "/v1.0/txtest", null, "{\"customer_id\":1}");
        assertEquals(409, r.status(), r.json());
        assertEquals("0", scalar("SELECT COUNT(*) FROM orders WHERE status = 'TX'"), "the first statement was rolled back");
        String id = String.valueOf(((Map<?, ?>) r.body()).get("errorId"));
        String detail = mq.engine().errorDetail(id);
        assertNotNull(detail);
        assertTrue(detail.toLowerCase().contains("null") || detail.toLowerCase().contains("constraint"), detail);
        assertTrue(!r.json().contains("INSERT"), "the response does not leak SQL");
    }
}
