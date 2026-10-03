package io.mq.engine;

import java.util.Map;

/** what the engine answers: HTTP status, response headers and the JSON-ready body (maps, lists, strings, numbers) */
public record Reply(int status, Map<String, String> headers, Object body) {

    public String json() {
        return Json.write(body);
    }
}
