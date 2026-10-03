package io.mq.script;

/** What a Kotlin script sees as {@code request}: the path values and the method. {@code id} and {@code pid} are null when the path has none. */
public final class RequestInfo {

    private final String id;
    private final String pid;
    private final String uid;
    private final String method;

    public RequestInfo(String id, String pid, String uid, String method) {
        this.id = id;
        this.pid = pid;
        this.uid = uid;
        this.method = method;
    }

    public String getId() {
        return id;
    }

    public String getPid() {
        return pid;
    }

    public String getUid() {
        return uid;
    }

    public String getMethod() {
        return method;
    }
}
