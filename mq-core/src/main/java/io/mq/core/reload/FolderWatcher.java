package io.mq.core.reload;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.concurrent.TimeUnit;

/**
 * Calls a callback after any change in a folder, once per burst: events are debounced, so writing 20 files produces one call
 * (or very few) after the last write. The callback is expected to re-read the whole folder; the kind of change does not matter.
 * Uses the JDK {@link WatchService}. Meant for development; a production process does not watch.
 */
public final class FolderWatcher implements AutoCloseable {

    private final WatchService service;
    private final Thread thread;

    private FolderWatcher(Path dir, Runnable onChange, long debounceMillis) throws IOException {
        this.service = dir.getFileSystem().newWatchService();
        dir.register(service, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
        this.thread = new Thread(() -> run(onChange, debounceMillis), "mq-watcher");
        this.thread.setDaemon(true);
    }

    /** starts watching; the folder must exist */
    public static FolderWatcher start(Path dir, Runnable onChange, long debounceMillis) throws IOException {
        FolderWatcher w = new FolderWatcher(dir, onChange, debounceMillis);
        w.thread.start();
        return w;
    }

    private void run(Runnable onChange, long debounceMillis) {
        try {
            for (;;) {
                WatchKey key = service.take();
                // wait until the folder has been quiet for the debounce time
                while (key != null) {
                    key.pollEvents();
                    key.reset();
                    key = service.poll(debounceMillis, TimeUnit.MILLISECONDS);
                }
                try {
                    onChange.run();
                } catch (RuntimeException e) {
                    // keep watching
                }
            }
        } catch (ClosedWatchServiceException | InterruptedException e) {
            // closed
        }
    }

    @Override
    public void close() throws IOException {
        service.close();
        thread.interrupt();
    }
}
