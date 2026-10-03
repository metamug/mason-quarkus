package io.mq.script;

import java.util.LinkedHashMap;

/** What a Kotlin script sees as {@code response}: it fills it, and the map becomes the step's result (and its output, when the step outputs). */
public final class Response extends LinkedHashMap<String, Object> {

    private static final long serialVersionUID = 1L;
}
