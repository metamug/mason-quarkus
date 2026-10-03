package io.mq.script;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarInputStream;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.mq.engine.Json;

/**
 * EXPERIMENTAL (measured in docs/reports/shared-host.md). A script loader for a backend that has no Kotlin compiler: it asks a shared
 * scripting host to compile, keeps the returned classes in memory (no files to lock on Windows) and runs the script by calling the
 * constructor of the compiled script class. A script is compiled again only when its file or the lib folder changed.
 */
public final class RemoteScriptLoader implements ScriptLoader {

    private record Loaded(String key, ClassLoader loader, String mainClass) {
    }

    private final URI host;
    private final Path scripts;
    private final Path lib;
    private final List<String> classpath;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Map<String, Loaded> cache = new ConcurrentHashMap<>();

    public RemoteScriptLoader(String hostUrl, Path scripts, Path lib, List<String> classpath) {
        this.host = URI.create(hostUrl.replaceAll("/+$", "") + "/export");
        this.scripts = scripts.toAbsolutePath();
        this.lib = lib == null ? null : lib.toAbsolutePath();
        this.classpath = classpath;
    }

    @Override
    public boolean has(String name) {
        return Files.isRegularFile(scripts.resolve(name + ".kts"));
    }

    @Override
    public void run(String name, Params params, Steps steps, Response response, RequestInfo request) throws Exception {
        Loaded l = load(name);
        Class<?> c = Class.forName(l.mainClass(), true, l.loader());
        // a compiled script is a class whose constructor takes the provided properties and runs the body
        for (var k : c.getConstructors()) {
            if (k.getParameterCount() == 4) {
                try {
                    k.newInstance(params, steps, response, request);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    Throwable t = e.getCause();
                    throw t instanceof Exception ex ? ex : new RuntimeException(t);
                }
                return;
            }
        }
        throw new IllegalStateException("script class " + l.mainClass() + " has no constructor (params, steps, response, request)");
    }

    private Loaded load(String name) throws Exception {
        String key = Files.getLastModifiedTime(scripts.resolve(name + ".kts")).toMillis() + "|" + libSignature();
        Loaded cached = cache.get(name);
        if (cached != null && cached.key().equals(key)) {
            return cached;
        }
        Map<String, Object> body = new HashMap<>();
        body.put("scripts", scripts.toString());
        body.put("lib", lib == null ? "" : lib.toString());
        body.put("name", name);
        body.put("classpath", classpath);
        HttpResponse<byte[]> r = client.send(HttpRequest.newBuilder(host).timeout(Duration.ofMinutes(2))
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body))).build(), HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() != 200) {
            throw new RuntimeException("script '" + name + "' does not compile: " + new String(r.body(), StandardCharsets.UTF_8));
        }
        Map<String, byte[]> classes = new HashMap<>();
        String main = null;
        try (JarInputStream jar = new JarInputStream(new ByteArrayInputStream(r.body()))) {
            if (jar.getManifest() != null) {
                main = jar.getManifest().getMainAttributes().getValue("Main-Class");
            }
            for (var e = jar.getNextJarEntry(); e != null; e = jar.getNextJarEntry()) {
                if (e.getName().endsWith(".class")) {
                    classes.put(e.getName().replace('/', '.').replaceAll("\\.class$", ""), jar.readAllBytes());
                }
            }
        }
        if (main == null) {
            throw new IllegalStateException("the compiled script " + name + " names no main class");
        }
        Loaded loaded = new Loaded(key, new MemoryClassLoader(classes, RemoteScriptLoader.class.getClassLoader()), main);
        cache.put(name, loaded);
        return loaded;
    }

    private String libSignature() throws IOException {
        if (lib == null || !Files.isDirectory(lib)) {
            return "";
        }
        try (Stream<Path> s = Files.walk(lib)) {
            return s.filter(p -> p.toString().endsWith(".kt")).sorted().map(p -> {
                try {
                    return lib.relativize(p) + ":" + Files.getLastModifiedTime(p).toMillis() + ":" + Files.size(p);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }).collect(Collectors.joining(";"));
        }
    }

    /** classes held in memory, defined on demand */
    private static final class MemoryClassLoader extends ClassLoader {
        private final Map<String, byte[]> classes;

        MemoryClassLoader(Map<String, byte[]> classes, ClassLoader parent) {
            super(parent);
            this.classes = classes;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] b = classes.get(name);
            if (b == null) {
                throw new ClassNotFoundException(name);
            }
            return defineClass(name, b, 0, b.length);
        }
    }
}
