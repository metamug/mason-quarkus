package io.mq.hosting;

import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.yaml.snakeyaml.Yaml;

import io.agroal.api.AgroalDataSource;
import io.agroal.api.configuration.supplier.AgroalDataSourceConfigurationSupplier;
import io.agroal.api.security.NamePrincipal;
import io.agroal.api.security.SimplePassword;
import io.quarkus.runtime.StartupEvent;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.Router;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * THROWAWAY (Spike 3). Two questions:
 * B: datasources are created at boot from each backend's backend.yaml (Agroal's programmatic API), not from build-time config.
 * A (option 2): one process, many backends, each with its own classloader and route prefix, added and removed at runtime.
 */
@Singleton
public class Backends {

    static final class Backend {
        final String name;
        final Map<String, AgroalDataSource> datasources = new LinkedHashMap<>();
        URLClassLoader loader;
        Route route;

        Backend(String name) {
            this.name = name;
        }
    }

    @ConfigProperty(name = "mq.backends.dir")
    String dir;

    @Inject
    Router router;

    final Map<String, Backend> backends = new ConcurrentHashMap<>();
    /** a backend whose datasources could not be created is reported, it does not stop the process from starting */
    final Map<String, String> errors = new ConcurrentHashMap<>();
    final List<WeakReference<ClassLoader>> removedLoaders = new ArrayList<>();

    // ---- question B: datasources from backend.yaml, at boot ----

    void onStart(@Observes StartupEvent e) throws IOException {
        Path root = Path.of(dir);
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> s = Files.list(root)) {
            for (Path p : (Iterable<Path>) s.sorted()::iterator) {
                Path yaml = p.resolve("backend.yaml");
                if (Files.isRegularFile(yaml)) {
                    try {
                        loadBackend(p.getFileName().toString(), yaml);
                    } catch (Throwable t) {
                        errors.put(p.getFileName().toString(), t.toString());
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    void loadBackend(String name, Path yamlFile) throws IOException {
        Backend b = new Backend(name);
        try (InputStream in = Files.newInputStream(yamlFile)) {
            Map<String, Object> doc = new Yaml().load(in);
            Map<String, Map<String, Object>> dss = (Map<String, Map<String, Object>>) doc.get("datasources");
            for (Map.Entry<String, Map<String, Object>> ds : dss.entrySet()) {
                Map<String, Object> c = ds.getValue();
                b.datasources.put(ds.getKey(), create(String.valueOf(c.get("kind")), env(String.valueOf(c.get("url"))),
                        env(String.valueOf(c.get("user"))), env(String.valueOf(c.get("password")))));
            }
        }
        backends.put(name, b);
    }

    /** ${NAME} is replaced from the environment at boot; an unset name is an error, never an empty password */
    static String env(String value) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < value.length()) {
            int s = value.indexOf("${", i);
            if (s < 0) {
                out.append(value, i, value.length());
                break;
            }
            int e = value.indexOf('}', s);
            String var = value.substring(s + 2, e);
            String v = System.getenv(var);
            if (v == null) {
                throw new IllegalStateException("environment variable " + var + " is not set");
            }
            out.append(value, i, s).append(v);
            i = e + 1;
        }
        return out.toString();
    }

    static AgroalDataSource create(String kind, String url, String user, String password) {
        String driver;
        switch (kind) {
            case "postgresql":
                driver = "org.postgresql.Driver";
                break;
            case "hsqldb":
                driver = "org.hsqldb.jdbc.JDBCDriver";
                break;
            default:
                throw new IllegalArgumentException("unsupported datasource kind " + kind);
        }
        try {
            AgroalDataSourceConfigurationSupplier cfg = new AgroalDataSourceConfigurationSupplier()
                    .connectionPoolConfiguration(p -> p.minSize(0).maxSize(4).acquisitionTimeout(Duration.ofSeconds(5))
                            .connectionFactoryConfiguration(f -> f.jdbcUrl(url).connectionProviderClassName(driver)
                                    .principal(new NamePrincipal(user)).credential(new SimplePassword(password))));
            return AgroalDataSource.from(cfg);
        } catch (Exception ex) {
            throw new IllegalStateException("cannot create datasource: " + ex, ex);
        }
    }

    /** runs a trivial query on every datasource of a backend and reports driver, database and the query result */
    String probe(String name) throws Exception {
        if (errors.containsKey(name)) {
            return "{\"backend\":\"" + name + "\",\"error\":\"" + errors.get(name).replace("\"", "'").replace("\n", " ") + "\"}";
        }
        Backend b = backends.get(name);
        if (b == null) {
            return "{\"error\":\"no such backend\"}";
        }
        StringBuilder sb = new StringBuilder("{\"backend\":\"" + name + "\",\"datasources\":{");
        boolean first = true;
        for (Map.Entry<String, AgroalDataSource> e : b.datasources.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            try (Connection c = e.getValue().getConnection(); Statement st = c.createStatement()) {
                String product = c.getMetaData().getDatabaseProductName();
                String driver = c.getMetaData().getDriverName();
                boolean hsql = product.toLowerCase().contains("hsql");
                try (ResultSet rs = st.executeQuery(hsql ? "VALUES (1)" : "SELECT 1")) {
                    rs.next();
                    sb.append('"').append(e.getKey()).append("\":{\"product\":\"").append(product).append("\",\"driver\":\"").append(driver)
                            .append("\",\"select1\":").append(rs.getInt(1)).append('}');
                }
            }
        }
        return sb.append("}}").toString();
    }

    // ---- question A, option 2: add/remove a backend in a running process ----

    /** adds a backend: its own classloader (a class compiled at runtime into a temp folder) and its own route prefix */
    String add(String name) throws Exception {
        Backend b = new Backend(name);
        Path tmp = Files.createTempDirectory("mq-backend-" + name);
        Path src = tmp.resolve("Hello.java");
        Files.writeString(src, "public class Hello { public String hi() { return \"hi from " + name + "\"; } }");
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null || javac.run(null, null, null, src.toString()) != 0) {
            throw new IllegalStateException("no compiler available (the in-process option is JVM only in this spike)");
        }
        b.loader = new URLClassLoader(new URL[] { tmp.toUri().toURL() }, getClass().getClassLoader());
        Class<?> hello = b.loader.loadClass("Hello");
        Object instance = hello.getDeclaredConstructor().newInstance();
        String said = (String) hello.getMethod("hi").invoke(instance);
        b.route = router.get("/b/" + name + "/ping").handler(ctx -> ctx.response().end(said));
        backends.put(name, b);
        return said;
    }

    boolean remove(String name) {
        Backend b = backends.remove(name);
        if (b == null) {
            return false;
        }
        if (b.route != null) {
            b.route.remove();
        }
        if (b.loader != null) {
            removedLoaders.add(new WeakReference<>(b.loader));
        }
        return true;
    }

    /** after removing backends: can the JVM unload their classloaders? */
    String unloadCheck() throws Exception {
        for (int i = 0; i < 20; i++) {
            System.gc();
            Thread.sleep(50);
        }
        long cleared = removedLoaders.stream().filter(r -> r.get() == null).count();
        return "{\"removedLoaders\":" + removedLoaders.size() + ",\"unloaded\":" + cleared + "}";
    }
}
