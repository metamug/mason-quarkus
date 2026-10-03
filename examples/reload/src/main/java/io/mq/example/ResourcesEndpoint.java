package io.mq.example;

import java.util.UUID;
import java.util.stream.Collectors;

import io.mq.core.reload.ResourceSet;
import io.mq.quarkus.runtime.MqResources;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;

/** Minimal endpoints for the reload measurements: /r/{name} returns the Desc of a resource, /status describes the model. */
@Path("/")
public class ResourcesEndpoint {

    /** changes whenever the application (not just the model) restarts */
    static final String APP_START_ID = UUID.randomUUID().toString().substring(0, 8);

    @Inject
    MqResources mq;

    void onStart(@Observes StartupEvent e) {
        System.out.println("MQ-EXAMPLE app started " + APP_START_ID);
    }

    @GET
    @Path("/r/{name}")
    public Response get(@PathParam("name") String name) {
        var r = mq.current().get(name);
        return r == null ? Response.status(404).entity("no such resource").build() : Response.ok(r.desc() == null ? "" : r.desc()).build();
    }

    @GET
    @Path("/status")
    public String status() {
        ResourceSet s = mq.current();
        String names = s.resources().keySet().stream().map(n -> "\"" + n + "\"").collect(Collectors.joining(","));
        return "{\"appStartId\":\"" + APP_START_ID + "\",\"generation\":" + s.generation() + ",\"resources\":[" + names
                + "],\"lastReloadMicros\":" + s.reloadMicros() + ",\"lastError\":\""
                + s.problemSummary().replace("\\", "/").replace("\"", "'").replace("\n", " ") + "\"}";
    }
}
