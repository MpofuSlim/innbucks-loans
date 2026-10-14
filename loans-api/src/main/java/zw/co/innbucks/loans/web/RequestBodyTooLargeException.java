package zw.co.innbucks.loans.web;

import java.io.IOException;

/**
 * A request body read past the size its endpoint accepts: thrown by the stream itself, so nothing past the limit is
 * read, and answered 413 {@code PAYLOAD_TOO_LARGE} ({@link GlobalExceptionHandler}). The staging test actions cap
 * their bodies this way when no Content-Length was declared.
 */
public class RequestBodyTooLargeException extends IOException {

    public RequestBodyTooLargeException(long limitBytes) {
        super("The request body must be at most " + limitBytes + " bytes");
    }
}
