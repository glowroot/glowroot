/*
 * Copyright 2012-2015 the original author or authors.
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

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Annotates a method in a {@literal @}{@link Pointcut} class that should be run just after each
 * method (or constructor) execution picked out by the {@link Pointcut}, but only if the method
 * picked out by the {@link Pointcut} throws an {@code Exception}. Only one method in a
 * 
 * {@literal @}{@code Pointcut} class may be annotated with {@code OnThrow}.
 * <p>
 * An {@literal @}{@code OnThrow} method can accept parameters annotated with any of the following:
 * {@literal @}{@link BindReceiver}, {@literal @}{@link BindParameter},
 * 
 * {@literal @}{@link BindParameterArray}, {@literal @}{@link BindMethodName},
 * 
 * {@literal @}{@link BindTraveler} or {@literal @}{@link BindThrowable}.
 * 
 * {@literal @}{@link BindTraveler} can only be used if there is a corresponding
 * 
 * {@literal @}{@link OnBefore} method that returns a non-{@code void} type (the <em>traveler</em>).
 * If {@literal @}{@link BindThrowable} is used, it must be the first parameter to the
 * 
 * {@literal @}{@code OnThrow} method.
 * <p>
 * An {@literal @}{@code OnThrow} method must return {@code void}. It is not able to suppress the
 * original {@code Exception} or change the {@code Exception} that is thrown (at least not
 * currently).
 */
@Target(METHOD)
@Retention(RUNTIME)
public @interface OnThrow {}
