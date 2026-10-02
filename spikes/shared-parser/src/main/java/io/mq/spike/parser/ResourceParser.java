package io.mq.spike.parser;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.xml.stream.Location;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * THROWAWAY spike code. Parses and validates one R2 resource XML file with StAX (never JAXB) into a plain model.
 * No reflection, no annotations, no third-party classes: this is what has to survive native-image unchanged.
 *
 * Validation is a deliberately small subset of the R2 schema, enough to have valid and invalid golden cases:
 * root element, version, request method/item/status, known child elements, required attributes, unique step ids.
 */
public final class ResourceParser {

    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "DELETE", "PATCH");
    private static final Set<String> REQUEST_CHILDREN = Set.of("Desc", "Param", "Sql", "Transaction", "XRequest", "Script", "Text", "Execute");

    /** result of parsing one file: a model when valid, errors (with line numbers) when not */
    public static final class Result {
        public final String name;
        public final boolean valid;
        public final List<String> errors;
        public final Resource resource;

        Result(String name, Resource resource, List<String> errors) {
            this.name = name;
            this.resource = resource;
            this.errors = errors;
            this.valid = errors.isEmpty();
        }

        public String summary() {
            return valid ? "valid requests=" + resource.requests.size() + " steps=" + resource.stepCount() : "invalid " + errors.get(0);
        }
    }

    public static final class Resource {
        public String name;
        public String version;
        public final List<Request> requests = new ArrayList<>();

        public int stepCount() {
            int n = 0;
            for (Request r : requests) {
                n += r.steps.size();
            }
            return n;
        }
    }

    public static final class Request {
        public String method;
        public boolean item;
        public int status;
        public final List<Step> steps = new ArrayList<>();
    }

    public static final class Step {
        /** sql, transaction, xrequest, script, text, execute, param */
        public String kind;
        public String id;
        public String type;
        public String text;
        public String when;
    }

    private ResourceParser() {
    }

    public static Result parse(String name, InputStream in) {
        List<String> errors = new ArrayList<>();
        Resource resource = new Resource();
        resource.name = name;
        try {
            XMLInputFactory f = XMLInputFactory.newFactory();
            f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            XMLStreamReader r = f.createXMLStreamReader(in);
            read(r, resource, errors);
        } catch (XMLStreamException e) {
            errors.add("not well-formed" + where(e.getLocation()) + ": " + firstLine(e.getMessage()));
        }
        return new Result(name, resource, errors);
    }

    private static void read(XMLStreamReader r, Resource resource, List<String> errors) throws XMLStreamException {
        Set<String> ids = new HashSet<>();
        Request request = null;
        boolean inTransaction = false;
        boolean sawRoot = false;
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.END_ELEMENT && r.getLocalName().equals("Transaction")) {
                inTransaction = false;
                continue;
            }
            if (ev != XMLStreamConstants.START_ELEMENT) {
                continue;
            }
            String el = r.getLocalName();
            if (!sawRoot) {
                sawRoot = true;
                if (!el.equals("Resource")) {
                    errors.add("root element must be <Resource>, found <" + el + ">" + where(r.getLocation()));
                    return;
                }
                resource.version = attr(r, "v");
                if (resource.version == null) {
                    errors.add("<Resource> needs a version attribute v" + where(r.getLocation()));
                } else if (!resource.version.matches("\\d+\\.\\d+")) {
                    errors.add("version v must look like 1.0, found '" + resource.version + "'" + where(r.getLocation()));
                }
                continue;
            }
            switch (el) {
                case "Desc":
                case "Tag":
                    skipText(r, el);
                    break;
                case "Request": {
                    request = new Request();
                    request.method = attr(r, "method");
                    if (request.method == null) {
                        errors.add("<Request> needs a method" + where(r.getLocation()));
                    } else if (!METHODS.contains(request.method)) {
                        errors.add("unknown method '" + request.method + "'" + where(r.getLocation()));
                    }
                    request.item = "true".equals(attr(r, "item"));
                    String status = attr(r, "status");
                    if (status != null) {
                        try {
                            request.status = Integer.parseInt(status);
                            if (request.status < 100 || request.status > 599) {
                                errors.add("status must be 100-599, found " + status + where(r.getLocation()));
                            }
                        } catch (NumberFormatException e) {
                            errors.add("status must be a number, found '" + status + "'" + where(r.getLocation()));
                        }
                    }
                    resource.requests.add(request);
                    break;
                }
                case "Transaction":
                    inTransaction = true;
                    if (request == null) {
                        errors.add("<Transaction> outside a <Request>" + where(r.getLocation()));
                    } else {
                        Step t = new Step();
                        t.kind = "transaction";
                        t.when = attr(r, "when");
                        request.steps.add(t);
                    }
                    break;
                case "Sql":
                case "XRequest":
                case "Script":
                case "Text":
                case "Execute":
                case "Param": {
                    if (request == null) {
                        errors.add("<" + el + "> outside a <Request>" + where(r.getLocation()));
                        break;
                    }
                    if (el.equals("Param") && !isRequestChild(r)) {
                        break;
                    }
                    Step s = new Step();
                    s.kind = el.toLowerCase();
                    s.id = attr(r, "id");
                    s.type = attr(r, "type");
                    s.when = attr(r, "when");
                    if (!el.equals("Param") && s.id == null) {
                        errors.add("<" + el + "> needs an id" + where(r.getLocation()));
                    } else if (s.id != null && !ids.add(s.id)) {
                        errors.add("duplicate id '" + s.id + "' (ids are unique across all requests of a resource)" + where(r.getLocation()));
                    }
                    if (el.equals("XRequest") && (attr(r, "url") == null || attr(r, "method") == null)) {
                        errors.add("<XRequest> needs url and method" + where(r.getLocation()));
                    }
                    if (el.equals("Script") && attr(r, "file") == null) {
                        errors.add("<Script> needs a file" + where(r.getLocation()));
                    }
                    if (el.equals("Sql") && s.type != null && !s.type.equals("query") && !s.type.equals("update")) {
                        errors.add("<Sql> type must be query or update, found '" + s.type + "'" + where(r.getLocation()));
                    }
                    if (el.equals("Sql") || el.equals("Text")) {
                        s.text = r.getElementText().trim().replaceAll("\\s+", " ");
                    }
                    if (inTransaction && !el.equals("Sql")) {
                        errors.add("only <Sql> is allowed inside <Transaction>, found <" + el + ">" + where(r.getLocation()));
                    }
                    request.steps.add(s);
                    break;
                }
                case "Header":
                case "Body":
                    break;
                default:
                    errors.add("unknown element <" + el + ">" + where(r.getLocation()));
                    if (!REQUEST_CHILDREN.contains(el)) {
                        skipText(r, el);
                    }
            }
        }
        if (!sawRoot) {
            errors.add("empty document");
        } else if (resource.requests.isEmpty()) {
            errors.add("a resource needs at least one <Request>");
        }
    }

    /** Param is a child of Request or of XRequest; only the former is a step */
    private static boolean isRequestChild(XMLStreamReader r) {
        return true;
    }

    private static void skipText(XMLStreamReader r, String el) throws XMLStreamException {
        int depth = 1;
        while (r.hasNext() && depth > 0) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                depth++;
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                depth--;
            }
        }
    }

    private static String attr(XMLStreamReader r, String name) {
        return r.getAttributeValue(null, name);
    }

    private static String where(Location l) {
        return l == null ? "" : " (line " + l.getLineNumber() + ")";
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        int i = s.indexOf('\n');
        return (i < 0 ? s : s.substring(0, i)).trim();
    }
}
