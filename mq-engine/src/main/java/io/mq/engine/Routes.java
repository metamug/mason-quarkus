package io.mq.engine;

import java.util.ArrayList;
import java.util.List;

import io.mq.core.model.Model;
import io.mq.core.reload.ResourceSet;

/**
 * Maps a method and a path to a request of a resource:
 * {@code /v1.0/customer}, {@code /v1.0/customer/7} (item), {@code /v1.0/customer/3/order} (parent customer, pid 3).
 * Built from one snapshot; take a new one after a reload.
 */
public final class Routes {

    public record Match(Model.Resource resource, Model.Request request, String id, String pid) {
    }

    private record Entry(Model.Resource resource, Model.Request request, List<String> pattern) {
    }

    private final List<Entry> entries = new ArrayList<>();

    public Routes(ResourceSet set) {
        for (Model.Resource r : set.resources().values()) {
            for (Model.Request q : r.requests()) {
                List<String> p = new ArrayList<>();
                p.add("v" + r.version());
                if (r.parent() != null) {
                    p.add(r.parent());
                    p.add("{pid}");
                }
                p.add(r.name());
                if (q.item() != null) {
                    p.add("{id}");
                }
                entries.add(new Entry(r, q, p));
            }
        }
    }

    /** the match, or null when no path fits; throws 405 when the path fits but not the method */
    public Match match(String method, String path) {
        List<String> segs = new ArrayList<>();
        for (String s : path.split("/")) {
            if (!s.isEmpty()) {
                segs.add(s);
            }
        }
        boolean pathFits = false;
        for (Entry e : entries) {
            if (e.pattern.size() != segs.size()) {
                continue;
            }
            String id = null;
            String pid = null;
            boolean ok = true;
            for (int i = 0; i < segs.size() && ok; i++) {
                String want = e.pattern.get(i);
                if (want.equals("{id}")) {
                    id = segs.get(i);
                } else if (want.equals("{pid}")) {
                    pid = segs.get(i);
                } else {
                    ok = want.equals(segs.get(i));
                }
            }
            if (!ok) {
                continue;
            }
            pathFits = true;
            if (e.request.method().equals(method) || (method.equals("HEAD") && e.request.method().equals("GET"))) {
                return new Match(e.resource, e.request, id, pid);
            }
        }
        if (pathFits) {
            throw new MqException(405, "method " + method + " is not available on " + path);
        }
        return null;
    }
}
