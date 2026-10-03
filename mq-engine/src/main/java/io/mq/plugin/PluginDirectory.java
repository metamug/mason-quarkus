package io.mq.plugin;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.stream.Stream;

/**
 * The plugin loader of the Dev server: every {@code *.jar} in a folder, in a class loader of their own. When a jar is added, removed or
 * changed, the next call builds a new class loader and new instances (the old ones are garbage collected), so a plugin can be redeployed
 * without restarting.
 */
public final class PluginDirectory implements PluginLoader {

    private final Path dir;
    private final ClassLoader parent;
    private volatile String signature = "";
    private volatile Map<String, Plugin> current = Map.of();
    private URLClassLoader loader;
    private Path copiesDir;

    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            // temp files: the operating system cleans them up
        }
    }

    public PluginDirectory(Path dir, ClassLoader parent) {
        this.dir = dir;
        this.parent = parent;
    }

    @Override
    public synchronized Map<String, Plugin> plugins() {
        List<Path> jars = new ArrayList<>();
        StringBuilder sig = new StringBuilder();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> s = Files.list(dir)) {
                s.filter(p -> p.toString().endsWith(".jar")).sorted().forEach(jars::add);
                for (Path j : jars) {
                    sig.append(j.getFileName()).append(Files.getLastModifiedTime(j).toMillis()).append(Files.size(j)).append(';');
                }
            } catch (IOException e) {
                throw new IllegalStateException("cannot read the plugin folder " + dir, e);
            }
        }
        if (!sig.toString().equals(signature)) {
            try {
                // load copies: on Windows a jar that is open cannot be replaced or deleted, which would block redeploying it
                Path copies = Files.createTempDirectory("mq-plugins");
                URL[] urls = new URL[jars.size()];
                for (int i = 0; i < urls.length; i++) {
                    Path copy = copies.resolve(jars.get(i).getFileName());
                    Files.copy(jars.get(i), copy);
                    urls[i] = copy.toUri().toURL();
                }
                URLClassLoader next = new URLClassLoader(urls, parent);
                Map<String, Plugin> found = new LinkedHashMap<>();
                for (Plugin p : ServiceLoader.load(Plugin.class, next)) {
                    found.put(p.getClass().getName(), p);
                }
                URLClassLoader old = loader;
                Path oldCopies = copiesDir;
                loader = next;
                copiesDir = copies;
                current = found;
                signature = sig.toString();
                if (old != null) {
                    old.close();
                    deleteQuietly(oldCopies);
                }
            } catch (IOException e) {
                throw new IllegalStateException("cannot load plugins from " + dir, e);
            }
        }
        return current;
    }
}
