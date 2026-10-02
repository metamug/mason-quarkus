package io.mq.runtime;

import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.annotations.Recorder;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;

/**
 * Runs at application startup. Receives the object model that was recorded at build time and turns it into routes on
 * the Vert.x Router. No XML is read here.
 */
@Recorder
public class MqRecorder {

    public void registerRoutes(RuntimeValue<Router> routerValue, MqModel model) {
        Router router = routerValue.getValue();
        for (MqResource resource : model.resources) {
            for (MqRequest request : resource.requests) {
                String path = "/v" + resource.version + "/" + resource.name + (request.item ? "/:id" : "");
                Route route = router.route(io.vertx.core.http.HttpMethod.valueOf(request.method), path);
                if (!"GET".equals(request.method) && !"DELETE".equals(request.method)) {
                    route.handler(BodyHandler.create());
                }
                route.blockingHandler(new MqHandler(request));
            }
        }
    }
}
