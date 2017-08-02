package com.google.errorprone.annotations;

import java.lang.annotation.Annotation;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.CONSTRUCTOR;
import static java.lang.annotation.ElementType.METHOD;

@Target({CONSTRUCTOR, METHOD})
public @interface RestrictedApi {
    public String checkerName() default "RestrictedApi";
    public String explanation();
    public String link();
    public String allowedOnPath() default "";
    public Class<? extends Annotation>[] whitelistAnnotations() default {};
    public Class<? extends Annotation>[] whitelistWithWarningAnnotations() default {};
}
