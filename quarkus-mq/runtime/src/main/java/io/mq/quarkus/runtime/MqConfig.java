package io.mq.quarkus.runtime;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/** quarkus.mq.* : read at run time, so the folder can be set when the container starts */
@ConfigMapping(prefix = "quarkus.mq")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface MqConfig {

    /** folder with the resource XML files (every *.xml in it, not recursive) */
    @WithDefault("mq")
    String dir();

    /** folder with the Kotlin scripts (name.kts); the Dev server compiles them from here */
    @WithDefault("scripts")
    String scriptsDir();

    /** EXPERIMENTAL: url of a shared scripting host that compiles scripts for many backends, so this backend needs no Kotlin compiler (empty: none) */
    java.util.Optional<String> scriptHost();

    /** folder with ordinary Kotlin files shared by the scripts (compiled before them) */
    @WithDefault("lib")
    String libDir();

    /**
     * where scripts come from: compiled (generated registry in the application, production and native), interpreted (the Dev
     * server compiles the .kts files and notices changes) or auto (interpreted in dev mode when the compiler is on the class path)
     */
    @WithDefault("auto")
    String scripts();

    /** folder with plugin jars (custom classes of Execute steps); the Dev server loads them from here and notices changes */
    @WithDefault("plugins")
    String pluginsDir();

    /** where plugins come from: classpath (registered services of the application; production and native), directory (jars in plugins-dir) or auto (directory in dev mode) */
    @WithDefault("auto")
    String plugins();

    /** backend properties: {{name}} in an XRequest url is replaced by quarkus.mq.properties.name */
    java.util.Map<String, String> properties();

    /** seconds to wait for an XRequest to connect and to answer */
    @WithDefault("15")
    int xrequestTimeoutSeconds();

    /** dev mode only: watch the folder and reload on change. Ignored in production, where nothing is watched. */
    @WithDefault("true")
    boolean watch();

    /** dev mode only: quiet time in milliseconds after the last file event before the folder is re-read */
    @WithDefault("15")
    long debounceMillis();
}
