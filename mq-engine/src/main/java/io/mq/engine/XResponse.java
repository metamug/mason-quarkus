package io.mq.engine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.mq.core.expr.Expr;

/**
 * The result of an XRequest step. As a map it holds the fields of the response body when that is a JSON object, so
 * {@code $[x].store.book[0].title} and a script's {@code steps["x"]["one"]} read the payload directly. It also answers the names
 * {@code body}, {@code statusCode}/{@code status} and {@code headers} (R2 documents both forms: {@code $[x].body.args.foo1}), and an
 * index when the body is an array. A body field with one of those names wins over the alias.
 */
public final class XResponse extends LinkedHashMap<String, Object> implements Expr.Aliased {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final Map<String, String> headers;
    private final Object body;

    public XResponse(int status, Map<String, String> headers, Object body) {
        this.status = status;
        this.headers = headers;
        this.body = body;
        if (body instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                put(String.valueOf(e.getKey()), e.getValue());
            }
        }
    }

    public int status() {
        return status;
    }

    public Map<String, String> headers() {
        return headers;
    }

    public Object body() {
        return body;
    }

    /** the value of the step in the response when output is true: the payload itself (an object's fields, an array, or text) */
    public Object payload() {
        return body instanceof Map<?, ?> ? new LinkedHashMap<>(this) : body;
    }

    /** the value when output is "headers": headers, body and statusCode, as in R2's documentation */
    public Map<String, Object> withHeaders() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("headers", headers);
        out.put("body", body);
        out.put("statusCode", status);
        return out;
    }

    @Override
    public Object alias(String key) {
        switch (key) {
            case "body":
                return body;
            case "statusCode":
            case "status":
                return status;
            case "headers":
                return headers;
            default:
                if (key.startsWith("[") && body instanceof List<?> l) {
                    int i = Integer.parseInt(key.substring(1, key.length() - 1));
                    return i >= 0 && i < l.size() ? l.get(i) : null;
                }
                return null;
        }
    }
}
