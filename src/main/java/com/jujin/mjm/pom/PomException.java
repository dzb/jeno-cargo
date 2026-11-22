package com.jujin.mjm.pom;

/**
 * A pom could not be decoded into mjm's model: the artifact or one of its
 * parents/BOMs could not be fetched, the XML was malformed, or the pom used
 * something outside mjm's supported subset (version ranges, snapshots, exotic
 * packaging). The message always names the artifact and the reason; callers
 * surface it as the next action, not a stack trace.
 */
public final class PomException extends RuntimeException {

    public PomException(String message) {
        super(message);
    }

    public PomException(String message, Throwable cause) {
        super(message, cause);
    }
}
