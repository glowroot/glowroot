/*
 * Copyright 2012-2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.api.weaving;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Target(TYPE)
@Retention(RUNTIME)
public @interface Pointcut {

    String typeName();
    /**
     * | and * can be used for limited regular expressions. Full regular expressions can be used by
     * starting and ending methodName with /.
     */
    // use "<init>" to weave constructors
    // patterns never match constructors
    // static initializers ("<clinit>") are not supported
    String methodName();
    String[] methodArgs() default {};
    String methodReturn() default "";
    MethodModifier[] methodModifiers() default {};
    boolean ignoreSameNested() default false;
    String metricName() default "";
}
