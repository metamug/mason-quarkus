// THROWAWAY spike code (spikes/lazy-start): a stand-in for the Dev server's launcher and proxy.
// One backend process per name, started on the first request, stopped after an idle time. Plain JDK, run with: java Lazy.java ...
//
//   java Lazy.java --port 9100 --idle-ms 1500 --backend a=<dir>=<command with {port} and {dir}> [--backend ...]
//
// GET /b/<name>/<path>  starts the backend if it is not running and forwards the request to it
// GET /_lazy/events     JSON list of start and stop events (what the measurements read)
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class Lazy {

    static final class Backend {
        final String name;
        final Path dir;
        final String command;
        Process process;
        int port;
        CompletableFuture<Void> ready;
        volatile long lastUse;
        final AtomicInteger inFlight = new AtomicInteger();

        Backend(String name, Path dir, String command) {
            this.name = name;
            this.dir = dir;
            this.command = command;
        }
    }

    static final Map<String, Backend> backends = new LinkedHashMap<>();
    static final List<String> events = new ArrayList<>();
    static final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    static long idleMs = 1500;

    public static void main(String[] args) throws Exception {
        int port = 9100;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--idle-ms" -> idleMs = Long.parseLong(args[++i]);
                case "--backend" -> {
                    String[] p = args[++i].split("=", 3);
                    backends.put(p[0], new Backend(p[0], Path.of(p[1]), p[2]));
                }
                default -> throw new IllegalArgumentException(args[i]);
            }
        }
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/_lazy/events", x -> reply(x, 200, "[" + String.join(",", snapshot()) + "]"));
        server.createContext("/b/", Lazy::proxy);
        server.start();
        Thread reaper = new Thread(Lazy::reap, "reaper");
        reaper.setDaemon(true);
        reaper.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> backends.values().forEach(b -> {
            if (b.process != null) {
                b.process.destroyForcibly();
            }
        })));
        System.out.println("lazy launcher on :" + port + " idle " + idleMs + " ms, backends " + backends.keySet());
        Thread.currentThread().join();
    }

    static synchronized List<String> snapshot() {
        return new ArrayList<>(events);
    }

    static synchronized void event(String json) {
        events.add(json);
    }

    static void reply(HttpExchange x, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        x.sendResponseHeaders(status, b.length);
        x.getResponseBody().write(b);
        x.close();
    }

    static void proxy(HttpExchange x) throws IOException {
        String rest = x.getRequestURI().getPath().substring("/b/".length());
        int slash = rest.indexOf('/');
        String name = slash < 0 ? rest : rest.substring(0, slash);
        String path = slash < 0 ? "/" : rest.substring(slash);
        Backend b = backends.get(name);
        if (b == null) {
            reply(x, 404, "no such backend");
            return;
        }
        b.inFlight.incrementAndGet();
        try {
            ensureStarted(b).get(60, TimeUnit.SECONDS);
            HttpResponse<byte[]> r = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + b.port + path)).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            x.sendResponseHeaders(r.statusCode(), r.body().length);
            x.getResponseBody().write(r.body());
            x.close();
        } catch (Exception e) {
            reply(x, 502, "backend " + name + ": " + e);
        } finally {
            b.lastUse = System.nanoTime();
            b.inFlight.decrementAndGet();
        }
    }

    static synchronized CompletableFuture<Void> ensureStarted(Backend b) throws IOException {
        b.lastUse = System.nanoTime();
        if (b.process != null && b.process.isAlive()) {
            return b.ready;
        }
        if (b.port == 0) {
            b.port = 20000 + new java.util.ArrayList<>(backends.keySet()).indexOf(b.name); // fixed per backend, below the ephemeral range
        }
        String cmd = b.command.replace("{port}", String.valueOf(b.port)).replace("{dir}", b.dir.toAbsolutePath().toString());
        long t0 = System.nanoTime();
        ProcessBuilder pb = new ProcessBuilder(cmd.split(" +"));
        pb.redirectErrorStream(true);
        pb.redirectOutput(Path.of("lazy-" + b.name + ".log").toFile());
        b.process = pb.start();
        Process p = b.process;
        b.ready = CompletableFuture.runAsync(() -> {
            URI status = URI.create("http://127.0.0.1:" + b.port + "/status");
            while (p.isAlive()) {
                try {
                    HttpResponse<Void> r = client.send(HttpRequest.newBuilder(status).timeout(Duration.ofMillis(300)).build(),
                            HttpResponse.BodyHandlers.discarding());
                    if (r.statusCode() == 200) {
                        event("{\"event\":\"start\",\"backend\":\"" + b.name + "\",\"readyMs\":" + (System.nanoTime() - t0) / 1_000_000.0
                                + ",\"rssKb\":" + rss(p.pid()) + "}");
                        return;
                    }
                } catch (Exception e) {
                    // not up yet
                }
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    return;
                }
            }
            throw new IllegalStateException("backend exited before it was ready");
        });
        return b.ready;
    }

    static void reap() {
        for (;;) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                return;
            }
            for (Backend b : backends.values()) {
                Process p;
                synchronized (Lazy.class) {
                    p = b.process;
                    if (p == null || !p.isAlive() || !b.ready.isDone() || b.inFlight.get() > 0
                            || (System.nanoTime() - b.lastUse) / 1_000_000 < idleMs) {
                        continue;
                    }
                    b.process = null;
                }
                long t0 = System.nanoTime();
                p.destroy();
                try {
                    if (!p.waitFor(10, TimeUnit.SECONDS)) {
                        p.destroyForcibly().waitFor();
                    }
                } catch (InterruptedException e) {
                    return;
                }
                event("{\"event\":\"stop\",\"backend\":\"" + b.name + "\",\"stopMs\":" + (System.nanoTime() - t0) / 1_000_000.0
                        + ",\"exit\":" + p.exitValue() + "}");
            }
        }
    }

    static long rss(long pid) {
        try {
            Process ps = new ProcessBuilder("ps", "-o", "rss=", "-p", String.valueOf(pid)).start();
            String out = new String(ps.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return out.isEmpty() ? -1 : Long.parseLong(out);
        } catch (Exception e) {
            return -1;
        }
    }
}
