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

    /**
     * where scripts come from: compiled (generated registry in the application, production and native), interpreted (the Dev
     * server compiles the .kts files and notices changes) or auto (interpreted in dev mode when the compiler is on the class path)
     */
    @WithDefault("auto")
    String scripts();

    /** dev mode only: watch the folder and reload on change. Ignored in production, where nothing is watched. */
    @WithDefault("true")
    boolean watch();

    /** dev mode only: quiet time in milliseconds after the last file event before the folder is re-read */
    @WithDefault("15")
    long debounceMillis();
}
