package io.mq.script;

import java.util.AbstractMap;
import java.util.Map;
import java.util.Set;

/**
 * What a Kotlin script sees as {@code params}: the request parameters as text, like the shop scripts expect
 * ({@code params["qty"]?.toIntOrNull()}). A non-generic class, because the Kotlin command-line compiler loses the type
 * arguments of generic provided properties.
 */
public final class Params extends AbstractMap<String, String> {

    private final Map<String, String> values;

    public Params(Map<String, String> values) {
        this.values = values;
    }

    @Override
    public Set<Entry<String, String>> entrySet() {
        return values.entrySet();
    }

    @Override
    public String get(Object key) {
        return values.get(key);
    }
}
