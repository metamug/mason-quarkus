package io.mq.core.reload;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import io.mq.core.model.Model;
import io.mq.core.parser.Problem;

/**
 * An immutable snapshot of a resource folder: what is served now, and which files are currently invalid.
 * Readers take one snapshot and use it for a whole request, so a reload never shows a half-applied folder.
 *
 * @param generation counts reloads, starting at 0 for the empty set
 * @param resources resource name (file name without .xml) -> model, sorted by name
 * @param problems file name -> problems of the file as it is on disk now; its resource, if any, is the last valid version
 * @param reloadMicros how long building this snapshot took
 */
public record ResourceSet(long generation, Map<String, Model.Resource> resources, Map<String, List<Problem>> problems, long reloadMicros) {

    public static final ResourceSet EMPTY = new ResourceSet(0, Map.of(), Map.of(), 0);

    public ResourceSet {
        resources = java.util.Collections.unmodifiableMap(new TreeMap<>(resources));
        problems = java.util.Collections.unmodifiableMap(new TreeMap<>(problems));
    }

    public Model.Resource get(String name) {
        return resources.get(name);
    }

    /** the first problem of each invalid file, one per line; empty when every file is valid */
    public String problemSummary() {
        StringBuilder sb = new StringBuilder();
        for (var e : problems.entrySet()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(e.getValue().get(0));
        }
        return sb.toString();
    }
}
