package io.mq.core.parser;

/** one validation error: where it is and what is wrong */
public record Problem(String file, int line, int column, String message) {

    @Override
    public String toString() {
        return file + ":" + line + ": " + message;
    }
}
