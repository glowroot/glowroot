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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotates a method in a {@literal @}{@link Pointcut} class that should be run just after each
 * method execution picked out by the {@link Pointcut}, whether the method picked out by the
 * {@link Pointcut} returns successfully or throws an {@code Exception}. Only one method in a
 * {@literal @}{@code Pointcut} class may be annotated with {@literal @}{@code OnAfter}.
 * 
 * An {@literal @}{@link OnAfter} method is run after the {@literal @}{@link OnReturn} and
 * {@literal @}{@link OnThrow} methods, if either of those are present.
 * 
 * An {@literal @}{@code OnAfter} method can accept parameters annotated with any of the following:
 * {@literal @}{@link InjectTarget}, {@literal @}{@link InjectMethodArg},
 * 
 * {@literal @}{@link InjectMethodArgArray}, {@literal @}{@link InjectMethodName} or
 * 
 * {@literal @}{@link InjectTraveler}. {@literal @}{@link InjectTraveler} can only be used if there
 * is a corresponding {@literal @}{@link OnBefore} method that returns a non-{@code void} type (the
 * <em>traveler</em>). Any un-annotated parameters are implicitly annotated with
 * 
 * {@literal @}{@link InjectMethodArg}.
 * 
 * An {@literal @}{@code OnAfter} method must return {@code void}.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OnAfter {}
