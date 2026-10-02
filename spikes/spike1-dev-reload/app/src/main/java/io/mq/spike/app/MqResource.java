package io.mq.spike.app;

import java.util.UUID;

import io.mq.spike.runtime.MqRegistry;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.event.Observes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;

@Path("/")
public class MqResource {

    /** changes whenever the application (not just the model) restarts */
    static final String APP_START_ID = UUID.randomUUID().toString().substring(0, 8);

    void onStart(@Observes StartupEvent e) {
        System.out.println("MQSPIKE app started " + APP_START_ID);
    }

    @GET
    @Path("/r/{name}")
    public Response get(@PathParam("name") String name) {
        String v = MqRegistry.INSTANCE.get(name);
        return v == null ? Response.status(404).entity("no such resource").build() : Response.ok(v).build();
    }

    @GET
    @Path("/status")
    public String status() {
        return MqRegistry.INSTANCE.status().replace("{", "{\"appStartId\":\"" + APP_START_ID + "\",");
    }

    @GET
    @Path("/scan")
    public String scan() throws Exception {
        return String.valueOf(MqRegistry.INSTANCE.scanNow());
    }
}
