package io.mq.core.reload;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.concurrent.TimeUnit;

/** FolderWatcher on the JDK WatchService: native file events on Linux and Windows, polling on macOS */
final class JdkFolderWatcher implements FolderWatcher {

    private final WatchService service;
    private final Thread thread;

    private JdkFolderWatcher(Path dir, Runnable onChange, long debounceMillis) throws IOException {
        this.service = dir.getFileSystem().newWatchService();
        dir.register(service, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
        this.thread = new Thread(() -> run(onChange, debounceMillis), "mq-watcher");
        this.thread.setDaemon(true);
    }

    static FolderWatcher start(Path dir, Runnable onChange, long debounceMillis) throws IOException {
        JdkFolderWatcher w = new JdkFolderWatcher(dir, onChange, debounceMillis);
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
    public String kind() {
        return "jdk";
    }

    @Override
    public void close() throws IOException {
        service.close();
        thread.interrupt();
    }
}
