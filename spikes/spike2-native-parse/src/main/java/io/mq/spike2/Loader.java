package io.mq.spike2;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.mq.spike.parser.ResourceParser;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;

/**
 * Reads every *.xml from the configured folders at boot, validates and parses it, keeps the outcome per file.
 * A @Singleton (no client proxy): the status resource reads its fields directly.
 */
@Singleton
public class Loader {

    @ConfigProperty(name = "mq.resources.dirs")
    List<String> dirs;

    final Map<String, String> results = new TreeMap<>();
    volatile long firstPassNanos;
    volatile long secondPassNanos;
    volatile int files;
    volatile int valid;
    volatile long startedAtMillis = System.currentTimeMillis();

    void onStart(@Observes StartupEvent e) throws IOException {
        List<Path> xmlFiles = new ArrayList<>();
        for (String d : dirs) {
            Path dir = Path.of(d.trim());
            if (Files.isDirectory(dir)) {
                try (Stream<Path> s = Files.list(dir)) {
                    s.filter(p -> p.toString().endsWith(".xml")).sorted().forEach(xmlFiles::add);
                }
            }
        }
        long t0 = System.nanoTime();
        int ok = 0;
        for (Path p : xmlFiles) {
            ResourceParser.Result r = parse(p);
            results.put(p.getParent().getFileName() + "/" + p.getFileName(), r.summary());
            if (r.valid) {
                ok++;
            }
        }
        firstPassNanos = System.nanoTime() - t0;
        files = xmlFiles.size();
        valid = ok;
        // a second pass in the same process: the JIT/warm-up cost on the JVM versus an already compiled native image
        long t1 = System.nanoTime();
        for (Path p : xmlFiles) {
            parse(p);
        }
        secondPassNanos = System.nanoTime() - t1;
    }

    private static ResourceParser.Result parse(Path p) throws IOException {
        try (InputStream in = Files.newInputStream(p)) {
            return ResourceParser.parse(p.getFileName().toString().replaceAll("\\.xml$", ""), in);
        }
    }
}
