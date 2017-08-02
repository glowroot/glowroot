package com.google.errorprone.annotations;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.CLASS;

@Retention(CLASS)
@Target(PARAMETER)
public @interface FormatString {}
