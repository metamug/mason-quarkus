package io.mq.core.parser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import javax.xml.stream.Location;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Parses and validates one R2 resource XML file. StAX only (never JAXB), no reflection, no third-party classes.
 * The rules are R2's resource.xsd plus the checks of R2's Java code and a few MQ rules; see docs/validator-gap-analysis.md.
 * Reports every problem with file and line instead of stopping at the first.
 */
public final class ResourceParser {

    private ResourceParser() {
    }

    public static ParseResult parse(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return parse(file.getFileName().toString(), in);
        }
    }

    /** @param file the name shown in problems */
    public static ParseResult parse(String file, InputStream in) {
        List<Problem> problems = new ArrayList<>();
        Node root = null;
        try {
            root = readTree(in);
        } catch (XMLStreamException e) {
            Location l = e.getLocation();
            problems.add(new Problem(file, l == null ? 0 : Math.max(l.getLineNumber(), 1), l == null ? 0 : l.getColumnNumber(),
                    "not well-formed: " + detail(e)));
        }
        if (root == null) {
            if (problems.isEmpty()) {
                problems.add(new Problem(file, 1, 1, "not well-formed: the document has no root element"));
            }
            return new ParseResult(file, null, problems);
        }
        Checker checker = new Checker(file, problems);
        var resource = checker.check(root);
        return new ParseResult(file, problems.isEmpty() ? resource : null, problems);
    }

    private static Node readTree(InputStream in) throws XMLStreamException {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        f.setProperty(XMLInputFactory.IS_COALESCING, true);
        XMLStreamReader r = f.createXMLStreamReader(in);
        Node root = null;
        Deque<Node> open = new ArrayDeque<>();
        try {
            while (r.hasNext()) {
                int ev = r.next();
                switch (ev) {
                    case XMLStreamConstants.START_ELEMENT -> {
                        Location l = r.getLocation();
                        Node n = new Node(r.getLocalName(), r.getNamespaceURI() == null ? "" : r.getNamespaceURI(),
                                l.getLineNumber(), l.getColumnNumber());
                        for (int i = 0; i < r.getAttributeCount(); i++) {
                            String uri = r.getAttributeNamespace(i);
                            String key = uri == null || uri.isEmpty() ? r.getAttributeLocalName(i)
                                    : "{" + uri + "}" + r.getAttributeLocalName(i);
                            n.attrs.put(key, r.getAttributeValue(i));
                        }
                        if (open.isEmpty()) {
                            root = n;
                        } else {
                            open.peek().kids.add(n);
                        }
                        open.push(n);
                    }
                    case XMLStreamConstants.END_ELEMENT -> open.pop();
                    case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE -> {
                        Node n = open.peek();
                        if (n != null) {
                            String t = r.getText();
                            n.text.append(t);
                            if (n.badTextLine == 0 && !t.isBlank()) {
                                Location l = r.getLocation();
                                n.badTextLine = l.getLineNumber();
                                n.badTextColumn = l.getColumnNumber();
                            }
                        }
                    }
                    default -> {
                    }
                }
            }
        } finally {
            r.close();
        }
        return root;
    }

    /** the parser's own text of what went wrong, without its "ParseError at [row,col]" prefix, so it reads the same everywhere */
    private static String detail(XMLStreamException e) {
        String m = e.getMessage();
        if (m == null) {
            return "invalid XML";
        }
        int i = m.indexOf("Message: ");
        if (i >= 0) {
            m = m.substring(i + "Message: ".length());
        }
        int nl = m.indexOf('\n');
        return (nl < 0 ? m : m.substring(0, nl)).trim();
    }
}
