package io.mq.engine;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.regex.Pattern;

import io.mq.core.model.Model;

/** applies the {@code <Param>} declarations of a request to the incoming parameters: required, default, type, range, length, pattern */
final class Inputs {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private Inputs() {
    }

    static void apply(List<Model.Param> declared, Context ctx) {
        for (Model.Param p : declared) {
            Object v = ctx.params.get(p.name());
            boolean blank = v == null || (v instanceof String s && s.isEmpty());
            if (blank) {
                if (p.value() != null) {
                    ctx.params.put(p.name(), p.value());
                    v = p.value();
                } else if (p.required()) {
                    throw new MqException(400, "parameter '" + p.name() + "' is required");
                } else {
                    continue;
                }
            }
            ctx.params.put(p.name(), convert(p, v));
        }
    }

    private static Object convert(Model.Param p, Object v) {
        String name = p.name();
        String text = String.valueOf(v);
        try {
            switch (p.type()) {
                case "number": {
                    BigDecimal n = v instanceof BigDecimal b ? b : new BigDecimal(text.trim());
                    if (p.min() != null && n.compareTo(new BigDecimal(p.min().trim())) < 0) {
                        throw bad(name, "must be at least " + p.min());
                    }
                    if (p.max() != null && n.compareTo(new BigDecimal(p.max().trim())) > 0) {
                        throw bad(name, "must be at most " + p.max());
                    }
                    Object out = n.scale() <= 0 && n.precision() <= 18 ? (Object) n.longValue() : n;
                    return length(p, text, out);
                }
                case "date":
                    return LocalDate.parse(text.trim());
                case "datetime": {
                    String t = text.trim();
                    return t.endsWith("Z") || t.matches(".*[+-]\\d\\d:\\d\\d$") ? OffsetDateTime.parse(t).toLocalDateTime() : LocalDateTime.parse(t);
                }
                case "time":
                    return LocalTime.parse(text.trim());
                case "email":
                    if (!EMAIL.matcher(text).matches()) {
                        throw bad(name, "must be an email address");
                    }
                    return length(p, text, text);
                case "url":
                    try {
                        if (java.net.URI.create(text).getScheme() == null) {
                            throw bad(name, "must be a URL");
                        }
                    } catch (IllegalArgumentException e) {
                        throw bad(name, "must be a URL");
                    }
                    return length(p, text, text);
                default:
                    return length(p, text, v);
            }
        } catch (NumberFormatException e) {
            throw bad(name, "must be a number, found '" + v + "'");
        } catch (DateTimeParseException e) {
            throw bad(name, "must be a " + p.type() + " in ISO format, found '" + v + "'");
        }
    }

    private static Object length(Model.Param p, String text, Object out) {
        if (p.minlength() != null && text.length() < Integer.parseInt(p.minlength().trim())) {
            throw bad(p.name(), "must have at least " + p.minlength().trim() + " characters");
        }
        if (p.maxlength() != null && text.length() > Integer.parseInt(p.maxlength().trim())) {
            throw bad(p.name(), "must have at most " + p.maxlength().trim() + " characters");
        }
        if (p.pattern() != null && !Pattern.matches(p.pattern(), text)) {
            throw bad(p.name(), "does not match the pattern " + p.pattern());
        }
        return out;
    }

    private static MqException bad(String name, String why) {
        return new MqException(400, "parameter '" + name + "' " + why);
    }
}
