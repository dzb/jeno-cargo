package com.jujin.jeno.pom;

/**
 * A pom could not be decoded into jeno's model: the artifact or one of its
 * parents/BOMs could not be fetched, the XML was malformed, or the pom used
 * something outside jeno's supported subset (version ranges, snapshots, exotic
 * packaging). The message always names the artifact and the reason; callers
 * surface it as the next action, not a stack trace.
 */
public final class PomException extends RuntimeException {
    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public PomException(String message) {
        super(message);
    }

    public PomException(String message, Throwable cause) {
        super(message, cause);
    }
}
