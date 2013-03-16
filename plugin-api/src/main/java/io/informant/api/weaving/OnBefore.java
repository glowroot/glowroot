/**
 * Copyright 2012-2013 the original author or authors.
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
package io.informant.api.weaving;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Annotates a method in a {@literal @}{@link Pointcut} class that should be run just before each
 * method execution picked out by the {@link Pointcut}. Only one method in a
 * 
 * {@literal @}{@code Pointcut} class may be annotated with {@literal @}{@code OnBefore}.
 * <p>
 * An {@literal @}{@code OnBefore} method can accept parameters annotated with any of the following:
 * {@literal @}{@link BindTarget}, {@literal @}{@link BindMethodArg},
 * 
 * {@literal @}{@link BindMethodArgArray} or {@literal @}{@link BindMethodName}.
 * <p>
 * An {@literal @}{@code OnBefore} method may return {@code void} or a non-{@code void} type. If it
 * returns a non-{@code void} type, the value returned by the {@literal @}{@code OnBefore} method is
 * called the <em>traveler</em>, and is available as input to subsequent {@literal @}
 * {@link OnReturn}, {@literal @}{@link OnThrow} and {@literal @}{@link OnAfter} methods by
 * annotating a parameter on any of these methods with {@literal @}{@link BindTraveler}.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Target(METHOD)
@Retention(RUNTIME)
public @interface OnBefore {}
