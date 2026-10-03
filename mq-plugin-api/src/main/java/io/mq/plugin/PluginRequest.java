package io.mq.plugin;

import java.util.Map;

import javax.sql.DataSource;

/** What a plugin may look at: the request and the results so far. */
public interface PluginRequest {

    /** the request parameters as text */
    Map<String, String> params();

    /** results of the steps run before this one, by step id */
    Map<String, Object> steps();

    /** the path values; null when the path has none */
    String id();

    String pid();

    /** the default datasource of the application */
    DataSource dataSource();
}
