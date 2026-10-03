package io.mq.core.model;

import java.util.List;

/**
 * The parsed form of one R2 resource XML file. Plain immutable records, no annotations, no reflection:
 * the same objects serve the Dev server, the CLI and the runtime, on the JVM and in a native binary.
 * Optional attributes are null when absent, except where the schema gives a default (resolved here).
 */
public final class Model {

    private Model() {
    }

    public record Resource(String name, String version, String parent, String auth, String desc, List<Request> requests) {
    }

    public record Request(String method, String item, Integer status, String desc, List<Header> headers, List<Param> params,
            List<Step> steps, int line) {
    }

    public record Header(String name, String value, int line) {
    }

    public record Param(String name, String type, boolean required, String max, String min, String maxlength, String minlength,
            String pattern, String exists, String value, String testvalue, int line) {
    }

    /** a step of a request: Sql, Transaction, XRequest, Script, Text or Execute, in document order */
    public sealed interface Step permits Sql, Transaction, XRequest, Script, Text, Execute {
        /** null for a Transaction, which has no id */
        String id();

        int line();
    }

    public record Sql(String id, String type, String datasource, String requires, String ref, String when, String onblank,
            String onerror, boolean verbose, boolean output, String limit, String offset, String classname, Integer status,
            String text, int line) implements Step {
    }

    public record Transaction(String when, String datasource, List<Sql> statements, int line) implements Step {
        @Override
        public String id() {
            return null;
        }
    }

    public record XRequest(String id, String when, String url, String method, Boolean verbose, String output, String classname,
            List<Header> headers, List<Header> params, String body, int line) implements Step {
    }

    public record Script(String id, String file, boolean output, String when, int line) implements Step {
    }

    public record Text(String id, String when, boolean output, String text, int line) implements Step {
    }

    public record Execute(String id, String requires, String when, String onerror, String classname, Boolean verbose,
            Boolean output, Integer status, List<Arg> args, int line) implements Step {
    }

    public record Arg(String name, String value, String path) {
    }
}
