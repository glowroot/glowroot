/*
 * Copyright 2012-2016 the original author or authors.
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
package org.glowroot.agent.plugin.api.weaving;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

@Target(TYPE)
@Retention(RUNTIME)
public @interface Pointcut {

    // target class name
    String className() default "";
    // optionally (in addition to className or instead of className) restrict pointcut to classes
    // with the given annotation
    String classAnnotation() default "";
    // restrict pointcut to the given subclass and below
    // e.g. useful for pointcut on java.lang.Runnable.run(), but only for classes
    // matching com.yourcompany.*
    // also useful for pointcut on java.util.concurrent.Future.get(), but only for classes
    // under com.ning.http.client.ListenableFuture
    String methodDeclaringClassName() default "";
    /**
     * | and * can be used for limited regular expressions. Full regular expressions can be used by
     * starting and ending methodName with /.
     */
    // use "<init>" to weave constructors
    // patterns never match constructors
    // static initializers ("<clinit>") are not supported
    String methodName() default "";
    // optionally (in addition to methodName or instead of methodName) restrict pointcut to methods
    // with the given annotation
    String methodAnnotation() default "";
    // methodParameterTypes has no default since it's not obvious if default should be {} or {".."}
    String[] methodParameterTypes();
    String methodReturnType() default "";
    MethodModifier[] methodModifiers() default {};
    String nestingGroup() default "";
    // order is used to order two pointcuts on the same method
    // it is used to nest pointcut inside another, e.g. creating a pointcut on HttpServlet.service()
    // to override transaction type, in which case the pointcut's @OnBefore needs to occur after the
    // servlet plugin's @OnBefore which starts the transaction
    //
    // orders can be negative if an ordering before the default 0 is needed
    //
    // given a pointcut A with order 0 and a pointcut B with order 10:
    // * A's @OnBefore will be called before B's @OnBefore
    // * A's @OnReturn will be called after B's @OnReturn
    // * A's @OnThrow will be called after B's @OnThrow
    // * A's @OnAfter will be called after B's @OnAfter
    int order() default 0;
    String timer() default "";
    String supersedes() default "";

    /**
     * Replaced by {@link Pointcut#timer()}. Will be removed in version 1.0.0.
     */
    @Deprecated
    String timerName() default "";
}
