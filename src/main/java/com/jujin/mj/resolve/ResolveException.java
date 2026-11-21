package com.jujin.mj.resolve;

/**
 * The dependency graph violates one of mj's frozen rules: a cross-major
 * conflict, a depth-budget breach, a circular dependency, a mixed-species id.
 * The message carries the full introduction path and the exits that stay
 * inside the rules — surfacing it is the "dependency review moment"
 * (DESIGN.md §4.2), not a stack trace.
 */
public final class ResolveException extends RuntimeException {

    public ResolveException(String message) {
        super(message);
    }

    public ResolveException(String message, Throwable cause) {
        super(message, cause);
    }
}
