package io.mq.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.mq.core.reload.ResourceStore;

/** XRequest against a local stub server: the $[x].body syntax, output modes, params, bodies, failures. */
class XRequestTest {

    private HttpServer stub;
    private int port;
    private final List<String> seen = new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeEach
    void startStub() throws IOException {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/", this::echo);
        stub.start();
        port = stub.getAddress().getPort();
    }

    @AfterEach
    void stopStub() {
        stub.stop(0);
    }

    private void echo(HttpExchange x) throws IOException {
        String path = x.getRequestURI().getPath();
        String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        seen.add(x.getRequestMethod() + " " + x.getRequestURI() + " " + body);
        Map<String, Object> out = new LinkedHashMap<>();
        int status = 200;
        String json;
        if (path.equals("/status/500")) {
            status = 500;
            json = "{\"error\":\"boom\"}";
        } else if (path.equals("/list")) {
            json = "[{\"name\":\"first\"},{\"name\":\"second\"}]";
        } else if (path.equals("/text")) {
            byte[] b = "plain words".getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().add("Content-Type", "text/plain");
            x.sendResponseHeaders(200, b.length);
            x.getResponseBody().write(b);
            x.close();
            return;
        } else {
            Map<String, Object> args = new LinkedHashMap<>();
            String q = x.getRequestURI().getRawQuery();
            if (q != null) {
                for (String pair : q.split("&")) {
                    String[] kv = pair.split("=", 2);
                    args.put(java.net.URLDecoder.decode(kv[0], StandardCharsets.UTF_8), java.net.URLDecoder.decode(kv.length > 1 ? kv[1] : "", StandardCharsets.UTF_8));
                }
            }
            out.put("method", x.getRequestMethod());
            out.put("args", args);
            out.put("accept", x.getRequestHeaders().getFirst("Accept"));
            out.put("contentType", x.getRequestHeaders().getFirst("Content-Type"));
            out.put("raw", body);
            out.put("token", "abc123");
            json = Json.write(out);
        }
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().add("Content-Type", "application/json");
        x.getResponseHeaders().add("X-Stub", "yes");
        x.sendResponseHeaders(status, b.length);
        x.getResponseBody().write(b);
        x.close();
    }

    private Dispatcher mq(Path dir, String request) throws IOException {
        Files.writeString(dir.resolve("api.xml"), "<Resource xmlns=\"http://xml.metamug.net/resource/1.0\" v=\"1.0\">\n" + request + "\n</Resource>");
        ResourceStore store = new ResourceStore();
        var set = store.reload(dir);
        assertTrue(set.problems().isEmpty(), set.problemSummary());
        XRequestHandler h = new XRequestHandler(Map.of("stub", "http://127.0.0.1:" + port), Duration.ofSeconds(5));
        return new Dispatcher(store::current, new Engine(name -> null, null, h, null));
    }

    private static Reply call(Dispatcher d, String method, String query, String body) {
        return d.handle(method, "/v1.0/api", query, body, body == null ? null : "application/json");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(Reply r) {
        return (Map<String, Object>) r.body();
    }

    @Test
    void outputTrueShowsThePayloadAndMpathReadsItBothWays(@TempDir Path dir) throws IOException {
        Dispatcher d = mq(dir, """
                <Request method="GET">
                  <XRequest id="x" method="GET" url="{{stub}}/get" output="true">
                    <Header name="Accept" value="application/json"/>
                    <Param name="foo1" value="Hello"/>
                    <Param name="foo2" value="$name"/>
                  </XRequest>
                  <Text id="direct">$[x].args.foo1</Text>
                  <Text id="viaBody">$[x].body.args.foo2</Text>
                  <Text id="status">$[x].statusCode</Text>
                </Request>""");
        Reply r = call(d, "GET", "name=World+Wide", null);
        assertEquals(200, r.status(), r.json());
        Map<String, Object> x = (Map<String, Object>) body(r).get("x");
        assertEquals("application/json", x.get("accept"), "the Header was sent");
        assertEquals("Hello", ((Map<?, ?>) x.get("args")).get("foo1"), "the Param went into the query string");
        assertEquals("World Wide", ((Map<?, ?>) x.get("args")).get("foo2"), "a request parameter in a Param value, url-encoded on the way");
        assertEquals("Hello", body(r).get("direct"), "$[x].args.foo1");
        assertEquals("World Wide", body(r).get("viaBody"), "$[x].body.args.foo2");
        assertEquals("200", body(r).get("status"));
    }

    @Test
    void outputFalseHidesItAndHeadersAddsHeadersBodyAndStatus(@TempDir Path dir) throws IOException {
        Dispatcher d = mq(dir, """
                <Request method="GET">
                  <XRequest id="hidden" method="GET" url="{{stub}}/get" output="false"/>
                  <XRequest id="default" method="GET" url="{{stub}}/get"/>
                  <XRequest id="full" method="GET" url="{{stub}}/get" output="headers"/>
                  <Text id="seen" output="true">$[hidden].token</Text>
                </Request>""");
        Map<String, Object> b = body(call(d, "GET", null, null));
        assertTrue(!b.containsKey("hidden") && !b.containsKey("default"), "output is off by default: " + b.keySet());
        assertEquals("abc123", b.get("seen"), "a hidden step is still available to mpath");
        Map<?, ?> full = (Map<?, ?>) b.get("full");
        assertEquals(200, full.get("statusCode"));
        assertEquals("yes", ((Map<?, ?>) full.get("headers")).entrySet().stream().filter(e -> e.getKey().toString().equalsIgnoreCase("x-stub"))
                .map(Map.Entry::getValue).findFirst().orElse(null));
        assertEquals("abc123", ((Map<?, ?>) full.get("body")).get("token"));
    }

    @Test
    void postBodyUsesRequestParametersAndEarlierResults(@TempDir Path dir) throws IOException {
        Dispatcher d = mq(dir, """
                <Request method="POST">
                  <XRequest id="a" method="GET" url="{{stub}}/get"/>
                  <XRequest id="b" method="POST" url="{{stub}}/post" output="true">
                    <Header name="Content-Type" value="application/json"/>
                    <Body>{"title": "$title", "token": "$[a].token", "n": $n}</Body>
                  </XRequest>
                </Request>""");
        Reply r = d.handle("POST", "/v1.0/api", null, "{\"title\":\"Hello\",\"n\":7}", "application/json");
        Map<?, ?> b = (Map<?, ?>) body(r).get("b");
        assertEquals("POST", b.get("method"));
        assertEquals("{\"title\": \"Hello\", \"token\": \"abc123\", \"n\": 7}", b.get("raw"));
    }

    @Test
    void paramsBecomeAFormBodyWhenTheContentTypeIsUrlencoded(@TempDir Path dir) throws IOException {
        Dispatcher d = mq(dir, """
                <Request method="POST">
                  <XRequest id="f" method="POST" url="{{stub}}/form" output="true">
                    <Header name="Content-Type" value="application/x-www-form-urlencoded"/>
                    <Param name="movie" value="The Godfather"/>
                    <Param name="rating" value="4"/>
                  </XRequest>
                </Request>""");
        Map<?, ?> f = (Map<?, ?>) body(call(d, "POST", null, "{}")).get("f");
        assertEquals("movie=The+Godfather&rating=4", f.get("raw"));
        assertTrue(f.get("contentType").toString().contains("x-www-form-urlencoded"));
    }

    @Test
    void anAnswerWithAnyStatusIsAResultThatLaterStepsCanBranchOn(@TempDir Path dir) throws IOException {
        Dispatcher d = mq(dir, """
                <Request method="GET">
                  <XRequest id="ext" method="GET" url="{{stub}}/status/500"/>
                  <Text id="failed" when="$[ext].statusCode ge 500">the external API failed with $[ext].statusCode: $[ext].error</Text>
                  <Text id="fine" when="$[ext].statusCode lt 400">not shown</Text>
                </Request>""");
        Reply r = call(d, "GET", null, null);
        assertEquals(200, r.status());
        assertEquals("the external API failed with 500: boom", body(r).get("failed"));
        assertTrue(!body(r).containsKey("fine"));
    }

    @Test
    void arraysAndTextBodies(@TempDir Path dir) throws IOException {
        Dispatcher d = mq(dir, """
                <Request method="GET">
                  <XRequest id="l" method="GET" url="{{stub}}/list" output="true"/>
                  <XRequest id="t" method="GET" url="{{stub}}/text" output="true"/>
                  <Text id="second">$[l][1].name</Text>
                  <Text id="text">$[t].body</Text>
                </Request>""");
        Map<String, Object> b = body(call(d, "GET", null, null));
        assertEquals("second", b.get("second"), "index into an array body");
        assertEquals(2, ((List<?>) b.get("l")).size());
        assertEquals("plain words", b.get("t"));
        assertEquals("plain words", b.get("text"));
    }

    @Test
    void failuresAreReportedWithoutHanging(@TempDir Path dir) throws IOException {
        Dispatcher d = mq(dir, """
                <Request method="GET">
                  <XRequest id="down" method="GET" url="http://127.0.0.1:1/never"/>
                </Request>""");
        long t0 = System.nanoTime();
        Reply r = call(d, "GET", null, null);
        assertEquals(502, r.status(), r.json());
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 5000, "a refused connection does not hang");

        Dispatcher d2 = mq(dir, """
                <Request method="GET">
                  <XRequest id="p" method="GET" url="{{nowhere}}/x"/>
                </Request>""");
        Reply r2 = call(d2, "GET", null, null);
        assertEquals(500, r2.status());
        assertTrue(d2.engine().errorDetail(String.valueOf(body(r2).get("errorId"))).contains("{{nowhere}}"), "the detail names the undefined property");
    }
}
