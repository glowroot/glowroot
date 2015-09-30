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

/**
 * Used for restricting a {@link Pointcut} to methods with or without particular modifiers.
 * 
 * <p>
 * {@link #PUBLIC} and {@link #NOT_STATIC} are useful for creating a pointcut that applies to all
 * public instance methods in a class.
 * 
 * <p>
 * {@link #STATIC} is useful for creating a pointcut that applies to
 * {@code public static void main(String[] args)} methods.
 * 
 * <p>
 * Additional modifiers can easily be supported if additional use cases arise.
 * 
 * @see Pointcut#methodModifiers()
 */
public enum MethodModifier {

    PUBLIC, STATIC, NOT_STATIC;
}
