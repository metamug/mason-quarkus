package io.mq.engine;

import java.util.LinkedHashMap;
import java.util.Map;

import io.mq.core.expr.Expr;

/**
 * The state of one request: its parameters and the results of the steps run so far.
 * Parameters come from the path ({@code id}, {@code pid}), the query string and the body; values from a JSON body keep their JSON type.
 */
public final class Context implements Expr.Env {

    public final String method;
    public final Map<String, Object> params = new LinkedHashMap<>();
    /** step id -> result: a list of rows for a query, a map for an update, script and XRequest */
    public final Map<String, Object> results = new LinkedHashMap<>();
    public final Map<String, String> headers = new LinkedHashMap<>();

    public Context(String method) {
        this.method = method;
    }

    @Override
    public Object lookup(Expr.Ref ref) {
        Object root = ref.mpath() ? results.get(ref.name()) : params.get(ref.name());
        return ref.path().isEmpty() ? root : Expr.navigate(root, ref.path());
    }
}
