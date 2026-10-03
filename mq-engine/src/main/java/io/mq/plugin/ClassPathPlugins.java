package io.mq.plugin;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ServiceLoader;

/** The plugins registered in {@code META-INF/services/io.mq.plugin.Plugin} on the class path. */
public final class ClassPathPlugins implements PluginLoader {

    private final Map<String, Plugin> plugins = new LinkedHashMap<>();

    public ClassPathPlugins() {
        for (Plugin p : ServiceLoader.load(Plugin.class)) {
            plugins.put(p.getClass().getName(), p);
        }
    }

    @Override
    public Map<String, Plugin> plugins() {
        return plugins;
    }
}
