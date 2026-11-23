package com.jujin.jeno.test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks one test method. Any thrown exception — {@link AssertionError}
 * included — fails the test; a clean return passes. This is the whole
 * contract, node:test style: the framework ships with the tool, the test
 * ships with the project.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface JenoTest {
}
