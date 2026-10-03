package io.mq.engine;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import io.mq.core.model.Model;
import io.mq.script.Params;
import io.mq.script.RequestInfo;
import io.mq.script.Response;
import io.mq.script.ScriptLoader;
import io.mq.script.Steps;

/** Runs a {@code <Script>} step: gives the script params, steps, response and request, and returns what it wrote into response. */
public final class ScriptHandler implements Engine.StepHandler {

    private final Supplier<ScriptLoader> loader;

    /** @param loader asked on every call, so the Dev server can swap its loader */
    public ScriptHandler(Supplier<ScriptLoader> loader) {
        this.loader = loader;
    }

    @Override
    public Object run(Model.Step step, Context ctx) throws Exception {
        Model.Script s = (Model.Script) step;
        String name = s.file().endsWith(".kts") ? s.file().substring(0, s.file().length() - 4) : s.file();
        ScriptLoader l = loader.get();
        if (l == null || !l.has(name)) {
            throw new MqException(500, "script '" + name + "' (line " + s.line() + ") is not available");
        }
        Map<String, String> text = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : ctx.params.entrySet()) {
            if (e.getValue() != null) {
                text.put(e.getKey(), e.getValue() instanceof Map<?, ?> || e.getValue() instanceof java.util.List<?> ? Json.write(e.getValue())
                        : String.valueOf(e.getValue()));
            }
        }
        Response response = new Response();
        l.run(name, new Params(text), new Steps(ctx.results), response,
                new RequestInfo((String) idOf(ctx, "id"), (String) idOf(ctx, "pid"), (String) idOf(ctx, "uid"), ctx.method));
        return response;
    }

    private static Object idOf(Context ctx, String key) {
        Object v = ctx.params.get(key);
        return v == null ? null : String.valueOf(v);
    }
}
