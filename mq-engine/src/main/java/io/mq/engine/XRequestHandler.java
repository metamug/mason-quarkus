package io.mq.engine;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.mq.core.model.Model;

/**
 * Runs an {@code <XRequest>}: an HTTP call to another API with the JDK HttpClient.
 * The url, header values, param values and body may contain {@code $variables} (request parameters and mpath into earlier steps) and
 * {@code {{properties}}} of the backend. Params go into the query string, or into a form body when a POST or PUT sends
 * {@code Content-Type: application/x-www-form-urlencoded}. The result is an {@link XResponse}. An answer with any HTTP status is a
 * result (the request carries on; a later step can look at {@code $[x].statusCode}); only a failure to get an answer (connection,
 * timeout) fails the request with 502.
 */
public final class XRequestHandler implements Engine.StepHandler {

    private static final Pattern PROPERTY = Pattern.compile("\\{\\{([^}]+)}}");

    private final Map<String, String> properties;
    private final HttpClient client;
    private final Duration timeout;

    public XRequestHandler(Map<String, String> properties, Duration timeout) {
        this.properties = properties;
        this.timeout = timeout;
        this.client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    @Override
    public Object run(Model.Step step, Context ctx) throws Exception {
        Model.XRequest x = (Model.XRequest) step;
        String url = Engine.render(withProperties(x.url(), x), ctx);
        StringBuilder query = new StringBuilder();
        Map<String, String> form = new LinkedHashMap<>();
        String contentType = null;
        for (Model.Header h : x.headers()) {
            if (h.name().equalsIgnoreCase("Content-Type")) {
                contentType = Engine.render(h.value(), ctx);
            }
        }
        boolean formBody = contentType != null && contentType.toLowerCase().contains("x-www-form-urlencoded")
                && (x.method().equals("POST") || x.method().equals("PUT"));
        for (Model.Header p : x.params()) {
            String value = Engine.render(p.value(), ctx);
            if (formBody) {
                form.put(p.name(), value);
            } else {
                query.append(query.length() == 0 && url.indexOf('?') < 0 ? '?' : '&').append(enc(p.name())).append('=').append(enc(value));
            }
        }
        String bodyText = null;
        if (formBody && x.body() == null) {
            StringBuilder b = new StringBuilder();
            for (Map.Entry<String, String> e : form.entrySet()) {
                b.append(b.length() == 0 ? "" : "&").append(enc(e.getKey())).append('=').append(enc(e.getValue()));
            }
            bodyText = b.toString();
        } else if (x.body() != null) {
            bodyText = Engine.render(x.body(), ctx);
        }
        HttpRequest.Builder rb;
        try {
            rb = HttpRequest.newBuilder(URI.create(url + query)).timeout(timeout);
        } catch (IllegalArgumentException e) {
            throw new MqException(500, "XRequest '" + x.id() + "' (line " + x.line() + ") has an invalid url: " + e.getMessage());
        }
        for (Model.Header h : x.headers()) {
            rb.header(h.name(), Engine.render(h.value(), ctx));
        }
        HttpRequest.BodyPublisher publisher = bodyText == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(bodyText, StandardCharsets.UTF_8);
        rb.method(x.method(), publisher);
        HttpResponse<String> r;
        try {
            r = client.send(rb.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new MqException(502, "XRequest '" + x.id() + "' got no answer from " + hostOf(url) + ": " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MqException(502, "XRequest '" + x.id() + "' was interrupted", e);
        }
        Map<String, String> headers = new LinkedHashMap<>();
        r.headers().map().forEach((k, v) -> headers.put(k, String.join(", ", v)));
        return new XResponse(r.statusCode(), headers, parse(r.body(), headers));
    }

    /** replaces {{name}} with the backend property; an unknown property is a configuration error, not an empty string */
    String withProperties(String text, Model.XRequest x) {
        Matcher m = PROPERTY.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String v = properties.get(m.group(1).trim());
            if (v == null) {
                throw new MqException(500, "XRequest '" + x.id() + "' (line " + x.line() + ") uses the backend property {{" + m.group(1).trim()
                        + "}}, which is not defined");
            }
            m.appendReplacement(out, Matcher.quoteReplacement(v));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static Object parse(String body, Map<String, String> headers) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String t = body.stripLeading();
        boolean json = headers.entrySet().stream().anyMatch(e -> e.getKey().equalsIgnoreCase("content-type") && e.getValue().contains("json"))
                || t.startsWith("{") || t.startsWith("[");
        if (json) {
            try {
                return Json.parse(body);
            } catch (IllegalArgumentException e) {
                return body;
            }
        }
        return body;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String hostOf(String url) {
        try {
            URI u = URI.create(url);
            return u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
        } catch (IllegalArgumentException e) {
            return url;
        }
    }
}
