package io.mq.core.reload;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

import io.mq.core.model.Model;
import io.mq.core.parser.ParseResult;
import io.mq.core.parser.Problem;
import io.mq.core.parser.ResourceParser;

/**
 * Holds the current {@link ResourceSet} and replaces it atomically.
 * A reload re-reads the whole folder and publishes one new snapshot. A file that does not validate keeps serving its last valid
 * version (if it ever had one) and its problems are recorded; a file that was deleted disappears.
 */
public final class ResourceStore {

    private final AtomicReference<ResourceSet> current = new AtomicReference<>(ResourceSet.EMPTY);
    private final List<Consumer<ResourceSet>> listeners = new CopyOnWriteArrayList<>();

    public ResourceSet current() {
        return current.get();
    }

    /** called after each reload with the new snapshot, on the thread that reloaded */
    public void onReload(Consumer<ResourceSet> listener) {
        listeners.add(listener);
    }

    /** re-reads every *.xml file of the folder and publishes the result; returns the new snapshot */
    public synchronized ResourceSet reload(Path dir) {
        long t0 = System.nanoTime();
        ResourceSet previous = current.get();
        Map<String, Model.Resource> resources = new HashMap<>();
        Map<String, List<Problem>> problems = new HashMap<>();
        List<Path> files = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> s = Files.list(dir)) {
                s.filter(p -> p.getFileName().toString().endsWith(".xml")).forEach(files::add);
            } catch (IOException e) {
                problems.put(dir.toString(), List.of(new Problem(dir.toString(), 0, 0, "cannot list the folder: " + e)));
            }
        } else {
            problems.put(dir.toString(), List.of(new Problem(dir.toString(), 0, 0, "resource folder not found")));
        }
        for (Path f : files) {
            String file = f.getFileName().toString();
            String name = file.substring(0, file.length() - ".xml".length());
            ParseResult r;
            try {
                r = ResourceParser.parse(f);
            } catch (IOException e) {
                r = new ParseResult(file, null, List.of(new Problem(file, 0, 0, "cannot read the file: " + e)));
            }
            if (r.valid()) {
                resources.put(name, r.resource());
            } else {
                problems.put(file, r.problems());
                Model.Resource old = previous.resources().get(name);
                if (old != null) {
                    resources.put(name, old);
                }
            }
        }
        ResourceSet next = new ResourceSet(previous.generation() + 1, resources, problems, (System.nanoTime() - t0) / 1000);
        current.set(next);
        for (Consumer<ResourceSet> l : listeners) {
            try {
                l.accept(next);
            } catch (RuntimeException e) {
                // a bad listener must not stop the others or the reload
            }
        }
        return next;
    }
}
