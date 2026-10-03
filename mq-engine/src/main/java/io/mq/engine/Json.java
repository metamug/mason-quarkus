package io.mq.engine;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal JSON writer and parser (no reflection, no dependencies): objects are LinkedHashMap, arrays ArrayList, numbers Long or BigDecimal. */
public final class Json {

    private Json() {
    }

    // ------------------------------------------------------------ write

    public static String write(Object v) {
        StringBuilder sb = new StringBuilder();
        write(sb, v);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            quote(sb, s);
        } else if (v instanceof Boolean || v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte
                || v instanceof BigInteger) {
            sb.append(v);
        } else if (v instanceof BigDecimal d) {
            sb.append(d.toPlainString());
        } else if (v instanceof Number n) {
            double d = n.doubleValue();
            sb.append(Double.isNaN(d) || Double.isInfinite(d) ? "null" : n.toString());
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<Object, Object>) m).entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                quote(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof Iterable<?> it) {
            sb.append('[');
            boolean first = true;
            for (Object o : it) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                write(sb, o);
            }
            sb.append(']');
        } else {
            quote(sb, v.toString());
        }
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    // ------------------------------------------------------------ parse

    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != text.length()) {
            throw new IllegalArgumentException("unexpected text after the JSON value at " + p.i);
        }
        return v;
    }

    private static final class Parser {
        final String s;
        int i;

        Parser(String s) {
            this.s = s;
        }

        void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        IllegalArgumentException err(String m) {
            return new IllegalArgumentException("invalid JSON at " + i + ": " + m);
        }

        Object value() {
            if (i >= s.length()) {
                throw err("unexpected end");
            }
            char c = s.charAt(i);
            switch (c) {
                case '{' -> {
                    i++;
                    Map<String, Object> m = new LinkedHashMap<>();
                    ws();
                    if (s.charAt(i) == '}') {
                        i++;
                        return m;
                    }
                    for (;;) {
                        ws();
                        String k = string();
                        ws();
                        expect(':');
                        ws();
                        m.put(k, value());
                        ws();
                        if (s.charAt(i) == ',') {
                            i++;
                        } else {
                            expect('}');
                            return m;
                        }
                    }
                }
                case '[' -> {
                    i++;
                    List<Object> l = new ArrayList<>();
                    ws();
                    if (s.charAt(i) == ']') {
                        i++;
                        return l;
                    }
                    for (;;) {
                        ws();
                        l.add(value());
                        ws();
                        if (s.charAt(i) == ',') {
                            i++;
                        } else {
                            expect(']');
                            return l;
                        }
                    }
                }
                case '"' -> {
                    return string();
                }
                case 't' -> {
                    lit("true");
                    return Boolean.TRUE;
                }
                case 'f' -> {
                    lit("false");
                    return Boolean.FALSE;
                }
                case 'n' -> {
                    lit("null");
                    return null;
                }
                default -> {
                    return number();
                }
            }
        }

        void expect(char c) {
            if (i >= s.length() || s.charAt(i) != c) {
                throw err("expected '" + c + "'");
            }
            i++;
        }

        void lit(String w) {
            if (!s.startsWith(w, i)) {
                throw err("expected " + w);
            }
            i += w.length();
        }

        Object number() {
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
                i++;
            }
            if (st == i) {
                throw err("unexpected character '" + s.charAt(i) + "'");
            }
            String n = s.substring(st, i);
            if (n.matches("-?\\d{1,18}")) {
                return Long.valueOf(n);
            }
            return new BigDecimal(n);
        }

        String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                        }
                        default -> sb.append(e);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw err("unterminated string");
        }
    }
}
