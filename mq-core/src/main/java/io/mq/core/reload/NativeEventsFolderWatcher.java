package io.mq.core.reload;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import io.methvin.watcher.DirectoryWatcher;
import io.methvin.watchservice.MacOSXListeningWatchService;

/**
 * FolderWatcher on the operating system's file events through the optional library io.methvin:directory-watcher
 * (FSEvents on macOS). Loaded by name from {@link FolderWatcher#start}; nothing else refers to it.
 */
public final class NativeEventsFolderWatcher implements FolderWatcher {

    private final DirectoryWatcher watcher;
    private final ScheduledExecutorService timer;
    private ScheduledFuture<?> pending;

    private NativeEventsFolderWatcher(Path dir, Runnable onChange, long debounceMillis) throws IOException {
        this.timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mq-watcher");
            t.setDaemon(true);
            return t;
        });
        DirectoryWatcher.Builder b = DirectoryWatcher.builder().path(dir).fileHashing(false).listener(event -> schedule(onChange, debounceMillis));
        if (System.getProperty("os.name", "").toLowerCase().contains("mac")) {
            // FSEvents coalesces events for its latency, 0.5 s by default; we debounce ourselves
            b.watchService(new MacOSXListeningWatchService(new MacOSXListeningWatchService.Config() {
                @Override
                public double latency() {
                    return 0.01;
                }
            }));
        }
        this.watcher = b.build();
    }

    public static FolderWatcher start(Path dir, Runnable onChange, long debounceMillis) throws IOException {
        NativeEventsFolderWatcher w = new NativeEventsFolderWatcher(dir, onChange, debounceMillis);
        w.watcher.watchAsync();
        return w;
    }

    /** restarts the quiet-time countdown; the callback runs once the folder has been quiet for the debounce time */
    private synchronized void schedule(Runnable onChange, long debounceMillis) {
        if (pending != null) {
            pending.cancel(false);
        }
        pending = timer.schedule(() -> {
            try {
                onChange.run();
            } catch (RuntimeException e) {
                // keep watching
            }
        }, debounceMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public String kind() {
        return "native-events";
    }

    @Override
    public void close() throws IOException {
        watcher.close();
        timer.shutdownNow();
    }
}
