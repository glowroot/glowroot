package com.google.errorprone.annotations;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import javax.lang.model.element.Modifier;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.RetentionPolicy.CLASS;

@Retention(CLASS)
@Target(ANNOTATION_TYPE)
public @interface RequiredModifiers {
    Modifier[] value();
}
