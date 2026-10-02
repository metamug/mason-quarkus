package io.mq.runtime;

public class MqStep {
    /** "sql" or "text" */
    public String kind;
    public String id;
    /** sql: "query" or "update" */
    public String type;
    /** sql: the statement with $name placeholders; text: the literal */
    public String text;
    public boolean output;
}
