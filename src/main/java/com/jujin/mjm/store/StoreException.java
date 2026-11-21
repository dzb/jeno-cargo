package com.jujin.mjm.store;

/** A download or store write failed. The message names the URL and the reason. */
public final class StoreException extends RuntimeException {

    public StoreException(String message) {
        super(message);
    }

    public StoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
