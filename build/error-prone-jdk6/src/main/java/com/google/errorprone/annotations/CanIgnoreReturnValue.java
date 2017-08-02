package com.google.errorprone.annotations;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.CLASS;

@Target({METHOD, TYPE})
@Retention(CLASS)
public @interface CanIgnoreReturnValue {}
