package io.mq.engine;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.sql.DataSource;

import io.mq.core.expr.Expr;
import io.mq.core.model.Model;
import io.mq.plugin.Plugin;
import io.mq.plugin.PluginLoader;
import io.mq.plugin.PluginRequest;

/**
 * Runs an {@code <Execute classname="...">}: finds the plugin class by name among the declared plugins, builds its arguments from the
 * {@code <Arg>} children ({@code value} with variables replaced, {@code path} as the value an mpath finds, typed) and returns what it
 * returns.
 */
public final class ExecuteHandler implements Engine.StepHandler {

    private final Supplier<PluginLoader> loader;
    private final Function<String, DataSource> dataSources;

    public ExecuteHandler(Supplier<PluginLoader> loader, Function<String, DataSource> dataSources) {
        this.loader = loader;
        this.dataSources = dataSources;
    }

    @Override
    public Object run(Model.Step step, Context ctx) throws Exception {
        Model.Execute e = (Model.Execute) step;
        if (e.classname() == null) {
            throw new MqException(500, "Execute '" + e.id() + "' (line " + e.line() + ") has no classname");
        }
        PluginLoader l = loader.get();
        Plugin plugin = l == null ? null : l.plugins().get(e.classname());
        if (plugin == null) {
            throw new MqException(500, "Execute '" + e.id() + "' (line " + e.line() + "): the plugin class " + e.classname()
                    + " is not among the declared plugins");
        }
        Map<String, Object> args = new LinkedHashMap<>();
        for (Model.Arg a : e.args()) {
            if (a.path() != null) {
                Expr.Ref ref = Expr.readRef(a.path().trim(), 0);
                args.put(a.name(), ref == null ? null : ctx.lookup(ref));
            } else if (a.value() != null) {
                args.put(a.name(), Engine.render(a.value(), ctx));
            } else {
                args.put(a.name(), null);
            }
        }
        Map<String, String> text = new LinkedHashMap<>();
        ctx.params.forEach((k, v) -> {
            if (v != null) {
                text.put(k, String.valueOf(v));
            }
        });
        DataSource ds = dataSources.apply(null);
        PluginRequest request = new PluginRequest() {
            public Map<String, String> params() {
                return text;
            }

            public Map<String, Object> steps() {
                return ctx.results;
            }

            public String id() {
                return text.get("id");
            }

            public String pid() {
                return text.get("pid");
            }

            public DataSource dataSource() {
                return ds;
            }
        };
        Object result = plugin.process(request, args);
        checkJsonReady(result, e, "the result");
        return result;
    }

    /**
     * A plugin result must be JSON-ready: null, String, Number, Boolean, a List of those or a Map with String keys of those. Writing any
     * other object would need reflection, which a native binary does not have; a quiet toString() would hide the mistake.
     */
    static void checkJsonReady(Object v, Model.Execute e, String where) {
        if (v == null || v instanceof String || v instanceof Number || v instanceof Boolean) {
            return;
        }
        if (v instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> en : m.entrySet()) {
                if (!(en.getKey() instanceof String)) {
                    throw notJson(e, where + " has a map key of type " + en.getKey().getClass().getName() + " (keys must be String)");
                }
                checkJsonReady(en.getValue(), e, where + "." + en.getKey());
            }
            return;
        }
        if (v instanceof Iterable<?> it) {
            int i = 0;
            for (Object o : it) {
                checkJsonReady(o, e, where + "[" + i++ + "]");
            }
            return;
        }
        throw notJson(e, where + " is a " + v.getClass().getName());
    }

    private static MqException notJson(Model.Execute e, String what) {
        return new MqException(500, "Execute '" + e.id() + "' (line " + e.line() + "): the plugin " + e.classname()
                + " returned a value that is not JSON-ready: " + what + ". Return String, Number, Boolean, null, List or Map.");
    }
}
