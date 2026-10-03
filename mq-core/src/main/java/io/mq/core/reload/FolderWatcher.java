package io.mq.core.reload;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Calls a callback after any change in a folder, once per burst: events are debounced, so writing 20 files produces one call
 * (or very few) after the last write. The callback is expected to re-read the whole folder; the kind of change does not matter.
 * Meant for development; a production process does not watch.
 *
 * <p>Which implementation: the JDK {@link java.nio.file.WatchService} on Linux and Windows (native file events). On macOS the JDK
 * service polls (about 2 s measured on GitHub's macOS runners), so there the file-system-events implementation is used when the optional
 * library {@code io.methvin:directory-watcher} is on the classpath. Force one with {@code -Dmq.watcher=jdk} or {@code -Dmq.watcher=native}.
 */
public interface FolderWatcher extends AutoCloseable {

    /** starts watching; the folder must exist */
    static FolderWatcher start(Path dir, Runnable onChange, long debounceMillis) throws IOException {
        String choice = System.getProperty("mq.watcher", "auto");
        boolean mac = System.getProperty("os.name", "").toLowerCase().contains("mac");
        if (choice.equals("native") || (choice.equals("auto") && mac)) {
            try {
                // by name, so that code which never watches (a native production binary) does not pull the library into the image
                Class<?> c = Class.forName("io.mq.core.reload.NativeEventsFolderWatcher");
                return (FolderWatcher) c.getMethod("start", Path.class, Runnable.class, long.class).invoke(null, dir, onChange, debounceMillis);
            } catch (ReflectiveOperationException | LinkageError e) {
                if (choice.equals("native")) {
                    throw new IOException("native file events requested but not available: " + e, e);
                }
                // fall through to the JDK watcher
            }
        }
        return JdkFolderWatcher.start(dir, onChange, debounceMillis);
    }

    /** which implementation is running, for logs and results: "jdk" or "native-events" */
    String kind();

    @Override
    void close() throws IOException;
}
