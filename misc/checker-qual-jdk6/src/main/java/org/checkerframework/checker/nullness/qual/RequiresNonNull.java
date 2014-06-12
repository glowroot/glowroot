package org.checkerframework.checker.nullness.qual;

import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.CONSTRUCTOR;
import static java.lang.annotation.ElementType.METHOD;

@Target({METHOD, CONSTRUCTOR})
public @interface RequiresNonNull {
    String[] value();
}
