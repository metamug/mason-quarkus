package io.mq.spike2;

import java.nio.file.Files;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;

@Path("/")
public class StatusResource {

    @Inject
    Loader loader;

    @GET
    @Path("/status")
    @Produces("application/json")
    public String status() {
        boolean nativeImage = System.getProperty("org.graalvm.nativeimage.imagecode") != null;
        return "{\"mode\":\"" + (nativeImage ? "native" : "jvm") + "\",\"files\":" + loader.files + ",\"valid\":" + loader.valid
                + ",\"invalid\":" + (loader.files - loader.valid) + ",\"firstPassMicros\":" + loader.firstPassNanos / 1000
                + ",\"secondPassMicros\":" + loader.secondPassNanos / 1000 + ",\"rssKb\":" + rssKb() + "}";
    }

    /** one line per file: "folder/name.xml: valid requests=N steps=M" or "invalid <first error>"; compared between JVM and native */
    @GET
    @Path("/results")
    @Produces("text/plain")
    public String results() {
        StringBuilder sb = new StringBuilder();
        loader.results.forEach((k, v) -> sb.append(k).append(": ").append(v).append('\n'));
        return sb.toString();
    }

    private static long rssKb() {
        try {
            for (String line : Files.readAllLines(java.nio.file.Path.of("/proc/self/status"))) {
                if (line.startsWith("VmRSS:")) {
                    return Long.parseLong(line.replaceAll("[^0-9]", ""));
                }
            }
        } catch (Exception e) {
            // not Linux
        }
        return -1;
    }
}
