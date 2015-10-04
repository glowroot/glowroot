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

import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Annotates a parameter to any {@literal @}{@link Pointcut} advice method
 * 
 * ({@literal @}{@link IsEnabled}, {@literal @}{@link OnBefore}, {@literal @}{@link OnReturn},
 * {@literal @}{@link OnThrow}, {@literal @}{@link OnAfter}).
 * <p>
 * When the advice method is called, the name of the method matched by the
 * 
 * {@literal @}{@link Pointcut} is bound to this parameter. This is useful when the
 * {@link Pointcut#methodName()} uses wildcards or is a regular expression.
 * <p>
 * Parameters annotated with {@literal @}{@link BindMethodName} must be of type {@link String}.
 */
@Target(PARAMETER)
@Retention(RUNTIME)
public @interface BindMethodName {}
