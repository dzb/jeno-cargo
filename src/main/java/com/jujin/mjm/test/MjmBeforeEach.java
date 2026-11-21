package com.jujin.mjm.test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Runs before every {@link MjTest} method of the same class. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface MjBeforeEach {
}
