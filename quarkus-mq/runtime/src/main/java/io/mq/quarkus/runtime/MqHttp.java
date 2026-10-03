package io.mq.quarkus.runtime;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import javax.sql.DataSource;

import org.jboss.logging.Logger;

import io.mq.engine.Dispatcher;
import io.mq.engine.Engine;
import io.mq.engine.Reply;
import io.quarkus.runtime.ShutdownEvent;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * Exposes the resources over HTTP: {@code /v<version>/<resource>[/<id>]} goes to the engine on a worker thread (JDBC blocks) and the
 * reply is written as JSON. Routes are not registered per resource: the engine matches against the current snapshot on every request, so a
 * reload needs no change to the router.
 */
@ApplicationScoped
public class MqHttp {

    private static final Logger LOG = Logger.getLogger(MqHttp.class);

    @Inject
    MqResources resources;

    @Inject
    Instance<DataSource> dataSource;

    private volatile Dispatcher dispatcher;

    private Dispatcher dispatcher() {
        Dispatcher d = dispatcher;
        if (d == null) {
            synchronized (this) {
                if (dispatcher == null) {
                    // the default datasource of the application; a step with a datasource name is not supported yet
                    dispatcher = new Dispatcher(resources::current, new Engine(name -> dataSource.isResolvable() ? dataSource.get() : null));
                }
                d = dispatcher;
            }
        }
        return d;
    }

    void routes(@Observes Router router) {
        router.routeWithRegex("/v[0-9][^/]*/.*").handler(BodyHandler.create()).blockingHandler(this::handle, false);
        LOG.info("MQ serves /v<version>/<resource>[/<id>]");
    }

    void stop(@Observes ShutdownEvent e) {
        dispatcher = null;
    }

    private void handle(RoutingContext rc) {
        var req = rc.request();
        String body = rc.body() == null || rc.body().buffer() == null ? null : rc.body().buffer().toString(StandardCharsets.UTF_8);
        Reply reply = dispatcher().handle(req.method().name(), req.path(), req.query(), body, req.getHeader("Content-Type"));
        var resp = rc.response().setStatusCode(reply.status()).putHeader("Content-Type", "application/json");
        for (Map.Entry<String, String> h : reply.headers().entrySet()) {
            resp.putHeader(h.getKey(), h.getValue());
        }
        resp.end(reply.json());
    }
}
