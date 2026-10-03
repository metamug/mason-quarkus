package io.mq.plugin;

import java.util.Map;

/**
 * Finds plugins by class name. Two implementations: the plugins on the application class path (production and native; found with
 * {@link java.util.ServiceLoader}, registered in the native image at build time), and the one of the Dev server, which reads jars from a
 * folder and notices changed jars.
 */
public interface PluginLoader {

    /** class name to plugin instance; may be a different map after a jar changed */
    Map<String, Plugin> plugins();
}
