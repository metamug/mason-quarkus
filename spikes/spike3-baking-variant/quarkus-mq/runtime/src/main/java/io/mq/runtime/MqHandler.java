package io.mq.runtime;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import io.quarkus.arc.Arc;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;

/** Executes the steps of one request definition. Runs on a worker thread (blocking JDBC). */
public class MqHandler implements Handler<RoutingContext> {

    private static final Pattern PARAM = Pattern.compile("\\$([A-Za-z_][A-Za-z0-9_]*)");

    private final MqRequest request;

    public MqHandler(MqRequest request) {
        this.request = request;
    }

    @Override
    public void handle(RoutingContext ctx) {
        JsonObject out = new JsonObject();
        try {
            DataSource ds = Arc.container().instance(DataSource.class).get();
            try (Connection con = ds.getConnection()) {
                for (MqStep step : request.steps) {
                    if ("text".equals(step.kind)) {
                        if (step.output) {
                            out.put(step.id, step.text);
                        }
                    } else {
                        run(con, step, ctx, out);
                    }
                }
            }
            ctx.response().setStatusCode(request.status > 0 ? request.status : 200)
                    .putHeader("Content-Type", "application/json").end(out.encode());
        } catch (Exception e) {
            ctx.response().setStatusCode(512).putHeader("Content-Type", "application/json")
                    .end(new JsonObject().put("message", String.valueOf(e.getMessage())).encode());
        }
    }

    private void run(Connection con, MqStep step, RoutingContext ctx, JsonObject out) throws Exception {
        List<String> names = new ArrayList<>();
        Matcher m = PARAM.matcher(step.text);
        StringBuilder sql = new StringBuilder();
        while (m.find()) {
            names.add(m.group(1));
            m.appendReplacement(sql, "?");
        }
        m.appendTail(sql);
        try (PreparedStatement ps = con.prepareStatement(sql.toString())) {
            for (int i = 0; i < names.size(); i++) {
                ps.setObject(i + 1, value(ctx, names.get(i)));
            }
            if ("update".equals(step.type)) {
                int n = ps.executeUpdate();
                if (step.output) {
                    out.put(step.id, new JsonObject().put("updated", n));
                }
            } else {
                JsonArray rows = new JsonArray();
                try (ResultSet rs = ps.executeQuery()) {
                    ResultSetMetaData md = rs.getMetaData();
                    while (rs.next()) {
                        JsonObject row = new JsonObject();
                        for (int c = 1; c <= md.getColumnCount(); c++) {
                            row.put(md.getColumnLabel(c).toLowerCase(), rs.getObject(c) instanceof Number || rs.getObject(c) instanceof Boolean
                                    ? rs.getObject(c) : (rs.getObject(c) == null ? null : rs.getObject(c).toString()));
                        }
                        rows.add(row);
                    }
                }
                if (step.output) {
                    out.put(step.id, rows);
                }
            }
        }
    }

    private static String value(RoutingContext ctx, String name) {
        String v = ctx.pathParam(name);
        if (v == null) {
            v = ctx.request().getParam(name);
        }
        if (v == null) {
            v = ctx.request().getFormAttribute(name);
        }
        return v;
    }
}
