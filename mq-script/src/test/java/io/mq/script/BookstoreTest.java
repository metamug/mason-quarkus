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
 * The "is it enough?" exercise: a small bookstore API written from scratch with only the artifacts the spec names (XML, a script,
 * a datasource), driven through HTTP-style calls. What was awkward is written down in docs/reports/bookstore-exercise.md.
 */
class BookstoreTest {

    private static final Path SAMPLE = Path.of(System.getProperty("mq.bookstore", "../samples/bookstore"));

    private Dispatcher mq;
    private DataSource ds;

    @BeforeEach
    void setUp() throws Exception {
        org.hsqldb.jdbc.JDBCDataSource h = new org.hsqldb.jdbc.JDBCDataSource();
        h.setUrl("jdbc:hsqldb:mem:books" + System.nanoTime());
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
        assertTrue(set.problems().isEmpty(), "the bookstore must validate: " + set.problemSummary());
        ScriptLoader loader = new DevScriptLoader(SAMPLE.resolve("scripts"));
        mq = new Dispatcher(store::current, new Engine(n -> ds, new ScriptHandler(() -> loader), null, null));
    }

    private Reply call(String method, String path, String query, String body) {
        return mq.handle(method, path, query, body, body == null ? null : "application/json");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Reply r, String k) {
        return (List<Map<String, Object>>) ((Map<String, Object>) r.body()).get(k);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Reply r, String k) {
        return (Map<String, Object>) ((Map<String, Object>) r.body()).get(k);
    }

    private static Object col(Map<String, Object> row, String name) {
        return row.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name)).map(Map.Entry::getValue).findFirst().orElse(null);
    }

    private void seed() {
        assertEquals(201, call("POST", "/v1.0/author", null, "{\"name\":\"Ursula Le Guin\"}").status());
        assertEquals(201, call("POST", "/v1.0/author", null, "{\"name\":\"Stanislaw Lem\"}").status());
        assertEquals(201, call("POST", "/v1.0/book", null, "{\"author_id\":1,\"title\":\"The Dispossessed\",\"isbn\":\"9780061054884\",\"price\":9.99,\"stock\":5}").status());
        assertEquals(201, call("POST", "/v1.0/book", null, "{\"author_id\":1,\"title\":\"A Wizard of Earthsea\",\"isbn\":\"9780547773742\",\"price\":8.5}").status());
        assertEquals(201, call("POST", "/v1.0/book", null, "{\"author_id\":2,\"title\":\"Solaris\",\"isbn\":\"9780156027601\",\"price\":11,\"stock\":2}").status());
    }

    @Test
    void createReadSearchPageUpdateDelete() {
        seed();
        assertEquals(3, rows(call("GET", "/v1.0/book", null, null), "all").size());
        assertEquals(1, rows(call("GET", "/v1.0/book", "q=solar", null), "search").size(), "search is case-insensitive");
        assertEquals(2, rows(call("GET", "/v1.0/book", "author_id=1", null), "byAuthor").size());
        Reply page = call("GET", "/v1.0/book", "size=2&from=1", null);
        assertEquals(2, rows(page, "all").size(), "limit and offset from the request");
        assertEquals("Solaris", col(rows(page, "all").get(0), "title"));

        Reply one = call("GET", "/v1.0/book/1", null, null);
        assertEquals("Ursula Le Guin", col(rows(one, "book").get(0), "author"));
        assertEquals(0, rows(one, "reviews").size());

        assertEquals(202, call("PUT", "/v1.0/book/1", null, "{\"price\":12.5}").status());
        assertEquals(0, new java.math.BigDecimal("12.5").compareTo(new java.math.BigDecimal(String.valueOf(col(rows(call("GET", "/v1.0/book/1", null, null), "book").get(0), "price")))));
        assertEquals(410, call("DELETE", "/v1.0/book/2", null, null).status());
        assertEquals(2, rows(call("GET", "/v1.0/book", null, null), "all").size());
    }

    @Test
    void validationAndConstraintsGiveClearStatuses() {
        seed();
        assertEquals(400, call("POST", "/v1.0/book", null, "{\"author_id\":1,\"title\":\"X\",\"isbn\":\"abc\",\"price\":1}").status(), "isbn pattern");
        assertEquals(400, call("POST", "/v1.0/book", null, "{\"author_id\":1,\"title\":\"X\",\"isbn\":\"1234567890\",\"price\":-1}").status(), "price min");
        assertEquals(409, call("POST", "/v1.0/book", null, "{\"author_id\":1,\"title\":\"Dup\",\"isbn\":\"9780061054884\",\"price\":1}").status(), "unique isbn");
        assertEquals(409, call("POST", "/v1.0/book", null, "{\"author_id\":99,\"title\":\"Orphan\",\"isbn\":\"1234567890\",\"price\":1}").status(), "unknown author");
    }

    @Test
    void reviewsLiveUnderTheirBookAndAScriptAveragesThem() {
        seed();
        assertEquals(201, call("POST", "/v1.0/book/1/review", null, "{\"rating\":5,\"comment\":\"great\"}").status());
        assertEquals(201, call("POST", "/v1.0/book/1/review", null, "{\"rating\":4}").status());
        assertEquals(400, call("POST", "/v1.0/book/1/review", null, "{\"rating\":6}").status(), "rating max");
        assertEquals(2, rows(call("GET", "/v1.0/book/1/review", null, null), "reviews").size());
        Reply one = call("GET", "/v1.0/book/1", null, null);
        assertEquals(4.5, ((Number) map(one, "rating").get("average")).doubleValue());
        assertEquals(2L, ((Number) map(one, "rating").get("count")).longValue());
    }
}
