package io.mq.quarkus.runtime;

import java.io.IOException;
import java.nio.file.Path;

import org.jboss.logging.Logger;

import io.mq.core.reload.FolderWatcher;
import io.mq.core.reload.ResourceSet;
import io.mq.core.reload.ResourceStore;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * The resources MQ serves. Loads the folder at start; in dev mode (JVM) it also watches the folder and swaps the whole model
 * atomically on change. In production nothing is watched: a resource change means a new pod.
 * Take one {@link #current()} snapshot per request.
 */
@Singleton
public class MqResources {

    private static final Logger LOG = Logger.getLogger(MqResources.class);

    @Inject
    MqConfig config;

    private final ResourceStore store = new ResourceStore();
    private volatile FolderWatcher watcher;

    public ResourceSet current() {
        return store.current();
    }

    /** "jdk", "native-events" or "none" (production, or watch=false) */
    public String watcherKind() {
        FolderWatcher w = watcher;
        return w == null ? "none" : w.kind();
    }

    public ResourceStore store() {
        return store;
    }

    void onStart(@Observes StartupEvent e) {
        Path dir = Path.of(config.dir());
        ResourceSet set = store.reload(dir);
        LOG.infof("MQ loaded %d resources from %s in %d ms%s", set.resources().size(), dir.toAbsolutePath(), set.reloadMicros() / 1000,
                set.problems().isEmpty() ? "" : ", problems: " + set.problemSummary());
        store.onReload(s -> {
            if (s.problems().isEmpty()) {
                LOG.infof("MQ reloaded %d resources in %d ms", s.resources().size(), s.reloadMicros() / 1000);
            } else {
                LOG.warnf("MQ reloaded with problems (old versions kept): %s", s.problemSummary());
            }
        });
        if (LaunchMode.current() == LaunchMode.DEVELOPMENT && config.watch()) {
            try {
                watcher = FolderWatcher.start(dir, () -> store.reload(dir), config.debounceMillis());
                LOG.infof("MQ watches %s (%s)", dir.toAbsolutePath(), watcher.kind());
            } catch (IOException | RuntimeException ex) {
                LOG.warnf("MQ cannot watch %s: %s", dir, ex);
            }
        }
    }

    void onStop(@Observes ShutdownEvent e) throws IOException {
        if (watcher != null) {
            watcher.close();
        }
    }
}
