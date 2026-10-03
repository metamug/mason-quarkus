package io.mq.engine;

/** a failure that maps to an HTTP status: 400 bad input, 404 no route, 409 constraint violation, 500 the rest */
public final class MqException extends RuntimeException {

    private static final long serialVersionUID = 1L;
    public final int status;

    public MqException(int status, String message) {
        super(message);
        this.status = status;
    }

    public MqException(int status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }
}
