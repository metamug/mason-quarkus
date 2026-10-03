package io.mq.hosting;

import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;

@Path("/")
public class HostingResource {

    @Inject
    Backends backends;

    /** datasources of one backend (question B) */
    @GET
    @Path("/ds/{backend}")
    @Produces("application/json")
    public String ds(@PathParam("backend") String backend) throws Exception {
        try {
            return backends.probe(backend);
        } catch (Exception e) {
            return "{\"error\":\"" + e.toString().replace("\"", "'") + "\"}";
        }
    }

    @GET
    @Path("/mem")
    @Produces("application/json")
    public String mem() {
        Runtime rt = Runtime.getRuntime();
        return "{\"backends\":" + backends.backends.size() + ",\"heapUsedKb\":" + (rt.totalMemory() - rt.freeMemory()) / 1024
                + ",\"rssKb\":" + rssKb() + ",\"mode\":\"" + (System.getProperty("org.graalvm.nativeimage.imagecode") != null ? "native" : "jvm") + "\"}";
    }

    /** question A, option 2 (JVM only) */
    @POST
    @Path("/admin/{name}")
    @Produces("application/json")
    public String add(@PathParam("name") String name) throws Exception {
        return "{\"said\":\"" + backends.add(name) + "\"}";
    }

    @DELETE
    @Path("/admin/{name}")
    @Produces("application/json")
    public String remove(@PathParam("name") String name) {
        return "{\"removed\":" + backends.remove(name) + "}";
    }

    @GET
    @Path("/admin/unload-check")
    @Produces("application/json")
    public String unload() throws Exception {
        return backends.unloadCheck();
    }

    private static long rssKb() {
        try {
            for (String line : java.nio.file.Files.readAllLines(java.nio.file.Path.of("/proc/self/status"))) {
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
