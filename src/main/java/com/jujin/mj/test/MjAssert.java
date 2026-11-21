package com.jujin.mj.test;

/**
 * The built-in assertions — deliberately tiny. A failed assertion throws
 * {@link AssertionError}, which is exactly what the runner treats as a
 * failure; anything thrown fails a test.
 */
public final class MjAssert {

    private MjAssert() {}

    public static void equals(Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
        }
    }

    public static void true_(boolean condition) {
        if (!condition) {
            throw new AssertionError("expected true");
        }
    }

    public static void false_(boolean condition) {
        if (condition) {
            throw new AssertionError("expected false");
        }
    }

    public static void notNull(Object value) {
        if (value == null) {
            throw new AssertionError("expected a value, was null");
        }
    }
}
