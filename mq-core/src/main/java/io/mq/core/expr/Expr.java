package io.mq.core.expr;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The {@code when} condition language of R2 resources: {@code $q eq 'recent'}, {@code not empty $customer_id},
 * {@code $[calc].ok eq true and $qty gt 0}. Operators: eq ne lt gt le ge (also == != &lt; &gt; &lt;= &gt;=), and, or, not, empty, parentheses.
 * Variables are request parameters ({@code $name}, {@code $name.field[0]}) and step results ({@code $[id].path}).
 * Semantics follow EL, which R2 used: if either side of a comparison is a number both are compared as numbers, else if either is a
 * boolean as booleans, else as strings; a missing value is null; {@code null eq x} is false and {@code null ne x} is true.
 * Parsing is separate from evaluation so that the validator can report a bad expression with the file and line.
 */
public final class Expr {

    /** a variable in an expression or text: {@code $name.path} (mpath false) or {@code $[name].path} (mpath true) */
    public record Ref(boolean mpath, String name, String path) {
    }

    /** supplies the values of variables when an expression is evaluated */
    public interface Env {
        Object lookup(Ref ref);
    }

    public static final class ParseException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public final int position;

        ParseException(String message, int position) {
            super(message + " (at character " + position + " of the expression)");
            this.position = position;
        }
    }

    private sealed interface Node permits Lit, Var, Not, Empty, Bin {
        Object eval(Env env);
    }

    private record Lit(Object value) implements Node {
        public Object eval(Env env) {
            return value;
        }
    }

    private record Var(Ref ref) implements Node {
        public Object eval(Env env) {
            return env.lookup(ref);
        }
    }

    private record Not(Node n) implements Node {
        public Object eval(Env env) {
            return !truthy(n.eval(env));
        }
    }

    private record Empty(Node n) implements Node {
        public Object eval(Env env) {
            return isEmpty(n.eval(env));
        }
    }

    private record Bin(String op, Node l, Node r) implements Node {
        public Object eval(Env env) {
            switch (op) {
                case "and":
                    return truthy(l.eval(env)) && truthy(r.eval(env));
                case "or":
                    return truthy(l.eval(env)) || truthy(r.eval(env));
                default:
                    return compare(op, l.eval(env), r.eval(env));
            }
        }
    }

    private final Node root;
    private final List<Ref> refs;
    private final String source;

    private Expr(String source, Node root, List<Ref> refs) {
        this.source = source;
        this.root = root;
        this.refs = refs;
    }

    public String source() {
        return source;
    }

    /** every variable the expression uses, in order */
    public List<Ref> refs() {
        return refs;
    }

    public Object eval(Env env) {
        return root.eval(env);
    }

    public boolean test(Env env) {
        return truthy(root.eval(env));
    }

    public static Expr parse(String source) {
        return new Parser(source).parse();
    }

    // ------------------------------------------------------------ helpers shared with the executors

    public static boolean truthy(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        return v instanceof String s && s.equalsIgnoreCase("true");
    }

    public static boolean isEmpty(Object v) {
        return v == null || (v instanceof CharSequence c && c.length() == 0) || (v instanceof Collection<?> c && c.isEmpty())
                || (v instanceof Map<?, ?> m && m.isEmpty());
    }

    private static BigDecimal number(Object v) {
        if (v instanceof BigDecimal d) {
            return d;
        }
        if (v instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        try {
            return new BigDecimal(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static boolean compare(String op, Object a, Object b) {
        if (a == null || b == null) {
            return switch (op) {
                case "eq" -> a == b;
                case "ne" -> a != b;
                default -> false;
            };
        }
        int c;
        if (a instanceof Number || b instanceof Number) {
            BigDecimal x = number(a);
            BigDecimal y = number(b);
            if (x == null || y == null) {
                return op.equals("ne");
            }
            c = x.compareTo(y);
        } else if (a instanceof Boolean || b instanceof Boolean) {
            if (!op.equals("eq") && !op.equals("ne")) {
                return false;
            }
            c = Boolean.valueOf(truthy(a)).equals(truthy(b)) ? 0 : 1;
        } else {
            c = String.valueOf(a).compareTo(String.valueOf(b));
        }
        return switch (op) {
            case "eq" -> c == 0;
            case "ne" -> c != 0;
            case "lt" -> c < 0;
            case "gt" -> c > 0;
            case "le" -> c <= 0;
            case "ge" -> c >= 0;
            default -> false;
        };
    }

    // ------------------------------------------------------------ parser

    private static final class Parser {
        final String s;
        int i;
        final List<Ref> refs = new ArrayList<>();

        Parser(String s) {
            this.s = s;
        }

        Expr parse() {
            Node n = or();
            ws();
            if (i < s.length()) {
                throw new ParseException("unexpected '" + s.substring(i, Math.min(s.length(), i + 12)) + "'", i);
            }
            return new Expr(s, n, List.copyOf(refs));
        }

        void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        boolean word(String w) {
            ws();
            if (s.regionMatches(true, i, w, 0, w.length())
                    && (i + w.length() >= s.length() || !Character.isLetterOrDigit(s.charAt(i + w.length())) && s.charAt(i + w.length()) != '_')) {
                i += w.length();
                return true;
            }
            return false;
        }

        Node or() {
            Node l = and();
            while (word("or")) {
                l = new Bin("or", l, and());
            }
            return l;
        }

        Node and() {
            Node l = not();
            while (word("and")) {
                l = new Bin("and", l, not());
            }
            return l;
        }

        Node not() {
            if (word("not")) {
                return new Not(not());
            }
            return cmp();
        }

        Node cmp() {
            Node l = unary();
            ws();
            String op = null;
            for (String[] o : new String[][] { { "==", "eq" }, { "!=", "ne" }, { "<=", "le" }, { ">=", "ge" }, { "<", "lt" }, { ">", "gt" } }) {
                if (s.startsWith(o[0], i)) {
                    i += o[0].length();
                    op = o[1];
                    break;
                }
            }
            if (op == null) {
                for (String o : new String[] { "eq", "ne", "lt", "gt", "le", "ge" }) {
                    if (word(o)) {
                        op = o;
                        break;
                    }
                }
            }
            return op == null ? l : new Bin(op, l, unary());
        }

        Node unary() {
            if (word("empty")) {
                return new Empty(unary());
            }
            return primary();
        }

        Node primary() {
            ws();
            if (i >= s.length()) {
                throw new ParseException("the expression ends where a value was expected", i);
            }
            char c = s.charAt(i);
            if (c == '(') {
                i++;
                Node n = or();
                ws();
                if (i >= s.length() || s.charAt(i) != ')') {
                    throw new ParseException("missing ')'", i);
                }
                i++;
                return n;
            }
            if (c == '\'' || c == '"') {
                int end = s.indexOf(c, i + 1);
                if (end < 0) {
                    throw new ParseException("unterminated string", i);
                }
                String v = s.substring(i + 1, end);
                i = end + 1;
                return new Lit(v);
            }
            if (c == '$') {
                return variable();
            }
            if (Character.isDigit(c) || c == '-' || c == '+' || c == '.') {
                int st = i;
                i++;
                while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) {
                    i++;
                }
                try {
                    return new Lit(new BigDecimal(s.substring(st, i)));
                } catch (NumberFormatException e) {
                    throw new ParseException("not a number: '" + s.substring(st, i) + "'", st);
                }
            }
            if (word("true")) {
                return new Lit(Boolean.TRUE);
            }
            if (word("false")) {
                return new Lit(Boolean.FALSE);
            }
            if (word("null")) {
                return new Lit(null);
            }
            throw new ParseException("unexpected '" + s.substring(i, Math.min(s.length(), i + 12)) + "'", i);
        }

        Node variable() {
            int st = i;
            Ref r = readRef(s, i);
            if (r == null) {
                throw new ParseException("a variable must look like $name or $[id].path", st);
            }
            i += refLength(s, i);
            refs.add(r);
            return new Var(r);
        }
    }

    // ------------------------------------------------------------ variable syntax, shared with SQL text and URLs

    /** reads a variable that starts at {@code at} (which holds '$'); null when none starts there */
    public static Ref readRef(String s, int at) {
        int n = refLength(s, at);
        if (n == 0) {
            return null;
        }
        String t = s.substring(at, at + n);
        if (t.startsWith("$[")) {
            int close = t.indexOf(']');
            return new Ref(true, t.substring(2, close), t.substring(close + 1));
        }
        int k = 1;
        while (k < t.length() && (Character.isLetterOrDigit(t.charAt(k)) || t.charAt(k) == '_')) {
            k++;
        }
        return new Ref(false, t.substring(1, k), t.substring(k));
    }

    /** length of the variable at {@code at}: {@code $name}, {@code $[id]}, each followed by {@code .field} and {@code [n]} parts; 0 if none */
    public static int refLength(String s, int at) {
        int i = at;
        if (i >= s.length() || s.charAt(i) != '$') {
            return 0;
        }
        i++;
        if (i < s.length() && s.charAt(i) == '[') {
            int close = s.indexOf(']', i);
            if (close < 0 || close == i + 1) {
                return 0;
            }
            i = close + 1;
        } else {
            int st = i;
            while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) {
                i++;
            }
            if (i == st) {
                return 0;
            }
        }
        for (;;) {
            if (i < s.length() && s.charAt(i) == '.' && i + 1 < s.length()
                    && (Character.isLetterOrDigit(s.charAt(i + 1)) || s.charAt(i + 1) == '_')) {
                i++;
                while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) {
                    i++;
                }
            } else if (i < s.length() && s.charAt(i) == '[') {
                int close = s.indexOf(']', i);
                if (close < 0 || !s.substring(i + 1, close).matches("\\d+")) {
                    break;
                }
                i = close + 1;
            } else {
                break;
            }
        }
        return i - at;
    }

    /** finds every variable in a text (SQL, URL, body), in order */
    public static List<Ref> refsIn(String text) {
        List<Ref> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '$') {
                int n = refLength(text, i);
                if (n > 0) {
                    out.add(readRef(text, i));
                    i += n - 1;
                }
            }
        }
        return out;
    }

    /** follows {@code .field} and {@code [n]} steps through maps (case-insensitive keys) and lists; null when something is missing */
    public static Object navigate(Object root, String path) {
        Object cur = root;
        int i = 0;
        while (i < path.length() && cur != null) {
            char c = path.charAt(i);
            if (c == '.') {
                int st = ++i;
                while (i < path.length() && path.charAt(i) != '.' && path.charAt(i) != '[') {
                    i++;
                }
                String key = path.substring(st, i);
                if (cur instanceof Map<?, ?> m) {
                    Object v = m.get(key);
                    if (v == null && !m.containsKey(key)) {
                        v = null;
                        for (Map.Entry<?, ?> e : m.entrySet()) {
                            if (String.valueOf(e.getKey()).equalsIgnoreCase(key)) {
                                v = e.getValue();
                                break;
                            }
                        }
                    }
                    cur = v;
                } else {
                    return null;
                }
            } else if (c == '[') {
                int close = path.indexOf(']', i);
                int idx = Integer.parseInt(path.substring(i + 1, close));
                i = close + 1;
                if (cur instanceof List<?> l && idx >= 0 && idx < l.size()) {
                    cur = l.get(idx);
                } else {
                    return null;
                }
            } else {
                return null;
            }
        }
        return cur;
    }
}
