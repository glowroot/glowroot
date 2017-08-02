package com.google.errorprone.annotations;

import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.CONSTRUCTOR;
import static java.lang.annotation.ElementType.METHOD;

@Target({CONSTRUCTOR, METHOD})
public @interface MustBeClosed {}
