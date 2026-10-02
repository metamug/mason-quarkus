package io.mq.spike.runtime;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;

/**
 * Spike model: resource name -> text of the first <Desc> element (stand-in for the parsed model).
 * Parsing uses StAX only. A file that does not parse keeps the previous model and records the error.
 */
public final class MqRegistry {

    public static final MqRegistry INSTANCE = new MqRegistry();

    /** identifies this JVM-side registry; the extension jar is not reloaded, so it survives app restarts */
    public final String registryId = UUID.randomUUID().toString().substring(0, 8);

    private final Map<String, String> model = new ConcurrentHashMap<>();
    private volatile String lastError = "";
    private volatile long lastReloadNanos;
    private volatile long lastReloadMillisEpoch;
    private volatile int reloadCount;
    private volatile String lastChangedSet = "";
    private volatile List<Path> roots = List.of();
    private volatile io.quarkus.dev.spi.HotReplacementContext context;

    private MqRegistry() {
    }

    public void init(io.quarkus.dev.spi.HotReplacementContext ctx) {
        this.context = ctx;
        String external = System.getProperty("mq.dir");
        this.roots = external != null ? List.of(Path.of(external).getParent()) : ctx.getResourcesDir();
        this.folderName = external != null ? Path.of(external).getFileName().toString() : "mq";
        loadAll();
        String watch = System.getProperty("mq.watch", "false");
        if (!watch.equals("false")) {
            startWatcher(roots.get(0).resolve(folderName), watch.equals("doscan"));
        }
    }

    private volatile String folderName = "mq";
    private volatile int watcherReloads;

    /** independent of Quarkus: re-read the whole folder (debounced) when anything in it changes */
    private void startWatcher(Path dir, boolean viaDoScan) {
        Thread t = new Thread(() -> {
            try (java.nio.file.WatchService ws = dir.getFileSystem().newWatchService()) {
                dir.register(ws, java.nio.file.StandardWatchEventKinds.ENTRY_CREATE, java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY,
                        java.nio.file.StandardWatchEventKinds.ENTRY_DELETE);
                for (;;) {
                    java.nio.file.WatchKey key = ws.take();
                    Thread.sleep(15); // debounce: let a burst of writes finish
                    key.pollEvents();
                    key.reset();
                    long t0 = System.nanoTime();
                    if (viaDoScan) {
                        context.doScan(true); // Quarkus scans and calls consumeNoRestartChanges -> reload(changed)
                    } else {
                        loadAll();
                    }
                    watcherReloads++;
                    lastReloadNanos = System.nanoTime() - t0;
                    lastReloadMillisEpoch = System.currentTimeMillis();
                }
            } catch (Exception e) {
                lastError = "watcher: " + e;
            }
        }, "mq-watcher");
        t.setDaemon(true);
        t.start();
    }

    public void loadAll() {
        Map<String, String> fresh = new ConcurrentHashMap<>();
        for (Path root : roots) {
            Path dir = root.resolve(folderName);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(p -> p.toString().endsWith(".xml")).forEach(p -> {
                    try {
                        fresh.put(name(p), parse(Files.readString(p, StandardCharsets.UTF_8)));
                    } catch (Exception e) {
                        lastError = name(p) + ": " + e.getMessage();
                        String previous = model.get(name(p));
                        if (previous != null) {
                            fresh.put(name(p), previous); // keep serving the old model of this file
                        }
                    }
                });
            } catch (IOException e) {
                lastError = e.toString();
            }
        }
        model.keySet().retainAll(fresh.keySet());
        model.putAll(fresh);
    }

    /** called by Quarkus with the changed relative paths when no restart is needed */
    public void reload(Set<String> changed) {
        long t0 = System.nanoTime();
        lastChangedSet = changed.toString();
        for (String rel : changed) {
            String n = rel.substring(rel.lastIndexOf('/') + 1).replace(".xml", "");
            Path file = null;
            for (Path root : roots) {
                Path p = root.resolve(rel);
                if (Files.exists(p)) {
                    file = p;
                    break;
                }
            }
            if (file == null) {
                model.remove(n); // deleted
                continue;
            }
            try {
                model.put(n, parse(Files.readString(file, StandardCharsets.UTF_8)));
                lastError = "";
            } catch (Exception e) {
                lastError = n + ": " + e.getMessage(); // keep the old model
            }
        }
        reloadCount++;
        lastReloadNanos = System.nanoTime() - t0;
        lastReloadMillisEpoch = System.currentTimeMillis();
    }

    public boolean scanNow() throws Exception {
        return context != null && context.doScan(true);
    }

    public String get(String name) {
        return model.get(name);
    }

    public String status() {
        return "{\"registryId\":\"" + registryId + "\",\"resources\":" + model.keySet().stream().sorted().toList().toString().replace("[", "[\"").replace("]", "\"]").replace(", ", "\",\"")
                + ",\"reloadCount\":" + reloadCount + ",\"lastReloadMicros\":" + lastReloadNanos / 1000 + ",\"lastReloadAt\":" + lastReloadMillisEpoch
                + ",\"watcherReloads\":" + watcherReloads + ",\"lastChanged\":\"" + lastChangedSet.replace("\"", "'") + "\",\"lastError\":\"" + lastError.replace("\"", "'").replace("\n", " ") + "\"}";
    }

    private static String name(Path p) {
        return p.getFileName().toString().replace(".xml", "");
    }

    private static String parse(String xml) throws Exception {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        XMLStreamReader r = f.createXMLStreamReader(new StringReader(xml));
        String desc = "";
        boolean sawRoot = false;
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                if (!sawRoot) {
                    if (!r.getLocalName().equals("Resource")) {
                        throw new IllegalStateException("root element must be Resource, found " + r.getLocalName());
                    }
                    sawRoot = true;
                } else if (r.getLocalName().equals("Desc")) {
                    desc = r.getElementText();
                }
            }
        }
        return desc;
    }
}
