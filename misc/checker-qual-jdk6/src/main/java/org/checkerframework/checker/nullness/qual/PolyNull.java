package org.checkerframework.checker.nullness.qual;

import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;

@Target({FIELD, PARAMETER, METHOD})
public @interface PolyNull {}
