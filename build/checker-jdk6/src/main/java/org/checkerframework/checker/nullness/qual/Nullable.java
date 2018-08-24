package org.checkerframework.checker.nullness.qual;

import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.LOCAL_VARIABLE;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;

@Target({FIELD, METHOD, PARAMETER, LOCAL_VARIABLE})
public @interface Nullable {}
