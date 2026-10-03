package io.mq.plugin;

import java.util.Map;

/**
 * A custom Java class an {@code <Execute classname="...">} step runs. Implement it in a plain Java project, list the class in
 * {@code META-INF/services/io.mq.plugin.Plugin}, and put the jar where the project declares its plugins. Classes are created with their
 * public no-argument constructor and are found through that service file, never by name: the native binary cannot load unknown classes.
 * Return JSON-ready values (Map, List, String, Number, Boolean); other objects are written with toString().
 */
public interface Plugin {

    /**
     * @param request the request parameters, the results of the earlier steps and a connection source
     * @param args the {@code <Arg>} values: a {@code value} is text with variables replaced, a {@code path} is the value an mpath finds
     */
    Object process(PluginRequest request, Map<String, Object> args) throws Exception;
}
