package io.mq.leverage;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import io.agroal.api.AgroalDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.arc.Arc;
import io.quarkus.cache.CacheResult;
import io.quarkus.scheduler.Scheduled;
import io.vertx.ext.web.Router;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/** Routes added at run time with the Vert.x router (as MQ does), secured, measured, cached, scheduled. */
@ApplicationScoped
public class Hello {

    @Inject
    MeterRegistry registry;

    @Inject
    Squares squares;

    void routes(@Observes Router router) {
        router.get("/open/hello").handler(rc -> {
            registry.counter("mq_requests", "route", "open").increment();
            rc.response().putHeader("Content-Type", "application/json").end("{\"hello\":\"open\"}");
        });
        // /secure/* is protected by quarkus.http.auth.permission.secured: a route added by code is covered like any other
        router.get("/secure/hello").handler(rc -> {
            registry.counter("mq_requests", "route", "secure").increment();
            var user = rc.user();
            rc.response().putHeader("Content-Type", "application/json").end("{\"hello\":\"secure\",\"user\":\"" + (user == null ? "?" : user.principal().getString("upn", user.principal().getString("sub", "?"))) + "\"}");
        });
        // a datasource chosen by name at run time, the way a step with datasource="reports" would
        router.get("/db/:name").blockingHandler(rc -> {
            String name = rc.pathParam("name");
            try {
                AgroalDataSource ds = name.equals("main") ? Arc.container().instance(AgroalDataSource.class).get()
                        : Arc.container().instance(AgroalDataSource.class, new io.quarkus.agroal.DataSource.DataSourceLiteral(name)).get();
                String table = name.equals("main") ? "items" : "report_rows";
                try (Connection c = ((DataSource) ds).getConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
                    rs.next();
                    rc.response().putHeader("Content-Type", "application/json").end("{\"datasource\":\"" + name + "\",\"rows\":" + rs.getInt(1) + "}");
                }
            } catch (Exception e) {
                rc.response().setStatusCode(500).end(e.toString());
            }
        }, false);
        router.get("/cached/:n").handler(rc -> {
            int n = Integer.parseInt(rc.pathParam("n"));
            int v = squares.square(n);
            rc.response().putHeader("Content-Type", "application/json").end("{\"square\":" + v + ",\"computed\":" + squares.calls.get() + "}");
        });
    }

    @Scheduled(every = "1s")
    void tick() {
        registry.counter("mq_ticks").increment();
    }

    @ApplicationScoped
    public static class Squares {
        final AtomicInteger calls = new AtomicInteger();

        @CacheResult(cacheName = "squares")
        public int square(int n) {
            calls.incrementAndGet();
            return n * n;
        }
    }
}
