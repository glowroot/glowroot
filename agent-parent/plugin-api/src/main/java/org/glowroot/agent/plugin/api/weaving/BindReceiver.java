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
 * Annotates a parameter to an {@literal @}{@link IsEnabled}, {@literal @}{@link OnBefore},
 * {@literal @}{@link OnReturn}, {@literal @}{@link OnThrow} or {@literal @}{@link OnAfter} method
 * in a {@literal @}{@link Pointcut} class.
 */
// for non-static methods, binds "this"
@Target(PARAMETER)
@Retention(RUNTIME)
public @interface BindReceiver {}
