package io.genesis.sandbox;

import java.io.IOException;

/** Network or protocol failure before a valid API response was received. */
public final class TransportException extends IOException {
    TransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
