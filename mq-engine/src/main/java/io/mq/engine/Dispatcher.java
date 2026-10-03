package io.mq.engine;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import io.mq.core.reload.ResourceSet;

/**
 * The HTTP-independent front of MQ: method, path, query and body in, {@link Reply} out. The Quarkus extension, the tests and the CLI all
 * call this. It keeps the {@link Routes} of the current snapshot, so a reload shows up on the next request.
 */
public final class Dispatcher {

    private final Supplier<ResourceSet> resources;
    private final Engine engine;
    private volatile Routes routes;
    private volatile long routesGeneration = -1;

    public Dispatcher(Supplier<ResourceSet> resources, Engine engine) {
        this.resources = resources;
        this.engine = engine;
    }

    public Engine engine() {
        return engine;
    }

    /**
     * @param path path without the query string, such as {@code /v1.0/order/7}
     * @param query raw query string without the '?', may be null
     * @param body request body, may be null
     * @param contentType content type of the body, may be null
     */
    public Reply handle(String method, String path, String query, String body, String contentType) {
        ResourceSet set = resources.get();
        Routes r = routes;
        if (r == null || routesGeneration != set.generation()) {
            r = new Routes(set);
            routes = r;
            routesGeneration = set.generation();
        }
        try {
            Routes.Match m = r.match(method, path);
            if (m == null) {
                return new Reply(404, Map.of(), Map.of("message", "no resource at " + path));
            }
            Context ctx = new Context(method);
            ctx.params.putAll(queryParams(query));
            ctx.params.putAll(bodyParams(body, contentType));
            return engine.handle(m, ctx);
        } catch (MqException e) {
            return new Reply(e.status, Map.of(), Map.of("message", e.getMessage()));
        }
    }

    static Map<String, Object> queryParams(String query) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) {
            return out;
        }
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String k = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String v = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            out.putIfAbsent(k, v);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> bodyParams(String body, String contentType) {
        if (body == null || body.isBlank()) {
            return Map.of();
        }
        String ct = contentType == null ? "" : contentType.toLowerCase();
        if (ct.contains("x-www-form-urlencoded")) {
            return queryParams(body);
        }
        try {
            Object v = Json.parse(body);
            if (v instanceof Map<?, ?> m) {
                return (Map<String, Object>) m;
            }
            throw new MqException(400, "the request body must be a JSON object");
        } catch (IllegalArgumentException e) {
            throw new MqException(400, "the request body is not valid JSON: " + e.getMessage());
        }
    }

    public static List<String> supportedMethods() {
        return List.of("GET", "HEAD", "POST", "PUT", "DELETE");
    }
}
