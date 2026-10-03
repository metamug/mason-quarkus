package io.mq.script;

/**
 * Runs a script by name. Two implementations exist: the generated registry of scripts compiled ahead of time (production and the
 * native binary; found with {@link java.util.ServiceLoader}), and the Dev server's loader, which compiles and evaluates the .kts
 * file on the JVM and notices changes. Both give the script the same four names.
 */
public interface ScriptLoader {

    /** whether a script of this name (without .kts) is available */
    boolean has(String name);

    /** runs the script; it writes its result into {@code response} */
    void run(String name, Params params, Steps steps, Response response, RequestInfo request) throws Exception;
}
