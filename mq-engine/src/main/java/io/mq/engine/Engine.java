package io.mq.engine;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import javax.sql.DataSource;

import io.mq.core.expr.Expr;
import io.mq.core.model.Model;

/**
 * Runs one request of a resource: validates the declared parameters, then runs the steps in document order. Each step may carry a
 * {@code when} condition; its result is stored under its id for later steps (mpath) and, when it is an output step, added to the response.
 * Sql and Transaction are executed here. Script, XRequest and Execute are handed to {@link StepHandler}s (Phase 4).
 *
 * <p>Response: {@code {"<step id>": <result>, ...}} for the steps that output, with the request's {@code status} (200 by default).
 * A query's result is a list of rows; a Script's a map; a Text's a string. Failures answer {@code {"errorId":"n","message":"..."}}
 * and the details go to {@link #errorDetail(String)}.
 */
public final class Engine {

    /** runs a Script, XRequest or Execute step and returns its result (a map, list or scalar), or throws */
    public interface StepHandler {
        Object run(Model.Step step, Context ctx) throws Exception;
    }

    private final Function<String, DataSource> dataSources;
    private final StepHandler scripts;
    private final StepHandler xrequests;
    private final StepHandler executes;
    private final AtomicLong errorIds = new AtomicLong();
    private final Map<String, String> errors = new LinkedHashMap<>() {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> e) {
            return size() > 500;
        }
    };

    /** @param dataSources maps the datasource name of a step (null for the default one) to a DataSource */
    public Engine(Function<String, DataSource> dataSources, StepHandler scripts, StepHandler xrequests, StepHandler executes) {
        this.dataSources = dataSources;
        this.scripts = scripts != null ? scripts : unsupported("Script");
        this.xrequests = xrequests != null ? xrequests : unsupported("XRequest");
        this.executes = executes != null ? executes : unsupported("Execute");
    }

    public Engine(Function<String, DataSource> dataSources) {
        this(dataSources, null, null, null);
    }

    private static StepHandler unsupported(String what) {
        return (step, ctx) -> {
            throw new MqException(501, "<" + what + "> is not supported yet");
        };
    }

    public String errorDetail(String errorId) {
        synchronized (errors) {
            return errors.get(errorId);
        }
    }

    public Reply handle(Routes.Match m, Context ctx) {
        try {
            if (m.id() != null) {
                ctx.params.put("id", m.id());
            }
            if (m.pid() != null) {
                ctx.params.put("pid", m.pid());
            }
            Model.Request req = m.request();
            Inputs.apply(req.params(), ctx);
            for (Model.Header h : req.headers()) {
                ctx.headers.put(h.name(), h.value());
            }
            Map<String, Object> body = new LinkedHashMap<>();
            for (Model.Step step : req.steps()) {
                run(step, ctx, body);
            }
            return new Reply(req.status() != null ? req.status() : 200, Map.copyOf(ctx.headers), body);
        } catch (MqException e) {
            return fail(e.status, e.getMessage(), e);
        } catch (SQLException e) {
            return fail(sqlStatus(e), sqlStatus(e) == 409 ? "the database refused the change (constraint)" : "the request failed", e);
        } catch (Exception e) {
            return fail(500, "the request failed", e);
        }
    }

    /** 23xxx is an integrity constraint violation: the caller sent something the data does not allow */
    private static int sqlStatus(SQLException e) {
        for (SQLException x = e; x != null; x = x.getNextException()) {
            if (x.getSQLState() != null && x.getSQLState().startsWith("23")) {
                return 409;
            }
        }
        return 500;
    }

    private Reply fail(int status, String message, Exception cause) {
        String id = String.valueOf(errorIds.incrementAndGet());
        StringBuilder detail = new StringBuilder(cause.toString());
        for (Throwable t = cause.getCause(); t != null; t = t.getCause()) {
            detail.append(" <- ").append(t);
        }
        synchronized (errors) {
            errors.put(id, detail.toString());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("errorId", id);
        body.put("message", message);
        return new Reply(status, Map.of(), body);
    }

    // ------------------------------------------------------------ steps

    private void run(Model.Step step, Context ctx, Map<String, Object> body) throws Exception {
        if (!applies(step, ctx)) {
            return;
        }
        if (step instanceof Model.Sql s) {
            try (Connection con = connection(s.datasource())) {
                store(s, SqlRunner.run(con, s, ctx), ctx, body);
            } catch (SQLException e) {
                throw withMessage(s.onerror(), e);
            }
        } else if (step instanceof Model.Transaction t) {
            transaction(t, ctx, body);
        } else if (step instanceof Model.Script s) {
            store(s.id(), s.output(), scripts.run(s, ctx), ctx, body);
        } else if (step instanceof Model.XRequest x) {
            store(x.id(), "true".equals(x.output()) || "headers".equals(x.output()), xrequests.run(x, ctx), ctx, body);
        } else if (step instanceof Model.Execute x) {
            store(x.id(), Boolean.TRUE.equals(x.output()), executes.run(x, ctx), ctx, body);
        } else if (step instanceof Model.Text t) {
            store(t.id(), t.output(), render(t.text(), ctx), ctx, body);
        }
    }

    private boolean applies(Model.Step step, Context ctx) {
        String when = null;
        if (step instanceof Model.Sql s) {
            when = s.when();
        } else if (step instanceof Model.Transaction t) {
            when = t.when();
        } else if (step instanceof Model.Script s) {
            when = s.when();
        } else if (step instanceof Model.XRequest x) {
            when = x.when();
        } else if (step instanceof Model.Execute x) {
            when = x.when();
        } else if (step instanceof Model.Text t) {
            when = t.when();
        }
        if (step instanceof Model.Sql s && s.requires() != null) {
            for (String name : s.requires().split("[,\\s]+")) {
                if (!name.isEmpty() && Expr.isEmpty(ctx.params.get(name))) {
                    throw new MqException(400, "parameter '" + name + "' is required");
                }
            }
        }
        return when == null || Expr.parse(when).test(ctx);
    }

    private void transaction(Model.Transaction t, Context ctx, Map<String, Object> body) throws Exception {
        try (Connection con = connection(t.datasource())) {
            con.setAutoCommit(false);
            try {
                for (Model.Sql s : t.statements()) {
                    if (s.when() == null || Expr.parse(s.when()).test(ctx)) {
                        try {
                            store(s, SqlRunner.run(con, s, ctx), ctx, body);
                        } catch (SQLException e) {
                            throw withMessage(s.onerror(), e);
                        }
                    }
                }
                con.commit();
            } catch (Exception | Error e) {
                try {
                    con.rollback();
                } catch (SQLException rb) {
                    e.addSuppressed(rb);
                }
                throw e;
            }
        }
    }

    private Connection connection(String datasource) throws SQLException {
        DataSource ds = dataSources.apply(datasource);
        if (ds == null) {
            throw new MqException(500, "no datasource" + (datasource == null ? "" : " named '" + datasource + "'") + " is configured");
        }
        return ds.getConnection();
    }

    private static SQLException withMessage(String onerror, SQLException e) {
        if (onerror == null || onerror.isEmpty()) {
            return e;
        }
        SQLException x = new SQLException(onerror, e.getSQLState(), e.getErrorCode(), e);
        return x;
    }

    private void store(Model.Sql s, Object result, Context ctx, Map<String, Object> body) {
        boolean query = result instanceof java.util.List;
        boolean out = s.output() != null ? s.output() : s.verbose() != null ? s.verbose() : query;
        store(s.id(), out, result, ctx, body);
    }

    private void store(String id, boolean output, Object result, Context ctx, Map<String, Object> body) {
        ctx.results.put(id, result);
        if (output) {
            body.put(id, result);
        }
    }

    /** a Text step: the text with $variables replaced by their values */
    static String render(String text, Context ctx) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            int n = text.charAt(i) == '$' ? Expr.refLength(text, i) : 0;
            if (n > 0) {
                Object v = ctx.lookup(Expr.readRef(text, i));
                out.append(v == null ? "" : v instanceof Map<?, ?> || v instanceof java.util.List<?> ? Json.write(v) : String.valueOf(v));
                i += n;
            } else {
                out.append(text.charAt(i++));
            }
        }
        return out.toString();
    }
}
