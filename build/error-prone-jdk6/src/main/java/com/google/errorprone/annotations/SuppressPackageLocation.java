package com.google.errorprone.annotations;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.PACKAGE;
import static java.lang.annotation.RetentionPolicy.CLASS;

@Target(PACKAGE)
@Retention(CLASS)
public @interface SuppressPackageLocation {}
