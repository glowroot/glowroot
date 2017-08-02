package com.google.errorprone.annotations;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.LOCAL_VARIABLE;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static javax.lang.model.element.Modifier.FINAL;

@Target({FIELD, PARAMETER, LOCAL_VARIABLE})
@Retention(RUNTIME)
@IncompatibleModifiers(FINAL)
public @interface Var {}
