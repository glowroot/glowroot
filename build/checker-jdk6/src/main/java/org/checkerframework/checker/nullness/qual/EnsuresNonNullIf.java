package org.checkerframework.checker.nullness.qual;

import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.METHOD;

@Target(METHOD)
public @interface EnsuresNonNullIf {
    String[] expression();
    boolean result();
}
