package io.mq.script;

import java.util.AbstractMap;
import java.util.Map;
import java.util.Set;

/**
 * What a Kotlin script sees as {@code steps}: the results of the steps run before it, by step id. A query is a list of row maps, an
 * update a map with {@code updated}, a script or XRequest a map, a Text a string. Row keys keep the case the database reports.
 */
public final class Steps extends AbstractMap<String, Object> {

    private final Map<String, Object> values;

    public Steps(Map<String, Object> values) {
        this.values = values;
    }

    @Override
    public Set<Entry<String, Object>> entrySet() {
        return values.entrySet();
    }

    @Override
    public Object get(Object key) {
        return values.get(key);
    }
}
