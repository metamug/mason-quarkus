package io.mq.core.parser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** a light element tree built from the StAX events: just what the checker needs, with source positions */
final class Node {
    final String local;
    final String uri;
    final int line;
    final int column;
    /** attributes in document order; key is the local name, or "{uri}local" for a namespaced attribute */
    final Map<String, String> attrs = new LinkedHashMap<>();
    final List<Node> kids = new ArrayList<>();
    final StringBuilder text = new StringBuilder();
    /** position of the first character data that is not white space, or 0 */
    int badTextLine;
    int badTextColumn;

    Node(String local, String uri, int line, int column) {
        this.local = local;
        this.uri = uri;
        this.line = line;
        this.column = column;
    }

    boolean hasNonBlankText() {
        return badTextLine > 0;
    }
}
