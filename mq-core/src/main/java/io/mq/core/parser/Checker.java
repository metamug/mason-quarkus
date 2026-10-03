package io.mq.core.parser;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.mq.core.expr.Expr;
import io.mq.core.model.Model;

/**
 * Checks a parsed element tree against R2's resource.xsd (every element, attribute, datatype and the two xsd:unique constraints),
 * against the rules R2's Java code adds (mpath references, limit/offset only on queries) and against the MQ rules
 * (a step id is unique in the whole resource, also inside a Transaction). Builds the model while it goes.
 * The golden cases in golden/ pin each rule.
 */
final class Checker {

    static final String NS = "http://xml.metamug.net/resource/1.0";

    /** the schema-instance attributes that any element may carry (R2 shop resources use xsi:schemaLocation) */
    private static final String XSI = "{http://www.w3.org/2001/XMLSchema-instance}";

    private static final Pattern MPATH = Pattern.compile("\\$\\[([^\\]\\.\\[\\s]+)");
    private static final Pattern DOUBLE = Pattern.compile("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?|[+-]?INF|NaN");
    private static final Pattern INTEGER = Pattern.compile("[+-]?\\d+");

    // ---- datatypes of the schema: null when the value is fine, otherwise what is wrong ----
    private interface Type {
        String check(String v);
    }

    private static final Type STRING = v -> null;
    private static final Type NON_EMPTY = v -> v.isEmpty() ? "must not be empty" : null;
    private static final Type ID = v -> {
        int n = v.codePointCount(0, v.length());
        return n < 1 ? "must not be empty" : n > 100 ? "must have at most 100 characters, found " + n : null;
    };
    private static final Type BOOLEAN = v -> {
        String t = v.trim();
        return t.equals("true") || t.equals("false") || t.equals("1") || t.equals("0") ? null : "must be true or false, found '" + v + "'";
    };
    private static final Type DOUBLE_T = v -> DOUBLE.matcher(v.trim()).matches() ? null : "must be a number, found '" + v + "'";
    private static final Type VERSION = v -> {
        String t = v.trim();
        if (!DOUBLE.matcher(t).matches()) {
            return "must be a number, found '" + v + "'";
        }
        return t.equals("NaN") || t.startsWith("-") || isZero(t) ? "must be greater than 0, found '" + v + "'" : null;
    };
    private static final Type STATUS = v -> {
        String t = v.trim();
        if (!INTEGER.matcher(t).matches()) {
            return "must be a whole number, found '" + v + "'";
        }
        BigInteger n = new BigInteger(t.startsWith("+") ? t.substring(1) : t);
        return n.compareTo(BigInteger.valueOf(100)) < 0 || n.compareTo(BigInteger.valueOf(599)) > 0
                ? "must be from 100 to 599, found '" + v + "'" : null;
    };
    private static final Type LENGTH = v -> {
        String t = v.trim();
        if (!INTEGER.matcher(t).matches()) {
            return "must be a whole number, found '" + v + "'";
        }
        return t.startsWith("-") && !new BigInteger(t).equals(BigInteger.ZERO) ? "must be 0 or more, found '" + v + "'" : null;
    };
    /** [a-zA-Z].* : starts with a letter, no line break */
    private static final Type LETTER_FIRST = v -> {
        if (v.isEmpty() || !((v.charAt(0) >= 'a' && v.charAt(0) <= 'z') || (v.charAt(0) >= 'A' && v.charAt(0) <= 'Z'))) {
            return "must start with a letter, found '" + v + "'";
        }
        return v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0 ? "must not contain a line break" : null;
    };
    private static final Type EXISTS = v -> v.codePointCount(0, v.length()) < 3 ? "must have at least 3 characters, found '" + v + "'" : null;

    private static Type oneOf(String... values) {
        return v -> {
            for (String s : values) {
                if (s.equals(v)) {
                    return null;
                }
            }
            return "must be one of " + String.join(", ", values) + ", found '" + v + "'";
        };
    }

    private static final Type METHOD = oneOf("HEAD", "GET", "POST", "PUT", "DELETE");
    private static final Type PARAM_TYPE = oneOf("date", "datetime", "email", "number", "text", "time", "url");
    private static final Type SQL_TYPE = oneOf("query", "update");
    private static final Type XREQUEST_OUTPUT = oneOf("true", "false", "headers");

    private static boolean isZero(String t) {
        String d = t.replaceFirst("^[+-]", "");
        int e = d.indexOf('e') >= 0 ? d.indexOf('e') : d.indexOf('E');
        String mantissa = e >= 0 ? d.substring(0, e) : d;
        return !mantissa.equals("INF") && mantissa.chars().noneMatch(c -> c >= '1' && c <= '9');
    }

    /** one attribute of an element */
    private record Spec(String name, Type type, boolean required) {
    }

    private static Spec opt(String name, Type type) {
        return new Spec(name, type, false);
    }

    private static Spec req(String name, Type type) {
        return new Spec(name, type, true);
    }

    private static final Spec[] RESOURCE = { req("v", VERSION), opt("parent", NON_EMPTY), opt("auth", LETTER_FIRST) };
    private static final Spec[] TAG = { opt("name", STRING), opt("color", STRING) };
    private static final Spec[] REQUEST = { req("method", METHOD), opt("status", STATUS), opt("item", STRING) };
    private static final Spec[] HEADER = { req("name", NON_EMPTY), req("value", STRING) };
    private static final Spec[] PARAM = { req("name", NON_EMPTY), opt("max", DOUBLE_T), opt("min", DOUBLE_T), opt("maxlength", LENGTH),
            opt("minlength", LENGTH), opt("pattern", STRING), opt("exists", EXISTS), opt("value", STRING), opt("testvalue", STRING),
            req("type", PARAM_TYPE), opt("required", BOOLEAN) };
    private static final Spec[] EXECUTE = { req("id", ID), opt("requires", NON_EMPTY), opt("when", STRING), opt("onerror", STRING),
            opt("classname", NON_EMPTY), opt("verbose", BOOLEAN), opt("output", BOOLEAN), opt("status", STATUS) };
    private static final Spec[] ARG = { req("name", STRING), opt("value", STRING), opt("path", STRING) };
    private static final Spec[] SQL = { req("id", ID), opt("type", SQL_TYPE), opt("datasource", STRING), opt("requires", NON_EMPTY),
            opt("ref", STRING), opt("when", STRING), opt("onblank", STRING), opt("onerror", STRING), opt("verbose", BOOLEAN),
            opt("output", BOOLEAN), opt("limit", LETTER_FIRST), opt("offset", LETTER_FIRST), opt("classname", NON_EMPTY),
            opt("status", STATUS) };
    private static final Spec[] TRANSACTION = { opt("when", STRING), opt("datasource", STRING) };
    private static final Spec[] XREQUEST = { req("id", ID), opt("when", STRING), req("url", STRING), req("method", METHOD),
            opt("verbose", BOOLEAN), opt("output", XREQUEST_OUTPUT), opt("classname", NON_EMPTY) };
    private static final Spec[] SCRIPT = { req("id", ID), req("file", NON_EMPTY), opt("output", BOOLEAN), opt("when", STRING) };
    private static final Spec[] TEXT = { req("id", ID), opt("when", STRING), opt("output", BOOLEAN) };
    private static final Spec[] NONE = {};

    private final String file;
    private final List<Problem> problems;

    /** step id -> line of its first use, for the whole resource (xsd:unique on Request/@id, widened to Transaction by MQ) */
    private final Map<String, Integer> idLines = new HashMap<>();
    /** ids of the steps already seen in the Request being read: the only ones an mpath may point to */
    private Set<String> defined = new HashSet<>();

    Checker(String file, List<Problem> problems) {
        this.file = file;
        this.problems = problems;
    }

    private void err(Node n, String message) {
        problems.add(new Problem(file, n.line, n.column, message));
    }

    private void errText(Node n, String message) {
        problems.add(new Problem(file, n.badTextLine, n.badTextColumn, message));
    }

    private void errAt(Node n, String message) {
        err(n, message);
    }

    // ---------------------------------------------------------------- document

    Model.Resource check(Node root) {
        if (!root.uri.equals(NS)) {
            errAt(root, root.uri.isEmpty() ? "the root element must be in the namespace " + NS
                    : "the root element is in the namespace " + root.uri + " but must be in " + NS);
            return null;
        }
        if (!root.local.equals("Resource")) {
            errAt(root, "the root element must be <Resource>, found <" + root.local + ">");
            return null;
        }
        Map<String, String> a = attributes(root, "Resource", RESOURCE);
        noText(root, "Resource");
        String desc = null;
        boolean sawDesc = false;
        boolean sawRequest = false;
        List<Model.Request> requests = new ArrayList<>();
        Map<String, Integer> methodItems = new HashMap<>();
        for (Node k : root.kids) {
            if (!inNamespace(k, "Resource")) {
                continue;
            }
            switch (k.local) {
                case "Desc" -> {
                    if (sawRequest) {
                        err(k, "<Desc> must come before the <Request> elements");
                    } else if (sawDesc) {
                        err(k, "<Resource> may have only one <Desc>");
                    }
                    sawDesc = true;
                    String d = desc(k);
                    if (!sawRequest && desc == null) {
                        desc = d;
                    }
                }
                case "Request" -> {
                    sawRequest = true;
                    Model.Request r = request(k);
                    requests.add(r);
                    if (k.attrs.containsKey("method") && k.attrs.containsKey("item")) {
                        String key = k.attrs.get("method") + " item=" + k.attrs.get("item");
                        Integer first = methodItems.putIfAbsent(key, k.line);
                        if (first != null) {
                            err(k, "duplicate <Request> with method " + k.attrs.get("method") + " and item '" + k.attrs.get("item")
                                    + "' (first on line " + first + ")");
                        }
                    }
                }
                default -> err(k, "unexpected element <" + k.local + "> in <Resource>");
            }
        }
        if (!sawRequest) {
            err(root, "<Resource> needs at least one <Request>");
        }
        return new Model.Resource(file.endsWith(".xml") ? file.substring(0, file.length() - 4) : file, a.get("v") == null ? null : a.get("v").trim(),
                a.get("parent"), a.get("auth"), desc, List.copyOf(requests));
    }

    /** an element must be in the resource namespace; reports it and returns false otherwise */
    private boolean inNamespace(Node n, String parent) {
        if (n.uri.equals(NS)) {
            return true;
        }
        err(n, n.uri.isEmpty() ? "<" + n.local + "> in <" + parent + "> must be in the namespace " + NS
                : "<" + n.local + "> is in the namespace " + n.uri + " but must be in " + NS);
        return false;
    }

    /** checks and returns the attributes of an element: unknown, missing and badly typed ones are problems */
    private Map<String, String> attributes(Node n, String element, Spec[] specs) {
        Map<String, String> found = new HashMap<>();
        for (Map.Entry<String, String> e : n.attrs.entrySet()) {
            if (e.getKey().equals(XSI + "schemaLocation") || e.getKey().equals(XSI + "noNamespaceSchemaLocation")) {
                continue;
            }
            Spec s = null;
            for (Spec c : specs) {
                if (c.name.equals(e.getKey())) {
                    s = c;
                }
            }
            if (s == null) {
                err(n, "unknown attribute '" + e.getKey() + "' on <" + element + ">");
                continue;
            }
            found.put(s.name, e.getValue());
            String bad = s.type.check(e.getValue());
            if (bad != null) {
                err(n, "attribute '" + s.name + "' of <" + element + "> " + bad);
            }
        }
        for (Spec s : specs) {
            if (s.required && !n.attrs.containsKey(s.name)) {
                err(n, "<" + element + "> needs the attribute '" + s.name + "'");
            }
        }
        return found;
    }

    private void noText(Node n, String element) {
        if (n.hasNonBlankText()) {
            errText(n, "character data is not allowed in <" + element + ">");
        }
    }

    /** an element with no content at all */
    private void empty(Node n, String element) {
        noText(n, element);
        for (Node k : n.kids) {
            err(k, "<" + element + "> must be empty, found <" + k.local + ">");
        }
    }

    /** an element with text only (Sql, Text, Body) */
    private String textOnly(Node n, String element) {
        for (Node k : n.kids) {
            err(k, "<" + element + "> may contain only text, found <" + k.local + ">");
        }
        return n.text.toString().trim();
    }

    private String desc(Node d) {
        attributes(d, "Desc", NONE);
        int tags = 0;
        for (Node k : d.kids) {
            if (!inNamespace(k, "Desc")) {
                continue;
            }
            if (!k.local.equals("Tag")) {
                err(k, "unexpected element <" + k.local + "> in <Desc>");
            } else if (++tags > 1) {
                err(k, "<Desc> may have only one <Tag>");
            } else {
                attributes(k, "Tag", TAG);
                empty(k, "Tag");
            }
        }
        return d.text.toString().trim();
    }

    // ---------------------------------------------------------------- request

    private Model.Request request(Node n) {
        Map<String, String> a = attributes(n, "Request", REQUEST);
        noText(n, "Request");
        defined = new HashSet<>();
        String desc = null;
        List<Model.Header> headers = new ArrayList<>();
        List<Model.Param> params = new ArrayList<>();
        List<Model.Step> steps = new ArrayList<>();
        for (Node k : n.kids) {
            if (!inNamespace(k, "Request")) {
                continue;
            }
            switch (k.local) {
                case "Desc" -> {
                    String d = desc(k);
                    if (desc == null) {
                        desc = d;
                    }
                }
                case "Header" -> {
                    Map<String, String> h = attributes(k, "Header", HEADER);
                    empty(k, "Header");
                    headers.add(new Model.Header(h.get("name"), h.get("value"), k.line));
                }
                case "Param" -> params.add(param(k));
                case "Sql" -> steps.add(sql(k, false));
                case "Transaction" -> steps.add(transaction(k));
                case "XRequest" -> steps.add(xrequest(k));
                case "Script" -> steps.add(script(k));
                case "Text" -> steps.add(text(k));
                case "Execute" -> steps.add(execute(k));
                default -> err(k, "unexpected element <" + k.local + "> in <Request>");
            }
        }
        return new Model.Request(a.get("method"), a.get("item"), a.containsKey("status") && STATUS.check(a.get("status")) == null
                ? Integer.valueOf(a.get("status").trim().replaceFirst("^\\+", "")) : null, desc, List.copyOf(headers), List.copyOf(params),
                List.copyOf(steps), n.line);
    }

    private Model.Param param(Node n) {
        Map<String, String> a = attributes(n, "Param", PARAM);
        empty(n, "Param");
        return new Model.Param(a.get("name"), a.get("type"), a.containsKey("required") && bool(a.get("required")), a.get("max"), a.get("min"),
                a.get("maxlength"), a.get("minlength"), a.get("pattern"), a.get("exists"), a.get("value"), a.get("testvalue"), n.line);
    }

    // ---------------------------------------------------------------- steps

    /** registers a step id: unique in the whole resource, and from now on a valid mpath target in this Request */
    private void register(Node n, String id) {
        if (id == null) {
            return;
        }
        Integer first = idLines.putIfAbsent(id, n.line);
        if (first != null) {
            err(n, "duplicate id '" + id + "' (first used on line " + first + "); a step id is unique in the whole resource");
        }
        defined.add(id);
    }

    /** every $[id] in the text must name a step that comes before in the same Request */
    private void mpaths(Node n, String where, String value) {
        if (value == null) {
            return;
        }
        Matcher m = MPATH.matcher(value);
        while (m.find()) {
            if (!defined.contains(m.group(1))) {
                err(n, "mpath $[" + m.group(1) + "] in " + where + " does not name a step defined before it in the same <Request>");
            }
        }
    }

    /** a when attribute must be a valid condition, and its mpaths must name earlier steps */
    private void whenCondition(Node n, String element, String when) {
        if (when == null) {
            return;
        }
        try {
            Expr.parse(when);
        } catch (Expr.ParseException e) {
            err(n, "attribute 'when' of <" + element + "> is not a valid condition: " + e.getMessage());
            return;
        }
        mpaths(n, "the when condition of <" + element + ">", when);
    }

    private Model.Sql sql(Node n, boolean inTransaction) {
        Map<String, String> a = attributes(n, "Sql", SQL);
        String text = textOnly(n, "Sql");
        if ("update".equals(a.get("type"))) {
            for (String lim : new String[] { "limit", "offset" }) {
                if (a.containsKey(lim)) {
                    err(n, "attribute '" + lim + "' of <Sql> is only allowed on a query, not on type=\"update\"");
                }
            }
        }
        whenCondition(n, "Sql", a.get("when"));
        mpaths(n, "the text of <Sql>", text);
        register(n, a.get("id"));
        return new Model.Sql(a.get("id"), a.get("type"), a.get("datasource"), a.get("requires"), a.get("ref"), a.get("when"), a.get("onblank"),
                a.get("onerror"), a.containsKey("verbose") ? Boolean.valueOf(bool(a.get("verbose"))) : null, a.containsKey("output") ? Boolean.valueOf(bool(a.get("output"))) : null,
                a.get("limit"), a.get("offset"), a.get("classname"), status(a), text, n.line);
    }

    private Model.Transaction transaction(Node n) {
        Map<String, String> a = attributes(n, "Transaction", TRANSACTION);
        noText(n, "Transaction");
        whenCondition(n, "Transaction", a.get("when"));
        List<Model.Sql> statements = new ArrayList<>();
        for (Node k : n.kids) {
            if (!inNamespace(k, "Transaction")) {
                continue;
            }
            if (k.local.equals("Sql")) {
                statements.add(sql(k, true));
            } else {
                err(k, "only <Sql> is allowed in <Transaction>, found <" + k.local + ">");
            }
        }
        return new Model.Transaction(a.get("when"), a.get("datasource"), List.copyOf(statements), n.line);
    }

    private Model.XRequest xrequest(Node n) {
        Map<String, String> a = attributes(n, "XRequest", XREQUEST);
        noText(n, "XRequest");
        whenCondition(n, "XRequest", a.get("when"));
        mpaths(n, "the url of <XRequest>", a.get("url"));
        List<Model.Header> params = new ArrayList<>();
        List<Model.Header> headers = new ArrayList<>();
        StringBuilder body = null;
        for (Node k : n.kids) {
            if (!inNamespace(k, "XRequest")) {
                continue;
            }
            switch (k.local) {
                case "Param" -> {
                    Map<String, String> p = attributes(k, "Param", HEADER);
                    empty(k, "Param");
                    mpaths(k, "a <Param> of <XRequest>", p.get("value"));
                    params.add(new Model.Header(p.get("name"), p.get("value"), k.line));
                }
                case "Header" -> {
                    Map<String, String> h = attributes(k, "Header", HEADER);
                    empty(k, "Header");
                    mpaths(k, "a <Header> of <XRequest>", h.get("value"));
                    headers.add(new Model.Header(h.get("name"), h.get("value"), k.line));
                }
                case "Body" -> {
                    attributes(k, "Body", NONE);
                    String t = textOnly(k, "Body");
                    mpaths(k, "the <Body> of <XRequest>", t);
                    body = (body == null ? new StringBuilder() : body.append('\n')).append(t);
                }
                default -> err(k, "unexpected element <" + k.local + "> in <XRequest>");
            }
        }
        register(n, a.get("id"));
        return new Model.XRequest(a.get("id"), a.get("when"), a.get("url"), a.get("method"),
                a.containsKey("verbose") ? Boolean.valueOf(bool(a.get("verbose"))) : null, a.get("output"), a.get("classname"),
                List.copyOf(headers), List.copyOf(params), body == null ? null : body.toString(), n.line);
    }

    private Model.Script script(Node n) {
        Map<String, String> a = attributes(n, "Script", SCRIPT);
        empty(n, "Script");
        scriptFile(n, a.get("file"));
        whenCondition(n, "Script", a.get("when"));
        register(n, a.get("id"));
        return new Model.Script(a.get("id"), a.get("file"), !a.containsKey("output") || bool(a.get("output")), a.get("when"), n.line);
    }

    /** scripts are Kotlin only: a name, or name.kts; Groovy and any other language were dropped */
    private void scriptFile(Node n, String file) {
        if (file == null || file.isEmpty()) {
            return;
        }
        String base = file.endsWith(".kts") ? file.substring(0, file.length() - 4) : file;
        if (base.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return;
        }
        int dot = file.lastIndexOf('.');
        if (dot > 0 && !file.contains("/") && !file.contains("\\") && !file.contains("..")) {
            err(n, "attribute 'file' of <Script> names '" + file + "', which is not a Kotlin script: scripts are Kotlin only (name or name.kts); Groovy and other languages are not supported");
        } else {
            err(n, "attribute 'file' of <Script> must be a script name such as hash or hash.kts, found '" + file + "'");
        }
    }

    private Model.Text text(Node n) {
        Map<String, String> a = attributes(n, "Text", TEXT);
        String t = textOnly(n, "Text");
        whenCondition(n, "Text", a.get("when"));
        mpaths(n, "the text of <Text>", t);
        register(n, a.get("id"));
        return new Model.Text(a.get("id"), a.get("when"), !a.containsKey("output") || bool(a.get("output")), t, n.line);
    }

    private Model.Execute execute(Node n) {
        Map<String, String> a = attributes(n, "Execute", EXECUTE);
        noText(n, "Execute");
        whenCondition(n, "Execute", a.get("when"));
        List<Model.Arg> args = new ArrayList<>();
        for (Node k : n.kids) {
            if (!inNamespace(k, "Execute")) {
                continue;
            }
            if (k.local.equals("Arg")) {
                Map<String, String> g = attributes(k, "Arg", ARG);
                empty(k, "Arg");
                mpaths(k, "an <Arg> of <Execute>", g.get("path"));
                mpaths(k, "an <Arg> of <Execute>", g.get("value"));
                args.add(new Model.Arg(g.get("name"), g.get("value"), g.get("path")));
            } else {
                err(k, "unexpected element <" + k.local + "> in <Execute>");
            }
        }
        register(n, a.get("id"));
        return new Model.Execute(a.get("id"), a.get("requires"), a.get("when"), a.get("onerror"), a.get("classname"),
                a.containsKey("verbose") ? Boolean.valueOf(bool(a.get("verbose"))) : null,
                a.containsKey("output") ? Boolean.valueOf(bool(a.get("output"))) : null, status(a), List.copyOf(args), n.line);
    }

    private static boolean bool(String v) {
        String t = v.trim();
        return t.equals("true") || t.equals("1");
    }

    private static Integer status(Map<String, String> a) {
        String s = a.get("status");
        return s != null && STATUS.check(s) == null ? Integer.valueOf(s.trim().replaceFirst("^\\+", "")) : null;
    }
}
