package io.mq.engine;

import java.math.BigDecimal;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.mq.core.expr.Expr;
import io.mq.core.model.Model;

/**
 * Runs one {@code <Sql>} step on a connection.
 * {@code $name} and {@code $[id].path} in the text become JDBC parameters (never pasted into the SQL), and each value is converted to the
 * type the database expects for that parameter, taken from the statement's parameter metadata. A path parameter such as {@code /order/7}
 * arrives as the text "7" and is bound as an integer for {@code WHERE id = $id}; this is the typed binding that JSTL's sql:param got wrong.
 * A variable inside quotes, {@code LIKE '%$q%'}, becomes {@code CONCAT('%', ?, '%')}.
 */
final class SqlRunner {

    record Prepared(String sql, List<Expr.Ref> binds) {
    }

    private SqlRunner() {
    }

    static Prepared prepare(String text) {
        StringBuilder out = new StringBuilder();
        List<Expr.Ref> binds = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\'') {
                // a quoted literal; variables inside it are concatenated in
                int j = i + 1;
                List<Object> parts = new ArrayList<>(); // String literal pieces and Expr.Ref
                StringBuilder lit = new StringBuilder();
                while (j < text.length()) {
                    char d = text.charAt(j);
                    if (d == '\'') {
                        if (j + 1 < text.length() && text.charAt(j + 1) == '\'') {
                            lit.append("''");
                            j += 2;
                            continue;
                        }
                        break;
                    }
                    int n = d == '$' ? Expr.refLength(text, j) : 0;
                    if (n > 0) {
                        if (lit.length() > 0) {
                            parts.add(lit.toString());
                            lit.setLength(0);
                        }
                        parts.add(Expr.readRef(text, j));
                        j += n;
                    } else {
                        lit.append(d);
                        j++;
                    }
                }
                if (lit.length() > 0) {
                    parts.add(lit.toString());
                }
                boolean hasRef = parts.stream().anyMatch(p -> p instanceof Expr.Ref);
                if (!hasRef) {
                    out.append(text, i, Math.min(text.length(), j + 1));
                } else {
                    out.append("CONCAT(");
                    boolean first = true;
                    for (Object p : parts) {
                        if (!first) {
                            out.append(", ");
                        }
                        first = false;
                        if (p instanceof Expr.Ref r) {
                            out.append('?');
                            binds.add(r);
                        } else {
                            out.append('\'').append(p).append('\'');
                        }
                    }
                    out.append(')');
                }
                i = j + 1;
            } else if (c == '$' && Expr.refLength(text, i) > 0) {
                int n = Expr.refLength(text, i);
                binds.add(Expr.readRef(text, i));
                out.append('?');
                i += n;
            } else {
                out.append(c);
                i++;
            }
        }
        return new Prepared(out.toString().trim(), binds);
    }

    static boolean isQuery(Model.Sql s) {
        if (s.type() != null) {
            return s.type().equals("query");
        }
        String t = s.text().stripLeading().toLowerCase(Locale.ROOT);
        return t.startsWith("select") || t.startsWith("with") || t.startsWith("values") || t.startsWith("show") || t.startsWith("explain");
    }

    /** runs the statement: a query returns a list of rows (maps), anything else a map with the update count */
    static Object run(Connection con, Model.Sql s, Context ctx) throws SQLException {
        Prepared p = prepare(s.text());
        try (PreparedStatement ps = con.prepareStatement(p.sql())) {
            bind(ps, p.binds(), ctx);
            if (isQuery(s)) {
                int limit = s.limit() == null ? -1 : intParam(ctx, s.limit());
                int offset = s.offset() == null ? 0 : Math.max(0, intParam(ctx, s.offset()));
                try (ResultSet rs = ps.executeQuery()) {
                    return rows(rs, offset, limit);
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("updated", ps.executeUpdate());
            return out;
        }
    }

    private static int intParam(Context ctx, String name) {
        Object v = ctx.params.get(name);
        if (v == null || String.valueOf(v).isEmpty()) {
            return -1;
        }
        try {
            return new BigDecimal(String.valueOf(v).trim()).intValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new MqException(400, "parameter '" + name + "' must be a whole number, found '" + v + "'");
        }
    }

    private static void bind(PreparedStatement ps, List<Expr.Ref> refs, Context ctx) throws SQLException {
        if (refs.isEmpty()) {
            return;
        }
        ParameterMetaData md = null;
        try {
            md = ps.getParameterMetaData();
        } catch (SQLException | RuntimeException e) {
            // driver cannot describe the parameters: bind values as they are
        }
        for (int i = 0; i < refs.size(); i++) {
            Expr.Ref r = refs.get(i);
            Object v = ctx.lookup(r);
            int sqlType = Types.OTHER;
            if (md != null) {
                try {
                    sqlType = md.getParameterType(i + 1);
                } catch (SQLException | RuntimeException e) {
                    // keep OTHER
                }
            }
            ps.setObject(i + 1, coerce(v, sqlType, r));
        }
    }

    static Object coerce(Object v, int sqlType, Expr.Ref r) {
        if (v == null) {
            return null;
        }
        if (v instanceof LocalDate d) {
            return java.sql.Date.valueOf(d);
        }
        if (v instanceof LocalDateTime d) {
            return Timestamp.valueOf(d);
        }
        if (v instanceof LocalTime d) {
            return java.sql.Time.valueOf(d);
        }
        if (v instanceof Map<?, ?> || v instanceof List<?>) {
            return Json.write(v);
        }
        if (!(v instanceof String s)) {
            return v;
        }
        try {
            switch (sqlType) {
                case Types.TINYINT:
                case Types.SMALLINT:
                case Types.INTEGER:
                    return new BigDecimal(s.trim()).intValueExact();
                case Types.BIGINT:
                    return new BigDecimal(s.trim()).longValueExact();
                case Types.DECIMAL:
                case Types.NUMERIC:
                    return new BigDecimal(s.trim());
                case Types.REAL:
                case Types.FLOAT:
                case Types.DOUBLE:
                    return Double.valueOf(s.trim());
                case Types.BOOLEAN:
                case Types.BIT:
                    return Boolean.valueOf(s.trim());
                case Types.DATE:
                    return java.sql.Date.valueOf(LocalDate.parse(s.trim()));
                case Types.TIMESTAMP:
                case Types.TIMESTAMP_WITH_TIMEZONE:
                    return Timestamp.valueOf(LocalDateTime.parse(s.trim().replace(' ', 'T')));
                case Types.TIME:
                    return java.sql.Time.valueOf(LocalTime.parse(s.trim()));
                default:
                    return s;
            }
        } catch (ArithmeticException | IllegalArgumentException | java.time.format.DateTimeParseException e) {
            throw new MqException(400, "parameter '" + r.name() + "': cannot use '" + s + "' where the database expects " + typeName(sqlType));
        }
    }

    private static String typeName(int t) {
        return switch (t) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT -> "a whole number";
            case Types.DECIMAL, Types.NUMERIC, Types.REAL, Types.FLOAT, Types.DOUBLE -> "a number";
            case Types.DATE -> "a date (yyyy-mm-dd)";
            case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> "a date and time";
            case Types.TIME -> "a time";
            default -> "another type";
        };
    }

    private static List<Map<String, Object>> rows(ResultSet rs, int offset, int limit) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int n = md.getColumnCount();
        String[] labels = new String[n];
        for (int i = 0; i < n; i++) {
            labels[i] = md.getColumnLabel(i + 1);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        int skipped = 0;
        while (rs.next()) {
            if (skipped < offset) {
                skipped++;
                continue;
            }
            if (limit >= 0 && out.size() >= limit) {
                break;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) {
                row.put(labels[i], value(rs.getObject(i + 1)));
            }
            out.add(row);
        }
        return out;
    }

    private static Object value(Object o) throws SQLException {
        if (o == null || o instanceof String || o instanceof Number || o instanceof Boolean) {
            return o;
        }
        if (o instanceof Timestamp t) {
            return t.toLocalDateTime().toString();
        }
        if (o instanceof java.sql.Date || o instanceof java.sql.Time || o instanceof java.time.temporal.TemporalAccessor) {
            return o.toString();
        }
        if (o instanceof byte[] b) {
            return Base64.getEncoder().encodeToString(b);
        }
        if (o instanceof Clob c) {
            return c.getSubString(1, (int) c.length());
        }
        return o.toString();
    }
}
