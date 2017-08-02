package com.google.errorprone.annotations;

import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

@Inherited
@Retention(RUNTIME)
@Target({TYPE, ANNOTATION_TYPE})
public @interface DoNotMock {
    String value() default "Create a real instance instead";
}
